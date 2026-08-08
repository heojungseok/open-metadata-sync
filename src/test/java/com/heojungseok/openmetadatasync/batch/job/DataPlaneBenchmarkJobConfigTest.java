package com.heojungseok.openmetadatasync.batch.job;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.heojungseok.openmetadatasync.OpenMetadataSyncApplication;
import com.heojungseok.openmetadatasync.batch.benchmark.BenchmarkMetrics;
import com.heojungseok.openmetadatasync.batch.parameter.SyncContract;
import com.heojungseok.openmetadatasync.batch.parameter.Tuning;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DataPlaneBenchmarkJobConfigTest {

	@Container
	static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10");

	private static ConfigurableApplicationContext context;
	private static JdbcTemplate jdbc;
	private static JobOperator operator;
	private static Job benchmarkJob;
	private static Job crossrefJob;
	private static HttpServer crossref;
	private static final AtomicInteger CROSSREF_REQUESTS = new AtomicInteger();

	@TempDir
	Path evidenceDirectory;

	@BeforeAll
	static void startApplication() throws SQLException, IOException {
		try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE open_metadata CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
		}
		crossref = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		crossref.createContext("/works", exchange -> {
			CROSSREF_REQUESTS.incrementAndGet();
			byte[] response = """
					{\"status\":\"ok\",\"message\":{\"total-results\":0,\"items-per-page\":0,\"items\":[],\"next-cursor\":null}}
					""".strip().getBytes();
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		crossref.start();
		context = new SpringApplicationBuilder(OpenMetadataSyncApplication.class)
				.profiles("actual")
				.properties("spring.main.banner-mode=off")
				.properties("spring.jpa.properties.hibernate.generate_statistics=true")
				.properties("crossref.base-uri=http://127.0.0.1:" + crossref.getAddress().getPort() + "/works")
				.run(
						"--DB_HOST=" + MYSQL.getHost(),
						"--DB_PORT=" + MYSQL.getMappedPort(3306),
						"--DB_USERNAME=root",
						"--DB_PASSWORD=" + MYSQL.getPassword()
				);
		jdbc = context.getBean(JdbcTemplate.class);
		operator = context.getBean(JobOperator.class);
		benchmarkJob = context.getBean("dataPlaneBenchmarkJob", Job.class);
		crossrefJob = context.getBean("crossrefSyncJob", Job.class);
	}

	@AfterAll
	static void stopApplication() {
		if (context != null) {
			context.close();
		}
		if (crossref != null) {
			crossref.stop(0);
		}
	}

	@Test
	void backfillAndIncrementalResolveTheirFrozenRangesAndRunCollectSyncVerify() throws Exception {
		int requestsBefore = CROSSREF_REQUESTS.get();
		String backfillRequest = UUID.randomUUID().toString();
		JobExecution backfill = operator.start(crossrefJob, new JobParametersBuilder()
				.addString("requestId", backfillRequest, true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "BACKFILL", true)
				.addLocalDate("createdFrom", java.time.LocalDate.parse("2026-08-01"), true)
				.addLocalDate("createdUntil", java.time.LocalDate.parse("2026-08-02"), true)
				.addLong("maxItems", 10L, true)
				.addLong("chunkSize", 5L, false)
				.toJobParameters());

		assertThat(backfill.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(executionStatus(backfillRequest)).isEqualTo("COMPLETED");
		assertThat(stepNames(backfill)).containsExactlyInAnyOrder(
				"prepareCrossrefExecution", "collect", "beginSync", "sync", "beginVerify", "verify"
		);
		UUID backfillExecution = executionIdForRequest(backfillRequest);
		LocalDateTime watermark = LocalDateTime.of(2026, 8, 2, 0, 0);
		jdbc.update("""
				INSERT INTO sync_watermark (source_name, indexed_until_utc, execution_id, updated_at)
				VALUES ('crossref', ?, ?, UTC_TIMESTAMP(6))
				""", watermark, bytes(backfillExecution));
		String incrementalRequest = UUID.randomUUID().toString();
		JobExecution incremental = operator.start(crossrefJob, new JobParametersBuilder()
				.addString("requestId", incrementalRequest, true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "INCREMENTAL", true)
				.addString("sourceName", "crossref", true)
				.addLong("chunkSize", 5L, false)
				.toJobParameters());

		assertThat(incremental.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(executionStatus(incrementalRequest)).isEqualTo("COMPLETED");
		assertThat(jdbc.queryForObject(
				"SELECT indexed_from_utc FROM sync_execution WHERE request_id = ?",
				LocalDateTime.class, incrementalRequest
		)).isEqualTo(watermark);
		long collectedWindows = jdbc.queryForObject("""
				SELECT COUNT(*) FROM sync_window window_row
				JOIN sync_execution execution_row ON execution_row.id = window_row.execution_id
				WHERE execution_row.request_id IN (?, ?)
				""", Long.class, backfillRequest, incrementalRequest);
		assertThat(CROSSREF_REQUESTS.get() - requestsBefore).isEqualTo(collectedWindows);
	}

	@Test
	void incrementalWithoutWatermarkOrBootstrapFailsBeforeHttp() throws Exception {
		jdbc.update("DELETE FROM sync_watermark");
		int before = CROSSREF_REQUESTS.get();
		String requestId = UUID.randomUUID().toString();

		JobExecution result = operator.start(crossrefJob, new JobParametersBuilder()
				.addString("requestId", requestId, true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "INCREMENTAL", true)
				.addLong("chunkSize", 5L, false)
				.toJobParameters());

		assertThat(result.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(CROSSREF_REQUESTS).hasValue(before);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM sync_execution WHERE request_id = ?", Long.class, requestId
		)).isZero();
	}

	@Test
	void replayCopiesTheImmutableSnapshotAndNeverCallsCrossref() throws Exception {
		UUID sourceExecution = sourceErrorFixture();
		int before = CROSSREF_REQUESTS.get();
		String requestId = UUID.randomUUID().toString();

		JobExecution replay = operator.start(crossrefJob, new JobParametersBuilder()
				.addString("requestId", requestId, true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "REPLAY_ERRORS", true)
				.addString("sourceExecutionId", sourceExecution.toString(), true)
				.addLong("chunkSize", 5L, false)
				.toJobParameters());

		assertThat(replay.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(executionStatus(requestId)).isEqualTo("COMPLETED");
		assertThat(CROSSREF_REQUESTS).hasValue(before);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM work WHERE doi = '10.5555/replay-job'", Long.class
		)).isEqualTo(1);
		assertThat(stepNames(replay)).containsExactlyInAnyOrder(
				"prepareCrossrefExecution", "replayPrepare", "beginSync", "sync", "beginVerify", "verify"
		);
	}

	@Test
	void verifierConflictMakesTheBatchJobFailedAndKeepsTheTargetUnchanged() throws Exception {
		UUID sourceExecution = sourceConflictFixture();
		int before = CROSSREF_REQUESTS.get();
		String requestId = UUID.randomUUID().toString();

		JobExecution replay = operator.start(crossrefJob, new JobParametersBuilder()
				.addString("requestId", requestId, true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "REPLAY_ERRORS", true)
				.addString("sourceExecutionId", sourceExecution.toString(), true)
				.addLong("chunkSize", 5L, false)
				.toJobParameters());

		assertThat(replay.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(executionStatus(requestId)).isEqualTo("FAILED");
		assertThat(CROSSREF_REQUESTS).hasValue(before);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM sync_error WHERE execution_id = (SELECT id FROM sync_execution WHERE request_id = ?)",
				Long.class, requestId
		)).isEqualTo(1);
	}

	@Test
	void smallInitialFailureRestartAndNoOpUseTheProductionReaderWriterVerifierAndEvidence() throws Exception {
		UUID initialExecution = UUID.randomUUID();
		JobParameters initial = parameters(initialExecution, "initial", true);

		JobExecution failed = operator.start(benchmarkJob, initial);

		assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(benchmarkTargetCount()).isZero();
		assertThat(chunkCount(initialExecution)).isZero();
		assertThat(status(initialExecution)).isEqualTo("SYNCING");
		BenchmarkMetrics.finish(initialExecution);

		JobExecution restarted = operator.start(benchmarkJob, initial);

		assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(status(initialExecution)).isEqualTo("COMPLETED");
		assertThat(stagingCount(initialExecution)).isEqualTo(12);
		assertThat(benchmarkTargetCount()).isEqualTo(12);
		assertThat(outcome(initialExecution, "inserted_count")).isEqualTo(12);
		assertThat(chunkCount(initialExecution)).isEqualTo(3);
		String initialEvidence = Files.readString(evidenceDirectory.resolve("benchmark-initial.json"));
		assertThat(initialEvidence)
				.contains("\"restart\"", "\"passed\" : true", "\"inserted\" : 12");
		assertThat(new ObjectMapper().readTree(initialEvidence)
				.get("persistence").get("jdbcBatches").asLong()).isPositive();

		LocalDateTime updatedAt = jdbc.queryForObject(
				"SELECT MAX(updated_at) FROM work WHERE doi LIKE '10.5555/benchmark-v1-42-%'",
				LocalDateTime.class
		);
		UUID noOpExecution = UUID.randomUUID();
		JobExecution noOp = operator.start(benchmarkJob, parameters(noOpExecution, "no-op", false));

		assertThat(noOp.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(status(noOpExecution)).isEqualTo("COMPLETED");
		assertThat(benchmarkTargetCount()).isEqualTo(12);
		assertThat(outcome(noOpExecution, "no_op_count")).isEqualTo(12);
		assertThat(jdbc.queryForObject(
				"SELECT MAX(updated_at) FROM work WHERE doi LIKE '10.5555/benchmark-v1-42-%'",
				LocalDateTime.class
		)).isEqualTo(updatedAt);
		assertThat(Files.readString(evidenceDirectory.resolve("benchmark-no-op.json")))
				.contains("\"scenario\" : \"no-op\"", "\"targetUpdates\" : 0");
	}

	@Test
	void benchmarkRejectsAnIncompatibleModeContractOrScenarioBeforePreload() throws Exception {
		for (String[] invalid : java.util.List.of(
				new String[] {"BACKFILL", SyncContract.hash(), "initial"},
				new String[] {"BENCHMARK", "0".repeat(64), "initial"},
				new String[] {"BENCHMARK", SyncContract.hash(), "unknown"}
		)) {
			String requestId = UUID.randomUUID().toString();
			JobExecution result = operator.start(benchmarkJob, new JobParametersBuilder()
					.addString("requestId", requestId, true)
					.addString("syncContractHash", invalid[1], true)
					.addString("mode", invalid[0], true)
					.addLong("rowCount", 1L, true)
					.addLong("seed", 42L, true)
					.addString("generatorVersion", "v1", true)
					.addString("scenario", invalid[2], true)
					.addLong("chunkSize", 1L, false)
					.addLong("hibernateBatchSize", 1L, false)
					.addString("evidenceDirectory", evidenceDirectory.toString(), false)
					.toJobParameters());

			assertThat(result.getStatus()).isEqualTo(BatchStatus.FAILED);
			assertThat(jdbc.queryForObject(
					"SELECT COUNT(*) FROM sync_execution WHERE request_id = ?", Long.class, requestId
			)).isZero();
		}
	}

	private JobParameters parameters(UUID executionId, String scenario, boolean failFirstExecution) {
		return DataPlaneBenchmarkJobConfig.parameters(
				executionId.toString(), 12, 42, "v1", scenario,
				new Tuning(5, 5), evidenceDirectory, failFirstExecution
		);
	}

	private static long benchmarkTargetCount() {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM work WHERE doi LIKE '10.5555/benchmark-v1-42-%'", Long.class
		);
	}

	private static long stagingCount(UUID executionId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM staging_work WHERE execution_id = ?", Long.class, bytes(executionId)
		);
	}

	private static long chunkCount(UUID executionId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM sync_chunk_result WHERE execution_id = ?", Long.class, bytes(executionId)
		);
	}

	private static long outcome(UUID executionId, String column) {
		return jdbc.queryForObject(
				"SELECT COALESCE(SUM(" + column + "), 0) FROM sync_chunk_result WHERE execution_id = ?",
				Long.class, bytes(executionId)
		);
	}

	private static String status(UUID executionId) {
		return jdbc.queryForObject(
				"SELECT business_status FROM sync_execution WHERE id = ?", String.class, bytes(executionId)
		);
	}

	private static String executionStatus(String requestId) {
		return jdbc.queryForObject(
				"SELECT business_status FROM sync_execution WHERE request_id = ?", String.class, requestId
		);
	}

	private static UUID executionIdForRequest(String requestId) {
		byte[] value = jdbc.queryForObject("SELECT id FROM sync_execution WHERE request_id = ?", byte[].class, requestId);
		ByteBuffer buffer = ByteBuffer.wrap(value);
		return new UUID(buffer.getLong(), buffer.getLong());
	}

	private static java.util.List<String> stepNames(JobExecution execution) {
		return execution.getStepExecutions().stream().map(step -> step.getStepName()).toList();
	}

	private static UUID sourceErrorFixture() {
		UUID executionId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO sync_execution (
				  id, request_id, mode, sync_contract_hash, canonical_version, business_status,
				  started_at, created_at, updated_at
				) VALUES (?, ?, 'INCREMENTAL', ?, 1, 'FAILED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", bytes(executionId), executionId.toString(), SyncContract.hash());
		byte[] hash = new byte[32];
		jdbc.update("""
				INSERT INTO staging_work (
				  execution_id, execution_sequence, source_json, doi, title, authors_json,
				  canonical_version, content_hash, author_hash, indexed_at, collected_at
				) VALUES (?, 1, JSON_OBJECT('DOI', '10.5555/replay-job'), '10.5555/replay-job',
				          'Replay job', JSON_ARRAY(), 1, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", bytes(executionId), hash, hash);
		long stagingKey = jdbc.queryForObject(
				"SELECT staging_key FROM staging_work WHERE execution_id = ?", Long.class, bytes(executionId)
		);
		jdbc.update("""
				UPDATE sync_execution SET expected_count = 1, staging_upper_bound = ? WHERE id = ?
				""", stagingKey, bytes(executionId));
		jdbc.update("""
				INSERT INTO sync_error (
				  execution_id, staging_key, error_type, error_code, message, status, replay_count, created_at
				) VALUES (?, ?, 'VALIDATION', 'FIXED', 'fixed', 'OPEN', 0, UTC_TIMESTAMP(6))
				""", bytes(executionId), stagingKey);
		return executionId;
	}

	private static UUID sourceConflictFixture() {
		UUID executionId = UUID.randomUUID();
		String doi = "10.5555/replay-conflict-" + executionId;
		byte[] incomingHash = new byte[32];
		java.util.Arrays.fill(incomingHash, (byte) 1);
		byte[] targetHash = new byte[32];
		java.util.Arrays.fill(targetHash, (byte) 2);
		LocalDateTime indexedAt = LocalDateTime.of(2026, 8, 8, 0, 0);
		jdbc.update("""
				INSERT INTO sync_execution (
				  id, request_id, mode, sync_contract_hash, canonical_version, business_status,
				  started_at, created_at, updated_at
				) VALUES (?, ?, 'INCREMENTAL', ?, 1, 'FAILED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", bytes(executionId), executionId.toString(), SyncContract.hash());
		jdbc.update("""
				INSERT INTO staging_work (
				  execution_id, execution_sequence, source_json, doi, title, authors_json,
				  canonical_version, content_hash, author_hash, indexed_at, collected_at
				) VALUES (?, 1, JSON_OBJECT('DOI', ?), ?, 'Incoming', JSON_ARRAY(), 1, ?, ?, ?, UTC_TIMESTAMP(6))
				""", bytes(executionId), doi, doi, incomingHash, incomingHash, indexedAt);
		long stagingKey = jdbc.queryForObject(
				"SELECT staging_key FROM staging_work WHERE execution_id = ?", Long.class, bytes(executionId)
		);
		jdbc.update("UPDATE sync_execution SET expected_count = 1, staging_upper_bound = ? WHERE id = ?",
				stagingKey, bytes(executionId));
		jdbc.update("""
				INSERT INTO sync_error (
				  execution_id, staging_key, error_type, error_code, message, status, replay_count, created_at
				) VALUES (?, ?, 'VALIDATION', 'FIXED_CONFLICT', 'fixed', 'OPEN', 0, UTC_TIMESTAMP(6))
				""", bytes(executionId), stagingKey);
		jdbc.update("""
				INSERT INTO work (
				  id, doi, title, authors_json, canonical_version, content_hash, author_hash,
				  source_indexed_at, created_at, updated_at
				) VALUES (?, ?, 'Existing', JSON_ARRAY(), 1, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", bytes(UUID.randomUUID()), doi, targetHash, targetHash, indexedAt);
		return executionId;
	}

	private static byte[] bytes(UUID id) {
		return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
	}
}
