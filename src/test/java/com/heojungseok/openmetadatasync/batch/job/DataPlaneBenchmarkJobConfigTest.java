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
import java.util.concurrent.Executors;

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
import com.heojungseok.openmetadatasync.batch.parameter.SyncContract;
import com.heojungseok.openmetadatasync.batch.parameter.Tuning;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
	private static final AtomicInteger CROSSREF_FAILURES = new AtomicInteger();

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
			if (CROSSREF_FAILURES.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
				byte[] unavailable = "unavailable".getBytes();
				exchange.getResponseHeaders().add("Retry-After", "0");
				exchange.sendResponseHeaders(503, unavailable.length);
				exchange.getResponseBody().write(unavailable);
				exchange.close();
				return;
			}
			byte[] response = """
					{\"status\":\"ok\",\"message\":{\"total-results\":0,\"items-per-page\":0,\"items\":[],\"next-cursor\":null}}
					""".strip().getBytes();
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		crossref.start();
		openApplication();
	}

	private static void openApplication() {
		context = new SpringApplicationBuilder(OpenMetadataSyncApplication.class)
				.profiles("actual")
				.properties("spring.main.banner-mode=off")
				.properties("spring.jpa.properties.hibernate.generate_statistics=true")
				.properties("spring.jpa.properties.hibernate.jdbc.batch_size=5")
				.properties("crossref.base-uri=http://127.0.0.1:" + crossref.getAddress().getPort() + "/works")
				.run(
						"--DB_HOST=" + MYSQL.getHost(),
						"--DB_PORT=" + MYSQL.getMappedPort(3306),
						"--DB_USERNAME=root",
						"--DB_PASSWORD=" + MYSQL.getPassword(),
						"--HIBERNATE_BATCH_SIZE=5"
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
	void incrementalWithoutWatermarkRejectsIndexedFromAsABootstrapSubstitute() throws Exception {
		jdbc.update("DELETE FROM sync_watermark");
		int before = CROSSREF_REQUESTS.get();
		String requestId = UUID.randomUUID().toString();

		JobExecution result = operator.start(crossrefJob, new JobParametersBuilder()
				.addString("requestId", requestId, true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "INCREMENTAL", true)
				.addString("indexedFromUtc", "2026-08-01T00:00:00Z", true)
				.addLong("chunkSize", 5L, false)
				.toJobParameters());

		assertThat(result.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(CROSSREF_REQUESTS).hasValue(before);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM sync_execution WHERE request_id = ?", Long.class, requestId
		)).isZero();
	}

	@Test
	void incrementalWithWatermarkRejectsDifferentIndexedFromBeforeHttpOrMutation() throws Exception {
		String sourceName = "watermark-exact-" + UUID.randomUUID();
		UUID watermarkExecution = sourceErrorFixture();
		jdbc.update("""
				INSERT INTO sync_watermark (source_name, indexed_until_utc, execution_id, updated_at)
				VALUES (?, '2026-08-01 00:00:00', ?, UTC_TIMESTAMP(6))
				""", sourceName, bytes(watermarkExecution));
		int requestsBefore = CROSSREF_REQUESTS.get();
		String requestId = UUID.randomUUID().toString();

		JobExecution result = operator.start(crossrefJob, new JobParametersBuilder()
				.addString("requestId", requestId, true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "INCREMENTAL", true)
				.addString("sourceName", sourceName, true)
				.addString("indexedFromUtc", "2026-07-31T00:00:00Z", true)
				.addString("indexedUntilUtc", "2026-08-02T00:00:00Z", true)
				.addLong("chunkSize", 5L, false)
				.toJobParameters());

		assertThat(result.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(CROSSREF_REQUESTS).hasValue(requestsBefore);
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
		Long sourceErrorKey = jdbc.queryForObject(
				"SELECT error_key FROM sync_error WHERE execution_id = ?", Long.class, bytes(sourceExecution)
		);
		assertThat(jdbc.queryForObject("""
				SELECT source_error_key FROM staging_work
				WHERE execution_id = (SELECT id FROM sync_execution WHERE request_id = ?)
				""", Long.class, requestId)).isEqualTo(sourceErrorKey);
		assertThat(jdbc.queryForObject(
				"SELECT status FROM sync_error WHERE error_key = ?", String.class, sourceErrorKey
		)).isEqualTo("RESOLVED");
		assertThat(jdbc.queryForObject(
				"SELECT replay_count FROM sync_error WHERE error_key = ?", Integer.class, sourceErrorKey
		)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT resolved_at IS NOT NULL FROM sync_error WHERE error_key = ?", Boolean.class, sourceErrorKey
		)).isTrue();
		assertThat(stepNames(replay)).containsExactlyInAnyOrder(
				"prepareCrossrefExecution", "replayPrepare", "beginSync", "sync", "beginVerify", "verify"
		);
	}

	@Test
	void existingRequestRejectsChangedModeSpecificFrozenFieldsBeforeWork() throws Exception {
		String backfillRequest = UUID.randomUUID().toString();
		JobParameters backfill = new JobParametersBuilder()
				.addString("requestId", backfillRequest, true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "BACKFILL", true)
				.addLocalDate("createdFrom", java.time.LocalDate.parse("2026-08-01"), true)
				.addLocalDate("createdUntil", java.time.LocalDate.parse("2026-08-02"), true)
				.addLong("maxItems", 10L, true)
				.addLong("chunkSize", 5L, false)
				.toJobParameters();
		assertThat(operator.start(crossrefJob, backfill).getStatus()).isEqualTo(BatchStatus.COMPLETED);

		String incrementalSource = "incremental-frozen-" + UUID.randomUUID();
		jdbc.update("""
				INSERT INTO sync_watermark (source_name, indexed_until_utc, execution_id, updated_at)
				VALUES (?, '2026-08-01 00:00:00', ?, UTC_TIMESTAMP(6))
				""", incrementalSource, bytes(executionIdForRequest(backfillRequest)));
		String incrementalRequest = UUID.randomUUID().toString();
		JobParameters incremental = new JobParametersBuilder()
				.addString("requestId", incrementalRequest, true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "INCREMENTAL", true)
				.addString("sourceName", incrementalSource, true)
				.addString("bootstrapIndexedFrom", "2026-08-01T00:00:00Z", true)
				.addString("indexedUntilUtc", "2026-08-02T00:00:00Z", true)
				.addLong("chunkSize", 5L, false)
				.toJobParameters();
		assertThat(operator.start(crossrefJob, incremental).getStatus()).isEqualTo(BatchStatus.COMPLETED);

		String replayRequest = UUID.randomUUID().toString();
		UUID firstSource = sourceErrorFixture();
		JobParameters replay = new JobParametersBuilder()
				.addString("requestId", replayRequest, true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "REPLAY_ERRORS", true)
				.addString("sourceExecutionId", firstSource.toString(), true)
				.addLong("chunkSize", 5L, false)
				.toJobParameters();
		assertThat(operator.start(crossrefJob, replay).getStatus()).isEqualTo(BatchStatus.COMPLETED);

		java.util.List<JobParameters> changed = java.util.List.of(
				new JobParametersBuilder(backfill)
						.addLong("maxItems", 11L, true).toJobParameters(),
				new JobParametersBuilder(incremental)
						.addString("bootstrapIndexedFrom", "2026-07-31T00:00:00Z", true).toJobParameters(),
				new JobParametersBuilder(replay)
						.addString("sourceExecutionId", sourceErrorFixture().toString(), true).toJobParameters()
		);
		long windowsBefore = jdbc.queryForObject("""
				SELECT COUNT(*) FROM sync_window window_row JOIN sync_execution execution_row
				  ON execution_row.id = window_row.execution_id
				WHERE execution_row.request_id IN (?, ?, ?)
				""", Long.class, backfillRequest, incrementalRequest, replayRequest);
		long cursorsBefore = jdbc.queryForObject("""
				SELECT COUNT(*) FROM sync_window window_row JOIN sync_execution execution_row
				  ON execution_row.id = window_row.execution_id
				WHERE execution_row.request_id IN (?, ?, ?)
				  AND (window_row.cursor_value IS NOT NULL OR window_row.next_cursor_value IS NOT NULL)
				""", Long.class, backfillRequest, incrementalRequest, replayRequest);
		int before = CROSSREF_REQUESTS.get();
		for (JobParameters request : changed) {
			JobExecution rejected = operator.start(crossrefJob, request);
			assertThat(rejected.getStatus()).isEqualTo(BatchStatus.FAILED);
			assertThat(rejected.getAllFailureExceptions().toString()).contains("Frozen request contract changed");
			assertThat(rejected.getExecutionContext().containsKey("syncExecutionId")).isFalse();
		}
		assertThat(CROSSREF_REQUESTS).hasValue(before);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM sync_window window_row JOIN sync_execution execution_row
				  ON execution_row.id = window_row.execution_id
				WHERE execution_row.request_id IN (?, ?, ?)
				""", Long.class, backfillRequest, incrementalRequest, replayRequest)).isEqualTo(windowsBefore);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM sync_window window_row JOIN sync_execution execution_row
				  ON execution_row.id = window_row.execution_id
				WHERE execution_row.request_id IN (?, ?, ?)
				  AND (window_row.cursor_value IS NOT NULL OR window_row.next_cursor_value IS NOT NULL)
				""", Long.class, backfillRequest, incrementalRequest, replayRequest)).isEqualTo(cursorsBefore);
		assertThat(jdbc.queryForObject(
				"SELECT max_items FROM sync_execution WHERE request_id = ?", Long.class, backfillRequest
		)).isEqualTo(10);
		assertThat(jdbc.queryForObject(
				"SELECT indexed_from_utc FROM sync_execution WHERE request_id = ?", LocalDateTime.class, incrementalRequest
		)).isEqualTo(LocalDateTime.of(2026, 8, 1, 0, 0));
		assertThat(jdbc.queryForObject(
				"SELECT source_execution_id FROM sync_execution WHERE request_id = ?", byte[].class, replayRequest
		)).isEqualTo(bytes(firstSource));
	}

	@Test
	void incrementalProcessRestartReusesFrozenUntilAndWindows() throws Exception {
		String sourceName = "process-restart-" + UUID.randomUUID();
		UUID watermarkExecution = sourceErrorFixture();
		jdbc.update("""
				INSERT INTO sync_watermark (source_name, indexed_until_utc, execution_id, updated_at)
				VALUES (?, '2026-08-01 00:00:00', ?, UTC_TIMESTAMP(6))
				""", sourceName, bytes(watermarkExecution));
		String requestId = UUID.randomUUID().toString();
		JobParameters parameters = new JobParametersBuilder()
				.addString("requestId", requestId, true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "INCREMENTAL", true)
				.addString("sourceName", sourceName, true)
				.addLong("chunkSize", 5L, false)
				.toJobParameters();
		CROSSREF_FAILURES.set(3);

		JobExecution failed = operator.start(crossrefJob, parameters);

		assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
		LocalDateTime frozenUntil = jdbc.queryForObject(
				"SELECT indexed_until_utc FROM sync_execution WHERE request_id = ?",
				LocalDateTime.class, requestId
		);
		long windows = jdbc.queryForObject("""
				SELECT COUNT(*) FROM sync_window window_row JOIN sync_execution execution_row
				  ON execution_row.id = window_row.execution_id WHERE execution_row.request_id = ?
				""", Long.class, requestId);
		assertThat(windows).isPositive();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM sync_window window_row JOIN sync_execution execution_row
				  ON execution_row.id = window_row.execution_id
				WHERE execution_row.request_id = ?
				  AND (window_row.cursor_value IS NOT NULL OR window_row.next_cursor_value IS NOT NULL)
				""", Long.class, requestId)).isZero();

		context.close();
		openApplication();
		JobExecution restarted = operator.start(crossrefJob, parameters);

		assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(jdbc.queryForObject(
				"SELECT indexed_until_utc FROM sync_execution WHERE request_id = ?",
				LocalDateTime.class, requestId
		)).isEqualTo(frozenUntil);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM sync_window window_row JOIN sync_execution execution_row
				  ON execution_row.id = window_row.execution_id WHERE execution_row.request_id = ?
				""", Long.class, requestId)).isEqualTo(windows);
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
		Long sourceErrorKey = jdbc.queryForObject(
				"SELECT error_key FROM sync_error WHERE execution_id = ?", Long.class, bytes(sourceExecution)
		);
		assertThat(jdbc.queryForObject(
				"SELECT status FROM sync_error WHERE error_key = ?", String.class, sourceErrorKey
		)).isEqualTo("OPEN");
		assertThat(jdbc.queryForObject(
				"SELECT replay_count FROM sync_error WHERE error_key = ?", Integer.class, sourceErrorKey
		)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT resolved_at IS NULL FROM sync_error WHERE error_key = ?", Boolean.class, sourceErrorKey
		)).isTrue();

		JobExecution restarted = operator.restart(replay);

		assertThat(restarted.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(stepNames(restarted)).doesNotContain("replayPrepare");
		assertThat(jdbc.queryForObject(
				"SELECT replay_count FROM sync_error WHERE error_key = ?", Integer.class, sourceErrorKey
		)).isEqualTo(1);
	}

	@Test
	void smallInitialFailureRestartAndNoOpUseTheProductionReaderWriterVerifierAndEvidence() throws Exception {
		UUID initialExecution = UUID.randomUUID();
		JobParameters initial = parameters(initialExecution, "initial", true);

		JobExecution failed = operator.start(benchmarkJob, initial);

		assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(benchmarkTargetCount()).isEqualTo(5);
		assertThat(chunkCount(initialExecution)).isEqualTo(1);
		var failedSync = failed.getStepExecutions().stream()
				.filter(step -> step.getStepName().equals("sync")).findFirst().orElseThrow();
		assertThat(failedSync.getCommitCount()).isPositive();
		assertThat(failedSync.getExecutionContext().entrySet())
				.anySatisfy(entry -> {
					assertThat(entry.getKey()).endsWith(".lastCommittedKey");
					assertThat((Long) entry.getValue()).isPositive();
				});
		assertThat(failed.getExecutionContext().getLong("syncTargetInserts")).isEqualTo(5);
		assertThat(status(initialExecution)).isEqualTo("SYNCING");
		context.close();
		openApplication();
		JobExecution restarted = operator.start(benchmarkJob, initial);

		assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(status(initialExecution)).isEqualTo("COMPLETED");
		assertThat(stagingCount(initialExecution)).isEqualTo(12);
		assertThat(benchmarkTargetCount()).isEqualTo(12);
		assertThat(outcome(initialExecution, "inserted_count")).isEqualTo(12);
		assertThat(chunkCount(initialExecution)).isEqualTo(3);
		String initialEvidence = Files.readString(evidenceDirectory.resolve("benchmark-12-initial.json"));
		assertThat(initialEvidence)
				.contains("\"restart\"", "\"passed\" : true", "\"inserted\" : 12");
		var initialJson = new ObjectMapper().readTree(initialEvidence);
		assertThat(restarted.getExecutionContext().getLong("syncTargetInserts")).isEqualTo(7);
		assertThat(initialJson.get("dml").get("targetInserts").asLong()).isEqualTo(12);
		assertThat(initialJson.get("persistence").get("jdbcBatches").asLong()).isPositive();
		assertThat(initialJson.get("persistence").get("queries").asLong())
				.isEqualTo(failed.getExecutionContext().getLong("syncQueries")
						+ restarted.getExecutionContext().getLong("syncQueries"));
		assertThat(initialJson.get("persistence").get("preparedStatements").asLong())
				.isEqualTo(failed.getExecutionContext().getLong("syncPreparedStatements")
						+ restarted.getExecutionContext().getLong("syncPreparedStatements"));
		assertThat(initialJson.get("persistence").get("jdbcBatches").asLong())
				.isEqualTo(failed.getExecutionContext().getLong("syncJdbcBatches")
						+ restarted.getExecutionContext().getLong("syncJdbcBatches"));

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
		var noOpJson = new ObjectMapper().readTree(
				Files.readString(evidenceDirectory.resolve("benchmark-12-no-op.json"))
		);
		assertThat(noOpJson.toString()).contains("\"scenario\":\"no-op\"", "\"targetUpdates\":0");
		assertThat(noOpJson.get("dml").get("updatedAtAfter").stringValue())
				.isEqualTo(noOpJson.get("dml").get("updatedAtBefore").stringValue());
		assertThat(noOp.getExecutionContext().getLong("syncTargetUpdates")).isZero();
	}

	@Test
	void evidenceOnlyRestartDoesNotDoubleCountInheritedSyncMetrics() throws Exception {
		UUID executionId = UUID.randomUUID();
		Path blockedDirectory = evidenceDirectory.resolve("blocked-evidence");
		Files.writeString(blockedDirectory, "not a directory");
		JobParameters failedParameters = new JobParametersBuilder(parameters(executionId, "initial", false, 951))
				.addString("evidenceDirectory", blockedDirectory.toString(), false)
				.toJobParameters();

		JobExecution failed = operator.start(benchmarkJob, failedParameters);

		assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(failed.getStepExecutions()).anySatisfy(step -> {
			assertThat(step.getStepName()).isEqualTo("sync");
			assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		});
		assertThat(failed.getExecutionContext().getLong("syncTargetInserts")).isEqualTo(12);
		assertThat(failed.getExecutionContext().getLong("syncMetricsOwnerExecutionId")).isEqualTo(failed.getId());
		context.close();
		openApplication();
		Path restartDirectory = evidenceDirectory.resolve("evidence-restart");
		JobParameters restartParameters = new JobParametersBuilder(failedParameters)
				.addString("evidenceDirectory", restartDirectory.toString(), false)
				.toJobParameters();

		JobExecution restarted = operator.start(benchmarkJob, restartParameters);

		assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(stepNames(restarted)).containsExactly("benchmarkEvidence");
		assertThat(restarted.getExecutionContext().getLong("syncTargetInserts")).isEqualTo(12);
		assertThat(restarted.getExecutionContext().getLong("syncMetricsOwnerExecutionId")).isEqualTo(failed.getId());
		var evidence = new ObjectMapper().readTree(
				Files.readString(restartDirectory.resolve("benchmark-12-initial.json"))
		);
		assertThat(evidence.get("dml").get("targetInserts").asLong()).isEqualTo(12);
		assertThat(evidence.get("persistence").get("queries").asLong())
				.isEqualTo(failed.getExecutionContext().getLong("syncQueries"));
		assertThat(evidence.get("persistence").get("preparedStatements").asLong())
				.isEqualTo(failed.getExecutionContext().getLong("syncPreparedStatements"));
		assertThat(evidence.get("persistence").get("jdbcBatches").asLong())
				.isEqualTo(failed.getExecutionContext().getLong("syncJdbcBatches"));
		assertThat(evidence.get("timing").get("syncMillis").asLong())
				.isEqualTo(failed.getExecutionContext().getLong("syncMillis"));
	}

	@Test
	void failedBenchmarkDoesNotWedgeTheNextBenchmarkAndCrossrefDoesNotEnterItsMetrics() throws Exception {
		UUID failedId = UUID.randomUUID();
		JobExecution failed = operator.start(benchmarkJob, parameters(failedId, "initial", true, 701));
		assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);

		JobExecution crossrefExecution = operator.start(crossrefJob, new JobParametersBuilder()
				.addString("requestId", UUID.randomUUID().toString(), true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "BACKFILL", true)
				.addLocalDate("createdFrom", java.time.LocalDate.parse("2026-08-01"), true)
				.addLocalDate("createdUntil", java.time.LocalDate.parse("2026-08-02"), true)
				.addLong("maxItems", 10L, true)
				.addLong("chunkSize", 5L, false)
				.toJobParameters());
		assertThat(crossrefExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

		UUID completedId = UUID.randomUUID();
		JobExecution completed = operator.start(
				benchmarkJob, parameters(completedId, "initial", false, 702)
		);

		assertThat(completed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(outcome(completedId, "inserted_count")).isEqualTo(12);
		assertThat(new ObjectMapper().readTree(
				Files.readString(evidenceDirectory.resolve("benchmark-12-initial.json"))
		).get("dml").get("targetInserts").asLong()).isEqualTo(12);
	}

	@Test
	void concurrentBenchmarksFailOneBeforeItsPreload() throws Exception {
		UUID firstId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		try (var executor = Executors.newSingleThreadExecutor()) {
			var first = executor.submit(() -> operator.start(
					benchmarkJob, benchmarkParameters(firstId, 5_000, 801, evidenceDirectory.resolve("a"))
			));
			long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
			while (stagingCount(firstId) == 0 && System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}
			JobExecution failed = operator.start(
					benchmarkJob, benchmarkParameters(secondId, 5_000, 802, evidenceDirectory.resolve("b"))
			);
			JobExecution completed = first.get();

			assertThat(completed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
			assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
			assertThat(failed.getAllFailureExceptions().toString()).contains("Concurrent data-plane job");
			assertThat(jdbc.queryForObject(
					"SELECT COUNT(*) FROM staging_work WHERE execution_id = ?", Long.class, bytes(secondId)
			)).isZero();
		}
	}

	@Test
	void crossrefCannotRunDuringBenchmarkSyncOrContaminateItsEvidence() throws Exception {
		UUID baselineId = UUID.randomUUID();
		Path baselineDirectory = evidenceDirectory.resolve("baseline");
		assertThat(operator.start(
				benchmarkJob, benchmarkParameters(baselineId, 5_000, 811, baselineDirectory)
		).getStatus()).isEqualTo(BatchStatus.COMPLETED);
		var baseline = new ObjectMapper().readTree(
				Files.readString(baselineDirectory.resolve("benchmark-5000-initial.json"))
		).get("persistence");

		UUID measuredId = UUID.randomUUID();
		Path measuredDirectory = evidenceDirectory.resolve("measured");
		try (var executor = Executors.newSingleThreadExecutor()) {
			var measured = executor.submit(() -> operator.start(
					benchmarkJob, benchmarkParameters(measuredId, 5_000, 812, measuredDirectory)
			));
			long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
			while (!"SYNCING".equals(nullableStatus(measuredId)) && System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}
			String requestId = UUID.randomUUID().toString();
			int httpBefore = CROSSREF_REQUESTS.get();
			JobExecution rejected = operator.start(crossrefJob, new JobParametersBuilder()
					.addString("requestId", requestId, true)
					.addString("syncContractHash", SyncContract.hash(), true)
					.addString("mode", "BACKFILL", true)
					.addLocalDate("createdFrom", java.time.LocalDate.parse("2026-08-01"), true)
					.addLocalDate("createdUntil", java.time.LocalDate.parse("2026-08-02"), true)
					.addLong("maxItems", 10L, true)
					.addLong("chunkSize", 5L, false)
					.toJobParameters());

			assertThat(rejected.getStatus()).isEqualTo(BatchStatus.FAILED);
			assertThat(rejected.getAllFailureExceptions().toString()).contains("Concurrent data-plane job");
			assertThat(CROSSREF_REQUESTS).hasValue(httpBefore);
			assertThat(jdbc.queryForObject(
					"SELECT COUNT(*) FROM sync_execution WHERE request_id = ?", Long.class, requestId
			)).isZero();
			assertThat(measured.get().getStatus()).isEqualTo(BatchStatus.COMPLETED);
		}
		var measured = new ObjectMapper().readTree(
				Files.readString(measuredDirectory.resolve("benchmark-5000-initial.json"))
		).get("persistence");
		assertThat(measured.get("queries")).isEqualTo(baseline.get("queries"));
		assertThat(measured.get("preparedStatements")).isEqualTo(baseline.get("preparedStatements"));
		assertThat(measured.get("jdbcBatches")).isEqualTo(baseline.get("jdbcBatches"));
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

	@Test
	void benchmarkRejectsConfiguredBatchSizeThatDiffersFromTheActualSessionFactory() throws Exception {
		UUID executionId = UUID.randomUUID();
		JobParameters parameters = DataPlaneBenchmarkJobConfig.parameters(
				executionId.toString(), 12, 901, "v1", "initial",
				new Tuning(5, 4), evidenceDirectory, false
		);

		JobExecution result = operator.start(benchmarkJob, parameters);

		assertThat(result.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(result.getAllFailureExceptions().toString()).contains("actual Hibernate batch size");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM sync_execution WHERE id = ?", Long.class, bytes(executionId)
		)).isZero();
		assertThat(stagingCount(executionId)).isZero();
	}

	@Test
	void benchmarkExistingRequestRejectsEveryChangedIdentifyingFieldBeforePreload() throws Exception {
		UUID executionId = UUID.randomUUID();
		JobParameters original = parameters(executionId, "initial", false, 921);
		assertThat(operator.start(benchmarkJob, original).getStatus()).isEqualTo(BatchStatus.COMPLETED);
		long stagingBefore = stagingCount(executionId);
		String statusBefore = status(executionId);

		java.util.List<JobParameters> changed = java.util.List.of(
				new JobParametersBuilder(original).addLong("rowCount", 13L, true).toJobParameters(),
				new JobParametersBuilder(original).addLong("seed", 922L, true).toJobParameters(),
				new JobParametersBuilder(original).addString("generatorVersion", "v2", true).toJobParameters(),
				new JobParametersBuilder(original).addString("scenario", "no-op", true).toJobParameters(),
				new JobParametersBuilder(original).addString("syncContractHash", "0".repeat(64), true).toJobParameters()
		);
		for (JobParameters request : changed) {
			JobExecution rejected = operator.start(benchmarkJob, request);
			assertThat(rejected.getStatus()).isEqualTo(BatchStatus.FAILED);
			assertThat(rejected.getAllFailureExceptions().toString())
					.contains("Frozen benchmark contract changed");
			assertThat(rejected.getExecutionContext().containsKey("syncExecutionId")).isFalse();
			assertThat(stagingCount(executionId)).isEqualTo(stagingBefore);
			assertThat(status(executionId)).isEqualTo(statusBefore);
		}
	}

	@Test
	void sameBenchmarkContractResumesAPartialPreloadAfterProcessRestart() throws Exception {
		UUID executionId = UUID.randomUUID();
		long seed = 931;
		JobParameters parameters = parameters(executionId, "initial", true, seed);
		JobExecution failed = operator.start(benchmarkJob, parameters);
		assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);

		jdbc.update(
				"DELETE FROM staging_work WHERE execution_id = ? AND execution_sequence > 5", bytes(executionId)
		);
		jdbc.update("""
				UPDATE sync_execution SET business_status = 'PREPARING', expected_count = NULL,
				  staging_upper_bound = NULL WHERE id = ?
				""", bytes(executionId));
		jdbc.update("""
				UPDATE BATCH_STEP_EXECUTION SET STATUS = 'FAILED', EXIT_CODE = 'FAILED'
				WHERE JOB_EXECUTION_ID = ? AND STEP_NAME IN ('syntheticPreload', 'beginSync')
				""", failed.getId());
		assertThat(stagingCount(executionId)).isEqualTo(5);

		context.close();
		openApplication();
		JobParameters restartParameters = new JobParametersBuilder(parameters)
				.addLong("failFirstExecution", 0L, false)
				.toJobParameters();
		JobExecution restarted = operator.start(benchmarkJob, restartParameters);

		assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(stagingCount(executionId)).isEqualTo(12);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(DISTINCT execution_sequence) FROM staging_work WHERE execution_id = ?
				""", Long.class, bytes(executionId))).isEqualTo(12);
		assertThat(outcome(executionId, "inserted_count")).isEqualTo(12);
		assertThat(status(executionId)).isEqualTo("COMPLETED");
	}

	@Test
	void millionJobValidatorRejectsMissingPreflightBeforeExecutionOrPreload() throws Exception {
		String requestId = UUID.randomUUID().toString();
		JobParameters parameters = new JobParametersBuilder()
				.addString("requestId", requestId, true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "BENCHMARK", true)
				.addLong("rowCount", 1_000_000L, true)
				.addLong("seed", 42L, true)
				.addString("generatorVersion", "v1", true)
				.addString("scenario", "initial", true)
				.addLong("chunkSize", 5L, false)
				.addLong("hibernateBatchSize", 5L, false)
				.addString("evidenceDirectory", evidenceDirectory.toString(), false)
				.toJobParameters();

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> operator.start(benchmarkJob, parameters))
				.withMessageContaining("100k initial and no-op");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM sync_execution WHERE request_id = ?", Long.class, requestId
		)).isZero();
	}

	private JobParameters parameters(UUID executionId, String scenario, boolean failFirstExecution) {
		return parameters(executionId, scenario, failFirstExecution, 42);
	}

	private JobParameters parameters(
			UUID executionId, String scenario, boolean failFirstExecution, long seed
	) {
		return DataPlaneBenchmarkJobConfig.parameters(
				executionId.toString(), 12, seed, "v1", scenario,
				new Tuning(5, 5), evidenceDirectory, failFirstExecution
		);
	}

	private JobParameters benchmarkParameters(UUID id, long rows, long seed, Path directory) {
		return DataPlaneBenchmarkJobConfig.parameters(
				id.toString(), rows, seed, "v1", "initial", new Tuning(100, 5), directory, false
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

	private static String nullableStatus(UUID executionId) {
		return jdbc.query(
				"SELECT business_status FROM sync_execution WHERE id = ?",
				result -> result.next() ? result.getString(1) : null, bytes(executionId)
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
