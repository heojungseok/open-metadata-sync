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
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
	}

	@AfterAll
	static void stopApplication() {
		if (context != null) {
			context.close();
		}
	}

	@Test
	void pageFailureRollsBackRowsAndWindowProgress() {
		Ids ids = insertCollectingExecution();
		CrossrefCollector.PageWrite page = new CrossrefCollector.PageWrite(
				ids.executionId(), ids.windowId(), "*", "cursor-1", 1,
				List.of(work("10.1000/valid"), work(null)), Instant.parse("2026-08-08T00:00:00Z")
		);

		assertThatThrownBy(() -> store.persist(page))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("DOI is required");

		assertThat(count("SELECT COUNT(*) FROM staging_work WHERE execution_id = ?", ids.executionId())).isZero();
		assertThat(count("SELECT collected_count FROM sync_window WHERE id = ?", ids.windowId())).isZero();
	}

	@Test
	void duplicatePageIsIdempotentAndCompletionFreezesTypedProjectionAndUpperBound() {
		Ids ids = insertCollectingExecution();
		CrossrefCollector.PageWrite page = new CrossrefCollector.PageWrite(
				ids.executionId(), ids.windowId(), "*", "cursor-1", 1,
				List.of(work("10.1000/one"), work("10.1000/two")), Instant.parse("2026-08-08T00:00:00Z")
		);

		store.persist(page);
		store.persist(page);
		store.completeWindow(ids.windowId());
		CrossrefCollector.Frozen frozen = store.freeze(ids.executionId());

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

	private static Ids insertCollectingExecution() {
		UUID executionId = UUID.randomUUID();
		UUID windowId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO sync_execution (
				  id, request_id, mode, sync_contract_hash, canonical_version, business_status,
				  indexed_from_utc, indexed_until_utc, started_at, created_at, updated_at
				) VALUES (?, ?, 'INCREMENTAL', ?, 1, 'COLLECTING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
				          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", bytes(executionId), executionId.toString(), "a".repeat(64));
		jdbc.update("""
				INSERT INTO sync_window (
				  id, execution_id, window_sequence, cursor_value, next_cursor_value,
				  collected_count, status, created_at, updated_at
				) VALUES (?, ?, 0, '*', NULL, 0, 'COLLECTING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", bytes(windowId), bytes(executionId));
		return new Ids(executionId, windowId);
	}

	private static long count(String sql, UUID id) {
		return jdbc.queryForObject(sql, Long.class, bytes(id));
	}

	private static CrossrefCollector.Response response(CrossrefPage.Work... works) {
		return new CrossrefCollector.Response(new CrossrefPage(
				"ok", "work-list", "1.0.0",
				new CrossrefPage.Message("still-present", works.length, works.length, List.of(works))
		));
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
}
