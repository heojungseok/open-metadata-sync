package com.heojungseok.openmetadatasync.batch.collect;

import java.net.URI;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.heojungseok.openmetadatasync.OpenMetadataSyncApplication;
import com.heojungseok.openmetadatasync.crossref.CrossrefPage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JpaCollectStoreTest {

	@Container
	static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10");

	private static ConfigurableApplicationContext context;
	private static JdbcTemplate jdbc;
	private static JpaCollectStore store;
	private static Statistics hibernateStatistics;

	@BeforeAll
	static void startApplication() throws SQLException {
		try (Connection connection = DriverManager.getConnection(
				MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE open_metadata CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
		}
		context = new SpringApplicationBuilder(OpenMetadataSyncApplication.class)
				.profiles("actual")
				.properties("spring.main.banner-mode=off")
				.properties("spring.jpa.properties.hibernate.generate_statistics=true")
				.run(
						"--DB_HOST=" + MYSQL.getHost(),
						"--DB_PORT=" + MYSQL.getMappedPort(3306),
						"--DB_USERNAME=root",
						"--DB_PASSWORD=" + MYSQL.getPassword()
				);
		jdbc = context.getBean(JdbcTemplate.class);
		assertThat(context.getBean(PlatformTransactionManager.class)).isInstanceOf(JpaTransactionManager.class);
		store = new JpaCollectStore(
				context.getBean(jakarta.persistence.EntityManager.class),
				context.getBean(PlatformTransactionManager.class)
		);
		hibernateStatistics = context.getBean(jakarta.persistence.EntityManagerFactory.class)
				.unwrap(SessionFactory.class).getStatistics();
	}

	@AfterAll
	static void stopApplication() {
		if (context != null) {
			context.close();
		}
	}

	@Test
	void finalPageFailureRollsBackRowsWindowCompletionAndExecutionFreeze() {
		Ids ids = insertCollectingExecution();
		CrossrefCollector.PageWrite page = new CrossrefCollector.PageWrite(
				ids.executionId(), ids.windowId(), "*", "cursor-1", 1, 2, 0,
				List.of(work("10.1000/valid"), work(null)), Instant.parse("2026-08-08T00:00:00Z")
		);

		assertThatThrownBy(() -> store.persist(page, CrossrefCollector.Completion.EXECUTION))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("DOI is required");

		assertThat(count("SELECT COUNT(*) FROM staging_work WHERE execution_id = ?", ids.executionId())).isZero();
		assertThat(count("SELECT collected_count FROM sync_window WHERE id = ?", ids.windowId())).isZero();
		assertThat(jdbc.queryForMap(
				"SELECT status, collected_count FROM sync_window WHERE id = ?", bytes(ids.windowId())
		)).containsEntry("status", "COLLECTING");
		assertThat(jdbc.queryForMap(
				"SELECT business_status, expected_count, staging_upper_bound FROM sync_execution WHERE id = ?",
				bytes(ids.executionId())
		)).containsEntry("business_status", "COLLECTING")
				.containsEntry("expected_count", null)
				.containsEntry("staging_upper_bound", null);
	}

	@Test
	void duplicatePageIsIdempotentAndCompletionFreezesTypedProjectionAndUpperBound() {
		Ids ids = insertCollectingExecution();
		CrossrefCollector.PageWrite page = new CrossrefCollector.PageWrite(
				ids.executionId(), ids.windowId(), "*", "cursor-1", 1, 2, 0,
				List.of(work("10.1000/one"), work("10.1000/two")), Instant.parse("2026-08-08T00:00:00Z")
		);

		store.persist(page, CrossrefCollector.Completion.PAGE);
		hibernateStatistics.clear();
		store.persist(page, CrossrefCollector.Completion.PAGE);
		assertThat(hibernateStatistics.getQueryExecutionCount()).isEqualTo(1);
		CrossrefCollector.PageWrite replay = new CrossrefCollector.PageWrite(
				ids.executionId(), ids.windowId(), "*", "cursor-1", 1, 2, 2,
				page.items(), page.collectedAt()
		);
		CrossrefCollector.Frozen frozen = store.persist(replay, CrossrefCollector.Completion.EXECUTION).frozen();

		assertThat(count("SELECT COUNT(*) FROM staging_work WHERE execution_id = ?", ids.executionId())).isEqualTo(2);
		assertThat(frozen.expectedCount()).isEqualTo(2);
		assertThat(frozen.stagingUpperBound()).isPositive();
		assertThat(jdbc.queryForMap(
				"SELECT doi, title, JSON_UNQUOTE(JSON_EXTRACT(source_json, '$.DOI')) source_doi "
						+ "FROM staging_work WHERE execution_id = ? ORDER BY execution_sequence LIMIT 1",
				bytes(ids.executionId())
		)).containsEntry("doi", "10.1000/one")
				.containsEntry("title", "Title")
				.containsEntry("source_doi", "10.1000/one");
		var execution = jdbc.queryForMap(
				"SELECT expected_count, staging_upper_bound, business_status, finished_at "
						+ "FROM sync_execution WHERE id = ?",
				bytes(ids.executionId())
		);
		assertThat(((Number) execution.get("expected_count")).longValue()).isEqualTo(2);
		assertThat(((Number) execution.get("staging_upper_bound")).longValue())
				.isEqualTo(frozen.stagingUpperBound());
		assertThat(execution).containsEntry("business_status", "COLLECTED");
		assertThat(execution).containsEntry("finished_at", null);
	}

	@Test
	void zeroNewPagePastReplayFrontierDoesNotAdvanceDurableWindowProgress() {
		Ids ids = insertCollectingExecution();
		List<CrossrefPage.Work> items = List.of(
				work("10.1000/one"), work("10.1000/two"), work("10.1000/three"), work("10.1000/four")
		);
		store.persist(new CrossrefCollector.PageWrite(
				ids.executionId(), ids.windowId(), "*", "cursor-1", 1, 4, 0,
				items, Instant.parse("2026-08-08T00:00:00Z")
		), CrossrefCollector.Completion.PAGE);
		jdbc.update("UPDATE sync_window SET collected_count = 2 WHERE id = ?", bytes(ids.windowId()));

		CrossrefCollector.PageCommit commit = store.persist(new CrossrefCollector.PageWrite(
				ids.executionId(), ids.windowId(), "cursor-1", "cursor-2", 3, 4, 2,
				items.subList(2, 4), Instant.parse("2026-08-08T00:00:01Z")
		), CrossrefCollector.Completion.PAGE);

		assertThat(commit.insertedCount()).isZero();
		assertThat(jdbc.queryForMap(
				"SELECT collected_count, status FROM sync_window WHERE id = ?", bytes(ids.windowId())
		)).satisfies(window -> {
			assertThat(((Number) window.get("collected_count")).longValue()).isEqualTo(2);
			assertThat(window).containsEntry("status", "COLLECTING");
		});
	}

	@Test
	void restartShortPageBeforeReplayFrontierFailsWithoutMutatingCommittedState() {
		assertPrematureReplayRejected(page(347, "short-end", 0, 347));
	}

	@Test
	void restartEmptyPageBeforeReplayFrontierFailsWithoutMutatingCommittedState() {
		assertPrematureReplayRejected(page(0, "empty-end", 0, 0));
	}

	@Test
	void directWindowAndExecutionCompletionBelowDurableFrontierRollsBack() {
		for (CrossrefCollector.Completion completion : List.of(
				CrossrefCollector.Completion.WINDOW, CrossrefCollector.Completion.EXECUTION
		)) {
			Ids ids = insertCollectingExecution();
			List<CrossrefPage.Work> items = List.of(work("10.1000/one"), work("10.1000/two"));
			store.persist(new CrossrefCollector.PageWrite(
					ids.executionId(), ids.windowId(), "*", "seed-next", 1, 2, 0,
					items, Instant.parse("2026-08-08T00:00:00Z")
			), CrossrefCollector.Completion.PAGE);

			assertThatThrownBy(() -> store.persist(new CrossrefCollector.PageWrite(
					ids.executionId(), ids.windowId(), "stale", "stale-next", 1, 1, 2,
					items.subList(0, 1), Instant.parse("2026-08-08T00:00:01Z")
			), completion)).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("replay frontier");

			assertCollectingState(ids, 2, "*", "seed-next");
		}
	}

	@Test
	void collectorCallsHttpOutsideTheJpaTransactionAndPersistsThePageInsideIt() {
		Ids ids = insertCollectingExecution();
		CrossrefCollector collector = new CrossrefCollector(
				(pageUri, cursor, rows) -> {
					assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
					return response(work("10.1000/outside"));
				},
				store,
				duration -> { },
				Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC)
		);

		CrossrefCollector.Result result = collector.collect(new CrossrefCollector.Request(
				ids.executionId(),
				List.of(new CrossrefCollector.Window(ids.windowId(), URI.create("https://api.crossref.org/works"))),
				10_000, 10, 2
		));

		assertThat(result.expectedCount()).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM staging_work WHERE execution_id = ?", ids.executionId())).isEqualTo(1);
	}

	@Test
	void threeWindowsKeepLocalCountsAndGapFreeGlobalSequencesAcrossWindowTwoRestart() {
		MultiIds ids = insertCollectingExecution(3);
		QueueClient interrupted = new QueueClient(
				page(2, "window-1-end", 0),
				page(1_000, "window-2-next", 100),
				retryable(), retryable(), retryable()
		);
		CrossrefCollector firstRun = collector(interrupted);

		assertThatThrownBy(() -> firstRun.collect(request(ids, ids.windowIds(), 10_000)))
				.isInstanceOf(CrossrefCollector.CrossrefRequestException.class);
		assertThat(windowCounts(ids.executionId())).containsExactly(2L, 1_000L, 0L);

		QueueClient restarted = new QueueClient(
				page(1_000, "window-2-next", 100),
				page(3, "window-2-end", 1_100),
				page(2, "window-3-end", 2_000)
		);
		collector(restarted).collect(request(ids, ids.windowIds().subList(1, 3), 10_000));

		assertThat(windowCounts(ids.executionId())).containsExactly(2L, 1_003L, 2L);
		assertThat(jdbc.queryForList(
				"SELECT execution_sequence FROM staging_work WHERE execution_id = ? ORDER BY execution_sequence",
				Long.class, bytes(ids.executionId())
		)).containsExactlyElementsOf(IntStream.rangeClosed(1, 1_007).mapToObj(value -> (long) value).toList());
		assertThat(count("SELECT COUNT(*) FROM staging_work WHERE execution_id = ?", ids.executionId()))
				.isEqualTo(1_007);
	}

	@Test
	void rejectsIncompatibleExecutionStatusAndMaxItemsBeforeHttp() {
		Ids wrongStatus = insertCollectingExecution();
		jdbc.update("UPDATE sync_execution SET business_status = 'PREPARING' WHERE id = ?",
				bytes(wrongStatus.executionId()));
		AtomicInteger calls = new AtomicInteger();
		CrossrefCollector statusCollector = collector((pageUri, cursor, rows) -> {
			calls.incrementAndGet();
			return response(work("10.1000/should-not-fetch"));
		});

		assertThatThrownBy(() -> statusCollector.collect(new CrossrefCollector.Request(
				wrongStatus.executionId(),
				List.of(new CrossrefCollector.Window(wrongStatus.windowId(), URI.create("https://api.crossref.org/works"))),
				100, 10, 2
		))).isInstanceOf(IllegalStateException.class).hasMessageContaining("PREPARING");

		Ids wrongMax = insertCollectingExecution();
		jdbc.update("UPDATE sync_execution SET max_items = 100 WHERE id = ?", bytes(wrongMax.executionId()));
		CrossrefCollector maxCollector = collector((pageUri, cursor, rows) -> {
			calls.incrementAndGet();
			return response(work("10.1000/should-not-fetch"));
		});
		assertThatThrownBy(() -> maxCollector.collect(new CrossrefCollector.Request(
				wrongMax.executionId(),
				List.of(new CrossrefCollector.Window(wrongMax.windowId(), URI.create("https://api.crossref.org/works"))),
				200, 10, 2
		))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxItems");

		assertThat(calls).hasValue(0);
	}

	@Test
	void rejectsMissingReversedAndForeignPendingWindowsBeforeHttp() {
		AtomicInteger calls = new AtomicInteger();
		CrossrefCollector collector = collector((pageUri, cursor, rows) -> {
			calls.incrementAndGet();
			return page(0, "unused", 0);
		});

		MultiIds missing = insertCollectingExecution(3);
		assertThatThrownBy(() -> collector.collect(request(
				missing, missing.windowIds().subList(0, 2), 10_000
		))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pending windows");

		MultiIds reversed = insertCollectingExecution(3);
		assertThatThrownBy(() -> collector.collect(request(
				reversed, reversed.windowIds().reversed(), 10_000
		))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pending windows");

		MultiIds extra = insertCollectingExecution(3);
		Ids foreign = insertCollectingExecution();
		List<UUID> withForeign = new java.util.ArrayList<>(extra.windowIds());
		withForeign.add(foreign.windowId());
		assertThatThrownBy(() -> collector.collect(request(extra, withForeign, 10_000)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pending windows");

		assertThat(calls).hasValue(0);
		assertThat(List.of(missing, reversed, extra)).allSatisfy(ids -> {
			assertThat(windowCounts(ids.executionId())).containsExactly(0L, 0L, 0L);
			assertThat(jdbc.queryForList(
					"SELECT status FROM sync_window WHERE execution_id = ? ORDER BY window_sequence",
					String.class, bytes(ids.executionId())
			)).containsExactly("COLLECTING", "COLLECTING", "COLLECTING");
			assertThat(count("SELECT COUNT(*) FROM staging_work WHERE execution_id = ?", ids.executionId())).isZero();
			assertThat(jdbc.queryForMap(
					"SELECT business_status, expected_count, staging_upper_bound FROM sync_execution WHERE id = ?",
					bytes(ids.executionId())
			)).containsEntry("business_status", "COLLECTING")
					.containsEntry("expected_count", null)
					.containsEntry("staging_upper_bound", null);
		});
	}

	@Test
	void finalTransactionRejectsANewPendingWindowCreatedAfterPreflight() {
		Ids ids = insertCollectingExecution();
		UUID staleWindowId = UUID.randomUUID();
		CrossrefCollector collector = collector((pageUri, cursor, rows) -> {
			jdbc.update("""
					INSERT INTO sync_window (
					  id, execution_id, window_sequence, cursor_value, next_cursor_value,
					  collected_count, status, created_at, updated_at
					) VALUES (?, ?, 1, '*', NULL, 0, 'COLLECTING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
					""", bytes(staleWindowId), bytes(ids.executionId()));
			return response(work("10.1000/stale-window"));
		});

		assertThatThrownBy(() -> collector.collect(new CrossrefCollector.Request(
				ids.executionId(),
				List.of(new CrossrefCollector.Window(ids.windowId(), URI.create("https://api.crossref.org/works"))),
				10_000, 10, 2
		))).isInstanceOf(IllegalStateException.class).hasMessageContaining("pending windows");

		assertThat(count("SELECT COUNT(*) FROM staging_work WHERE execution_id = ?", ids.executionId())).isZero();
		assertThat(windowCounts(ids.executionId())).containsExactly(0L, 0L);
		assertThat(jdbc.queryForMap(
				"SELECT business_status, expected_count, staging_upper_bound FROM sync_execution WHERE id = ?",
				bytes(ids.executionId())
		)).containsEntry("business_status", "COLLECTING")
				.containsEntry("expected_count", null)
				.containsEntry("staging_upper_bound", null);
	}

	@Test
	void mutationRejectsExecutionStatusChangedAfterPreflightBeforeAnyPageWrite() {
		Ids ids = insertCollectingExecution();
		CrossrefCollector collector = collector((pageUri, cursor, rows) -> {
			jdbc.update("UPDATE sync_execution SET business_status = 'COLLECTED' WHERE id = ?",
					bytes(ids.executionId()));
			return page(1_000, "cursor-1", 0, 2_000);
		});

		assertThatThrownBy(() -> collector.collect(new CrossrefCollector.Request(
				ids.executionId(),
				List.of(new CrossrefCollector.Window(ids.windowId(), URI.create("https://api.crossref.org/works"))),
				10_000, 10, 2
		))).isInstanceOf(IllegalStateException.class).hasMessageContaining("COLLECTED");

		assertThat(count("SELECT COUNT(*) FROM staging_work WHERE execution_id = ?", ids.executionId())).isZero();
		assertThat(jdbc.queryForMap(
				"SELECT cursor_value, next_cursor_value, collected_count, status FROM sync_window WHERE id = ?",
				bytes(ids.windowId())
		)).satisfies(window -> {
			assertThat(window).containsEntry("cursor_value", "*")
					.containsEntry("next_cursor_value", null)
					.containsEntry("status", "COLLECTING");
			assertThat(((Number) window.get("collected_count")).longValue()).isZero();
		});
		assertThat(jdbc.queryForMap(
				"SELECT business_status, expected_count, staging_upper_bound FROM sync_execution WHERE id = ?",
				bytes(ids.executionId())
		)).containsEntry("business_status", "COLLECTED")
				.containsEntry("expected_count", null)
				.containsEntry("staging_upper_bound", null);
	}

	@Test
	void committedPagesSurviveTimeoutAndRestartReplaysThenFreezesTheExactExecution() {
		Ids ids = insertCollectingExecution();
		QueueClient interrupted = new QueueClient(
				page(1_000, "cursor-1", 0, 2_500),
				page(1_000, "cursor-2", 1_000, 2_500),
				retryable(), retryable(), retryable()
		);

		assertThatThrownBy(() -> collector(interrupted).collect(new CrossrefCollector.Request(
				ids.executionId(),
				List.of(new CrossrefCollector.Window(ids.windowId(), URI.create("https://api.crossref.org/works"))),
				10_000, 10, 2
		))).isInstanceOf(CrossrefCollector.CrossrefRequestException.class);
		assertThat(count("SELECT COUNT(*) FROM staging_work WHERE execution_id = ?", ids.executionId()))
				.isEqualTo(2_000);
		assertThat(jdbc.queryForMap(
				"SELECT cursor_value, next_cursor_value, collected_count, status FROM sync_window WHERE id = ?",
				bytes(ids.windowId())
		)).satisfies(window -> {
			assertThat(window).containsEntry("cursor_value", "cursor-1")
					.containsEntry("next_cursor_value", "cursor-2")
					.containsEntry("status", "COLLECTING");
			assertThat(((Number) window.get("collected_count")).longValue()).isEqualTo(2_000);
		});

		QueueClient restarted = new QueueClient(
				page(1_000, "cursor-1", 0, 2_500),
				page(1_000, "cursor-2", 1_000, 2_500),
				page(500, "terminal", 2_000, 2_500)
		);
		CrossrefCollector.Result result = collector(restarted).collect(new CrossrefCollector.Request(
				ids.executionId(),
				List.of(new CrossrefCollector.Window(ids.windowId(), URI.create("https://api.crossref.org/works"))),
				10_000, 10, 2
		));

		assertThat(restarted.cursors).startsWith("*");
		assertThat(result.expectedCount()).isEqualTo(2_500);
		assertThat(jdbc.queryForMap("""
				SELECT COUNT(*) row_count, COUNT(DISTINCT execution_sequence) sequence_count,
				       MIN(execution_sequence) first_sequence, MAX(execution_sequence) last_sequence,
				       MAX(staging_key) upper_bound
				FROM staging_work WHERE execution_id = ?
				""", bytes(ids.executionId()))).satisfies(staging -> {
			assertThat(((Number) staging.get("row_count")).longValue()).isEqualTo(2_500);
			assertThat(((Number) staging.get("sequence_count")).longValue()).isEqualTo(2_500);
			assertThat(((Number) staging.get("first_sequence")).longValue()).isEqualTo(1);
			assertThat(((Number) staging.get("last_sequence")).longValue()).isEqualTo(2_500);
			assertThat(((Number) staging.get("upper_bound")).longValue()).isEqualTo(result.stagingUpperBound());
		});
		assertThat(jdbc.queryForList("""
				SELECT doi FROM staging_work WHERE execution_id = ?
				AND execution_sequence IN (1, 1001, 2500) ORDER BY execution_sequence
				""", String.class, bytes(ids.executionId())))
				.containsExactly("10.1000/0", "10.1000/1000", "10.1000/2499");
		assertThat(jdbc.queryForMap(
				"SELECT cursor_value, next_cursor_value, collected_count, status FROM sync_window WHERE id = ?",
				bytes(ids.windowId())
		)).satisfies(window -> {
			assertThat(window).containsEntry("cursor_value", "cursor-2")
					.containsEntry("next_cursor_value", "terminal")
					.containsEntry("status", "COLLECTED");
			assertThat(((Number) window.get("collected_count")).longValue()).isEqualTo(2_500);
		});
		var execution = jdbc.queryForMap("""
				SELECT business_status, expected_count, staging_upper_bound, finished_at
				FROM sync_execution WHERE id = ?
				""", bytes(ids.executionId()));
		assertThat(execution).containsEntry("business_status", "COLLECTED")
				.containsEntry("finished_at", null);
		assertThat(((Number) execution.get("expected_count")).longValue()).isEqualTo(2_500);
		assertThat(((Number) execution.get("staging_upper_bound")).longValue())
				.isEqualTo(result.stagingUpperBound());
	}

	private static void assertPrematureReplayRejected(CrossrefPage replayPage) {
		Ids ids = insertCollectingExecution();
		CrossrefPage seed = page(1_000, "seed-next", 0, 1_000);
		store.persist(new CrossrefCollector.PageWrite(
				ids.executionId(), ids.windowId(), "*", "seed-next", 1, 1_000, 0,
				seed.message().items(), Instant.parse("2026-08-08T00:00:00Z")
		), CrossrefCollector.Completion.PAGE);

		assertThatThrownBy(() -> collector(new QueueClient(replayPage)).collect(new CrossrefCollector.Request(
				ids.executionId(),
				List.of(new CrossrefCollector.Window(ids.windowId(), URI.create("https://api.crossref.org/works"))),
				10_000, 10, 2
		))).isInstanceOf(CrossrefCollector.CollectionSafetyException.class)
				.hasMessageContaining("replay frontier");

		assertCollectingState(ids, 1_000, "*", "seed-next");
	}

	private static void assertCollectingState(Ids ids, long expectedRows, String cursor, String nextCursor) {
		assertThat(count("SELECT COUNT(*) FROM staging_work WHERE execution_id = ?", ids.executionId()))
				.isEqualTo(expectedRows);
		assertThat(jdbc.queryForMap(
				"SELECT cursor_value, next_cursor_value, collected_count, status FROM sync_window WHERE id = ?",
				bytes(ids.windowId())
		)).satisfies(window -> {
			assertThat(window).containsEntry("cursor_value", cursor)
					.containsEntry("next_cursor_value", nextCursor)
					.containsEntry("status", "COLLECTING");
			assertThat(((Number) window.get("collected_count")).longValue()).isEqualTo(expectedRows);
		});
		assertThat(jdbc.queryForMap(
				"SELECT business_status, expected_count, staging_upper_bound FROM sync_execution WHERE id = ?",
				bytes(ids.executionId())
		)).containsEntry("business_status", "COLLECTING")
				.containsEntry("expected_count", null)
				.containsEntry("staging_upper_bound", null);
	}

	private static CrossrefCollector collector(QueueClient client) {
		return collector((CrossrefCollector.CrossrefClient) client);
	}

	private static CrossrefCollector collector(CrossrefCollector.CrossrefClient client) {
		return new CrossrefCollector(
				client, store, duration -> { },
				Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC)
		);
	}

	private static CrossrefCollector.Request request(MultiIds ids, List<UUID> windows, long maxItems) {
		return new CrossrefCollector.Request(
				ids.executionId(),
				windows.stream()
						.map(id -> new CrossrefCollector.Window(id, URI.create("https://api.crossref.org/works")))
						.toList(),
				maxItems, 20, 2
		);
	}

	private static CrossrefPage page(int size, String nextCursor, int doiOffset) {
		return page(size, nextCursor, doiOffset, size);
	}

	private static CrossrefPage page(int size, String nextCursor, int doiOffset, long totalResults) {
		return new CrossrefPage(
				"ok", "work-list", "1.0.0",
				new CrossrefPage.Message(nextCursor, totalResults, size,
						IntStream.range(0, size).mapToObj(index -> work("10.1000/" + (doiOffset + index))).toList())
		);
	}

	private static CrossrefCollector.CrossrefRequestException retryable() {
		return new CrossrefCollector.CrossrefRequestException("timeout", true, null);
	}

	private static List<Long> windowCounts(UUID executionId) {
		return jdbc.queryForList(
				"SELECT collected_count FROM sync_window WHERE execution_id = ? ORDER BY window_sequence",
				Long.class, bytes(executionId)
		);
	}

	private static Ids insertCollectingExecution() {
		MultiIds ids = insertCollectingExecution(1);
		return new Ids(ids.executionId(), ids.windowIds().getFirst());
	}

	private static MultiIds insertCollectingExecution(int windowCount) {
		UUID executionId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO sync_execution (
				  id, request_id, mode, sync_contract_hash, canonical_version, business_status,
				  indexed_from_utc, indexed_until_utc, started_at, created_at, updated_at
				) VALUES (?, ?, 'INCREMENTAL', ?, 1, 'COLLECTING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
				          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", bytes(executionId), executionId.toString(), "a".repeat(64));
		List<UUID> windowIds = IntStream.range(0, windowCount).mapToObj(sequence -> {
			UUID windowId = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO sync_window (
					  id, execution_id, window_sequence, cursor_value, next_cursor_value,
					  collected_count, status, created_at, updated_at
					) VALUES (?, ?, ?, '*', NULL, 0, 'COLLECTING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
					""", bytes(windowId), bytes(executionId), sequence);
			return windowId;
		}).toList();
		return new MultiIds(executionId, windowIds);
	}

	private static long count(String sql, UUID id) {
		return jdbc.queryForObject(sql, Long.class, bytes(id));
	}

	private static CrossrefPage response(CrossrefPage.Work... works) {
		return new CrossrefPage(
				"ok", "work-list", "1.0.0",
				new CrossrefPage.Message("still-present", works.length, works.length, List.of(works))
		);
	}

	private static CrossrefPage.Work work(String doi) {
		return new CrossrefPage.Work(
				doi, List.of("Title"), "Publisher", "journal-article",
				new CrossrefPage.DateParts(List.of(List.of(2026, 8, 8))), "https://doi.org/example",
				List.of(), new CrossrefPage.Timestamp("2026-08-08T00:00:00Z"),
				new CrossrefPage.Timestamp("2026-08-07T00:00:00Z")
		);
	}

	private static byte[] bytes(UUID id) {
		return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
	}

	private record Ids(UUID executionId, UUID windowId) {
	}

	private record MultiIds(UUID executionId, List<UUID> windowIds) {
	}

	private static final class QueueClient implements CrossrefCollector.CrossrefClient {
		private final Queue<Object> outcomes = new ArrayDeque<>();
		private final List<String> cursors = new java.util.ArrayList<>();

		private QueueClient(Object... outcomes) {
			this.outcomes.addAll(List.of(outcomes));
		}

		@Override
		public CrossrefPage fetch(URI pageUri, String cursor, int rows) {
			cursors.add(cursor);
			Object outcome = outcomes.remove();
			if (outcome instanceof RuntimeException exception) {
				throw exception;
			}
			return (CrossrefPage) outcome;
		}
	}
}
