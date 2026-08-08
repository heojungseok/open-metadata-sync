package com.heojungseok.openmetadatasync.batch.sync;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.EntityManager;

import org.hibernate.SessionEventListener;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.heojungseok.openmetadatasync.OpenMetadataSyncApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ChunkAwareJpaWorkWriterTest {

	@Container
	static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10");

	private static final AtomicInteger JOB_SEQUENCE = new AtomicInteger();
	private static final Instant T0 = Instant.parse("2026-08-08T00:00:00.000001Z");
	private static final Instant T1 = Instant.parse("2026-08-08T00:00:01.000001Z");
	private static final Instant T2 = Instant.parse("2026-08-08T00:00:02.000001Z");
	private static final String AUTHORS_A = "[{\"given\":\"First\",\"family\":\"Author\"}]";
	private static final String AUTHORS_B = "[{\"given\":\"Changed\",\"family\":\"Author\"}]";

	private static ConfigurableApplicationContext context;
	private static EntityManager entityManager;
	private static JdbcTemplate jdbc;
	private static JobRepository jobRepository;
	private static TransactionTemplate transaction;
	private static Statistics statistics;

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
				.properties("spring.jpa.properties.hibernate.session.events.auto=" + BatchProbe.class.getName())
				.run(
						"--DB_HOST=" + MYSQL.getHost(),
						"--DB_PORT=" + MYSQL.getMappedPort(3306),
						"--DB_USERNAME=root",
						"--DB_PASSWORD=" + MYSQL.getPassword()
				);
		entityManager = context.getBean(EntityManager.class);
		jdbc = context.getBean(JdbcTemplate.class);
		jobRepository = context.getBean(JobRepository.class);
		transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
		statistics = context.getBean(jakarta.persistence.EntityManagerFactory.class)
				.unwrap(SessionFactory.class).getStatistics();
	}

	@AfterAll
	static void stopApplication() {
		if (context != null) {
			context.close();
		}
	}

	@Test
	void writesAllSixCanonicalOutcomesAndMutatesOnlyAllowedTargetFields() {
		Fixture fixture = fixture();
		SyncWorkDto inserted = staging(fixture, "10.1000/inserted", T1, 1, "Inserted", AUTHORS_A);
		SyncWorkDto superseded = staging(fixture, "10.1000/superseded", T0, 2, "Past", AUTHORS_A);
		SyncWorkDto noOp = staging(fixture, "10.1000/no-op", T1, 3, "Same", AUTHORS_A);
		SyncWorkDto conflict = staging(fixture, "10.1000/conflict", T1, 4, "Incoming", AUTHORS_A);
		SyncWorkDto indexAdvanced = staging(fixture, "10.1000/index-advanced", T2, 6, "Stable", AUTHORS_A);
		SyncWorkDto updated = staging(fixture, "10.1000/updated", T2, 7, "New title", AUTHORS_B);
		insertTarget(superseded, T1, 2, "Current", AUTHORS_A);
		insertTarget(noOp, T1, 3, "Same", AUTHORS_A);
		insertTarget(conflict, T1, 5, "Existing", AUTHORS_A);
		insertTarget(indexAdvanced, T1, 6, "Stable", AUTHORS_A);
		insertTarget(updated, T1, 8, "Old title", AUTHORS_A);
		Instant indexUpdatedAt = targetTimestamp(indexAdvanced.doi(), "updated_at");
		statistics.clear();

		commit(writer(fixture), List.of(inserted, superseded, noOp, conflict, indexAdvanced, updated));

		assertThat(chunkCounts(fixture)).containsEntry("inserted_count", 1L)
				.containsEntry("superseded_count", 1L)
				.containsEntry("no_op_count", 1L)
				.containsEntry("conflict_count", 1L)
				.containsEntry("index_advanced_count", 1L)
				.containsEntry("updated_count", 1L);
		assertThat(count("SELECT COUNT(*) FROM sync_error WHERE execution_id = ? AND status = 'OPEN'", fixture))
				.isEqualTo(1);
		assertThat(target("10.1000/conflict")).containsEntry("title", "Existing");
		assertThat(target("10.1000/superseded")).containsEntry("title", "Current");
		assertThat(target("10.1000/index-advanced")).containsEntry("title", "Stable");
		assertThat(targetTimestamp(indexAdvanced.doi(), "source_indexed_at")).isEqualTo(T2);
		assertThat(targetTimestamp(indexAdvanced.doi(), "updated_at")).isEqualTo(indexUpdatedAt);
		assertThat(target("10.1000/updated")).containsEntry("title", "New title")
				.containsEntry("authors_json", normalizedJson(AUTHORS_B));
		Map<String, Object> updatedHashes = jdbc.queryForMap(
				"SELECT content_hash, author_hash FROM work WHERE doi = '10.1000/updated'"
		);
		assertThat((byte[]) updatedHashes.get("content_hash")).containsExactly(updated.contentHash());
		assertThat((byte[]) updatedHashes.get("author_hash")).containsExactly(updated.authorHash());
		assertThat(targetTimestamp(updated.doi(), "source_indexed_at")).isEqualTo(T2);
		assertThat(target("10.1000/inserted")).containsEntry("title", "Inserted");
		assertThat(statistics.getEntityUpdateCount()).isEqualTo(2);
	}

	@Test
	void groupsNormalizedDoiBeforeLookupAndAccountsLatestWinnerDuplicatesAndConflicts() {
		Fixture fixture = fixture();
		List<SyncWorkDto> items = List.of(
				staging(fixture, " 10.1000/GROUP ", T0, 1, "Old", AUTHORS_A),
				staging(fixture, "10.1000/group", T2, 2, "Winner", AUTHORS_B),
				staging(fixture, "10.1000/GROUP", T2, 2, "Winner", AUTHORS_B),
				staging(fixture, "10.1000/conflicted-group", T0, 3, "Past", AUTHORS_A),
				staging(fixture, "10.1000/conflicted-group", T2, 4, "A", AUTHORS_A),
				staging(fixture, "10.1000/conflicted-group", T2, 5, "B", AUTHORS_B)
		);
		statistics.clear();

		commit(writer(fixture), items);

		assertThat(chunkCounts(fixture)).containsEntry("inserted_count", 1L)
				.containsEntry("superseded_count", 1L)
				.containsEntry("no_op_count", 1L)
				.containsEntry("conflict_count", 3L);
		assertThat(target("10.1000/group")).containsEntry("title", "Winner");
		assertThat(count("SELECT COUNT(*) FROM work WHERE doi = '10.1000/conflicted-group'")).isZero();
		assertThat(count("SELECT COUNT(*) FROM sync_error WHERE execution_id = ?", fixture)).isEqualTo(3);
		assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
		assertThat(statistics.getEntityFetchCount()).isZero();
	}

	@Test
	void noOpPerformsNoTargetDmlAndOneBulkLookupForTheChunk() {
		Fixture fixture = fixture();
		SyncWorkDto item = staging(fixture, "10.1000/no-target-dml", T1, 9, "Same", AUTHORS_A);
		insertTarget(item, T1, 9, "Same", AUTHORS_A);
		Instant updatedAt = targetTimestamp(item.doi(), "updated_at");
		statistics.clear();

		commit(writer(fixture), List.of(item));

		assertThat(statistics.getEntityUpdateCount()).isZero();
		assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
		assertThat(targetTimestamp(item.doi(), "updated_at")).isEqualTo(updatedAt);
		assertThat(chunkCounts(fixture)).containsEntry("no_op_count", 1L);
	}

	@Test
	void assignedTargetIdsUseAnActualJdbcBatchForAFullInsertChunk() {
		Fixture fixture = fixture();
		List<SyncWorkDto> items = new ArrayList<>();
		for (int index = 1; index <= 25; index++) {
			items.add(staging(
					fixture, "10.1000/batch-" + index, T1.plusNanos(index), index,
					"Batch " + index, AUTHORS_A
			));
		}
		statistics.clear();
		BatchProbe.JDBC_BATCHES.set(0);
		BatchProbe.JDBC_STATEMENTS.set(0);

		commit(writer(fixture), items);

		assertThat(count("SELECT COUNT(*) FROM work WHERE doi LIKE '10.1000/batch-%'")).isEqualTo(25);
		assertThat(statistics.getEntityInsertCount()).isEqualTo(26);
		assertThat(statistics.getFlushCount()).isEqualTo(1);
		assertThat(BatchProbe.JDBC_BATCHES).hasPositiveValue();
		assertThat(BatchProbe.JDBC_STATEMENTS.get()).isLessThan(25);
	}

	@Test
	void failureBeforeFlushRollsBackTheWholeChunk() {
		Fixture fixture = fixture();
		SyncWorkDto item = staging(fixture, "10.1000/before-flush", T1, 1, "Pending", AUTHORS_A);
		SyncWorkDto conflict = staging(fixture, "10.1000/before-flush-conflict", T1, 2, "Incoming", AUTHORS_A);
		insertTarget(conflict, T1, 3, "Existing", AUTHORS_A);
		ChunkAwareJpaWorkWriter writer = new ChunkAwareJpaWorkWriter(
				entityManager, fixture.executionId(), fixture.stepExecution().getId(),
				() -> { throw new IllegalStateException("before flush"); }
		);

		assertThatThrownBy(() -> commit(writer, List.of(item, conflict)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("before flush");

		assertThat(count("SELECT COUNT(*) FROM work WHERE doi = '10.1000/before-flush'")).isZero();
		assertThat(count("SELECT COUNT(*) FROM sync_error WHERE execution_id = ?", fixture)).isZero();
		assertThat(count("SELECT COUNT(*) FROM sync_chunk_result WHERE execution_id = ?", fixture)).isZero();
	}

	@Test
	void flushConstraintFailureRollsBackJdbcFixtureAndJpaRowsTogether() {
		Fixture fixture = fixture();
		SyncWorkDto item = staging(fixture, "10.1000/flush-failure", T1, 1, "Pending", AUTHORS_A);
		ChunkAwareJpaWorkWriter writer = new ChunkAwareJpaWorkWriter(
				entityManager, fixture.executionId(), fixture.stepExecution().getId(),
				() -> insertTarget(item, T1, 2, "Concurrent", AUTHORS_B)
		);

		assertThatThrownBy(() -> commit(writer, List.of(item))).isInstanceOf(RuntimeException.class);

		assertThat(count("SELECT COUNT(*) FROM work WHERE doi = '10.1000/flush-failure'")).isZero();
		assertThat(count("SELECT COUNT(*) FROM sync_chunk_result WHERE execution_id = ?", fixture)).isZero();
	}

	@Test
	void checkpointTransactionFailureRollsBackTargetChunkResultAndExecutionContext() {
		Fixture fixture = fixture();
		SyncWorkDto item = staging(fixture, "10.1000/checkpoint", T1, 1, "Pending", AUTHORS_A);
		SyncWorkDto conflict = staging(fixture, "10.1000/checkpoint-conflict", T1, 2, "Incoming", AUTHORS_A);
		insertTarget(conflict, T1, 3, "Existing", AUTHORS_A);
		StepExecution step = fixture.stepExecution();
		step.getExecutionContext().putLong("checkpoint-probe", 1);
		jobRepository.updateExecutionContext(step);

		transaction.executeWithoutResult(status -> {
			write(writer(fixture), List.of(item, conflict));
			step.getExecutionContext().putLong("checkpoint-probe", 2);
			jobRepository.updateExecutionContext(step);
			status.setRollbackOnly();
		});

		StepExecution durable = jobRepository.getStepExecution(step.getId());
		assertThat(durable.getExecutionContext().getLong("checkpoint-probe")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM work WHERE doi = '10.1000/checkpoint'")).isZero();
		assertThat(count("SELECT COUNT(*) FROM sync_error WHERE execution_id = ?", fixture)).isZero();
		assertThat(count("SELECT COUNT(*) FROM sync_chunk_result WHERE execution_id = ?", fixture)).isZero();
	}

	@Test
	void restartUsesNextDurableChunkSequenceAndNewStepAfterRolledBackThirdChunk() {
		Fixture fixture = fixture();
		SyncWorkDto first = staging(fixture, "10.1000/restart-1", T1, 1, "First", AUTHORS_A);
		SyncWorkDto second = staging(fixture, "10.1000/restart-2", T1, 2, "Second", AUTHORS_A);
		SyncWorkDto third = staging(fixture, "10.1000/restart-3", T1, 3, "Third", AUTHORS_A);
		StepExecution firstStep = fixture.stepExecution();
		ChunkAwareJpaWorkWriter firstWriter = writer(fixture);

		commitWithCheckpoint(firstWriter, firstStep, first);
		commitWithCheckpoint(firstWriter, firstStep, second);
		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			write(firstWriter, List.of(third));
			firstStep.getExecutionContext().putLong("restart-checkpoint", third.stagingKey());
			jobRepository.updateExecutionContext(firstStep);
			throw new IllegalStateException("checkpoint transaction failure");
		})).isInstanceOf(IllegalStateException.class)
				.hasMessage("checkpoint transaction failure");

		StepExecution durableFailedStep = jobRepository.getStepExecution(firstStep.getId());
		assertThat(durableFailedStep.getExecutionContext().getLong("restart-checkpoint"))
				.isEqualTo(second.stagingKey());
		assertThat(jdbc.queryForList("""
				SELECT chunk_sequence FROM sync_chunk_result
				WHERE execution_id = ? ORDER BY chunk_sequence
				""", Long.class, bytes(fixture.executionId()))).containsExactly(1L, 2L);
		assertThat(count("SELECT COUNT(*) FROM work WHERE doi = '10.1000/restart-3'")).isZero();
		assertThat(jdbc.queryForList("""
				SELECT doi FROM work WHERE doi LIKE '10.1000/restart-%' ORDER BY doi
				""", String.class)).containsExactly("10.1000/restart-1", "10.1000/restart-2");

		StepExecution restartStep = createStepExecution();
		restartStep.setExecutionContext(new ExecutionContext(durableFailedStep.getExecutionContext()));
		jobRepository.updateExecutionContext(restartStep);
		assertThat(restartStep.getId()).isNotEqualTo(firstStep.getId());
		assertThat(restartStep.getCommitCount()).isZero();
		ChunkAwareJpaWorkWriter restartedWriter = new ChunkAwareJpaWorkWriter(
				entityManager, fixture.executionId(), restartStep.getId()
		);

		commitWithCheckpoint(restartedWriter, restartStep, third);

		assertThat(jdbc.queryForList("""
				SELECT chunk_sequence FROM sync_chunk_result
				WHERE execution_id = ? ORDER BY chunk_sequence
				""", Long.class, bytes(fixture.executionId()))).containsExactly(1L, 2L, 3L);
		assertThat(jdbc.queryForList("""
				SELECT step_execution_id FROM sync_chunk_result
				WHERE execution_id = ? ORDER BY chunk_sequence
				""", Long.class, bytes(fixture.executionId())))
				.containsExactly(firstStep.getId(), firstStep.getId(), restartStep.getId());
		assertThat(jdbc.queryForList("""
				SELECT first_staging_key FROM sync_chunk_result
				WHERE execution_id = ? ORDER BY chunk_sequence
				""", Long.class, bytes(fixture.executionId())))
				.containsExactly(first.stagingKey(), second.stagingKey(), third.stagingKey());
		assertThat(jdbc.queryForList("""
				SELECT last_staging_key FROM sync_chunk_result
				WHERE execution_id = ? ORDER BY chunk_sequence
				""", Long.class, bytes(fixture.executionId())))
				.containsExactly(first.stagingKey(), second.stagingKey(), third.stagingKey());
		assertThat(jdbc.queryForList("""
				SELECT doi FROM work WHERE doi LIKE '10.1000/restart-%' ORDER BY doi
				""", String.class)).containsExactly(
					"10.1000/restart-1", "10.1000/restart-2", "10.1000/restart-3"
				).doesNotHaveDuplicates();
		StepExecution durableRestart = jobRepository.getStepExecution(restartStep.getId());
		assertThat(durableRestart.getExecutionContext().getLong("restart-checkpoint"))
				.isEqualTo(third.stagingKey());
	}

	private static ChunkAwareJpaWorkWriter writer(Fixture fixture) {
		return new ChunkAwareJpaWorkWriter(
				entityManager, fixture.executionId(), fixture.stepExecution().getId()
		);
	}

	private static void commit(ChunkAwareJpaWorkWriter writer, List<SyncWorkDto> items) {
		transaction.executeWithoutResult(status -> write(writer, items));
	}

	private static void commitWithCheckpoint(
			ChunkAwareJpaWorkWriter writer,
			StepExecution stepExecution,
			SyncWorkDto item
	) {
		transaction.executeWithoutResult(status -> {
			write(writer, List.of(item));
			stepExecution.getExecutionContext().putLong("restart-checkpoint", item.stagingKey());
			jobRepository.updateExecutionContext(stepExecution);
		});
	}

	private static void write(ChunkAwareJpaWorkWriter writer, List<SyncWorkDto> items) {
		writer.write(new Chunk<>(items));
	}

	private static Fixture fixture() {
		UUID executionId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO sync_execution (
				  id, request_id, mode, sync_contract_hash, canonical_version, business_status,
				  started_at, created_at, updated_at
				) VALUES (?, ?, 'FULL', ?, 1, 'SYNCING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", bytes(executionId), executionId.toString(), "a".repeat(64));
		return new Fixture(executionId, createStepExecution(), new AtomicInteger());
	}

	private static StepExecution createStepExecution() {
		String name = "writer-" + JOB_SEQUENCE.incrementAndGet();
		JobParameters parameters = new JobParameters();
		JobInstance instance = jobRepository.createJobInstance(name, parameters);
		JobExecution execution = jobRepository.createJobExecution(instance, parameters, new ExecutionContext());
		return jobRepository.createStepExecution("sync", execution);
	}

	private static SyncWorkDto staging(
			Fixture fixture,
			String doi,
			Instant indexedAt,
			int hashSeed,
			String title,
			String authorsJson
	) {
		int sequence = fixture.sequence().incrementAndGet();
		byte[] contentHash = hash(hashSeed);
		byte[] authorHash = hash(hashSeed + 50);
		jdbc.update("""
				INSERT INTO staging_work (
				  execution_id, execution_sequence, source_json, doi, title, publisher, work_type,
				  issued_date, issued_date_precision, url, authors_json, canonical_version,
				  content_hash, author_hash, indexed_at, collected_at
				) VALUES (?, ?, JSON_OBJECT('ignored', ?), ?, ?, 'Publisher', 'journal-article',
				          '2026-08-08', 3, ?, CAST(? AS JSON), 1, ?, ?, ?, UTC_TIMESTAMP(6))
				""",
				bytes(fixture.executionId()), sequence, sequence, doi, title, "https://doi.org/" + doi.trim(),
				authorsJson, contentHash, authorHash, utc(indexedAt));
		long stagingKey = jdbc.queryForObject(
				"SELECT staging_key FROM staging_work WHERE execution_id = ? AND execution_sequence = ?",
				Long.class, bytes(fixture.executionId()), sequence);
		return new SyncWorkDto(
				stagingKey, doi, title, "Publisher", "journal-article", "2026-08-08", (byte) 3,
				"https://doi.org/" + doi.trim(), authorsJson, 1, contentHash, authorHash, indexedAt
		);
	}

	private static void insertTarget(
			SyncWorkDto work,
			Instant indexedAt,
			int hashSeed,
			String title,
			String authorsJson
	) {
		jdbc.update("""
				INSERT INTO work (
				  id, doi, title, publisher, work_type, issued_date, issued_date_precision, url,
				  authors_json, canonical_version, content_hash, author_hash, source_indexed_at,
				  created_at, updated_at
				) VALUES (?, ?, ?, 'Publisher', 'journal-article', '2026-08-08', 3, ?,
				          CAST(? AS JSON), 1, ?, ?, ?, '2026-01-01 00:00:00', '2026-01-01 00:00:00')
				""", bytes(UUID.randomUUID()), work.doi().trim().toLowerCase(), title, work.url(), authorsJson,
				hash(hashSeed), hash(hashSeed + 50), utc(indexedAt));
	}

	private static Map<String, Long> chunkCounts(Fixture fixture) {
		Map<String, Object> raw = jdbc.queryForMap("""
				SELECT inserted_count, superseded_count, no_op_count, conflict_count,
				       index_advanced_count, updated_count
				FROM sync_chunk_result WHERE execution_id = ?
				""", bytes(fixture.executionId()));
		Map<String, Long> counts = new java.util.LinkedHashMap<>();
		raw.forEach((name, value) -> counts.put(name, ((Number) value).longValue()));
		return counts;
	}

	private static Map<String, Object> target(String doi) {
		return jdbc.queryForMap("SELECT title, CAST(authors_json AS CHAR) authors_json FROM work WHERE doi = ?", doi);
	}

	private static Instant targetTimestamp(String doi, String column) {
		LocalDateTime value = jdbc.queryForObject("SELECT " + column + " FROM work WHERE doi = ?", LocalDateTime.class, doi);
		return value.toInstant(ZoneOffset.UTC);
	}

	private static String normalizedJson(String json) {
		return json.replace(":", ": ").replace(",", ", ");
	}

	private static long count(String sql, Fixture fixture) {
		return jdbc.queryForObject(sql, Long.class, bytes(fixture.executionId()));
	}

	private static long count(String sql) {
		return jdbc.queryForObject(sql, Long.class);
	}

	private static byte[] hash(int value) {
		byte[] hash = new byte[32];
		Arrays.fill(hash, (byte) value);
		return hash;
	}

	private static LocalDateTime utc(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static byte[] bytes(UUID id) {
		return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
	}

	private record Fixture(UUID executionId, StepExecution stepExecution, AtomicInteger sequence) {
	}

	public static final class BatchProbe implements SessionEventListener {
		static final AtomicInteger JDBC_BATCHES = new AtomicInteger();
		static final AtomicInteger JDBC_STATEMENTS = new AtomicInteger();

		@Override
		public void jdbcExecuteBatchEnd() {
			JDBC_BATCHES.incrementAndGet();
		}

		@Override
		public void jdbcExecuteStatementEnd() {
			JDBC_STATEMENTS.incrementAndGet();
		}
	}
}
