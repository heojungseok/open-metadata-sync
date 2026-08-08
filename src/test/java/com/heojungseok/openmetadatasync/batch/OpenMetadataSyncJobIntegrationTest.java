package com.heojungseok.openmetadatasync.batch;

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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
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
import com.heojungseok.openmetadatasync.batch.execution.ExecutionStatus;
import com.heojungseok.openmetadatasync.batch.replay.JpaErrorReplayReader;
import com.heojungseok.openmetadatasync.batch.replay.ReplayWork;
import com.heojungseok.openmetadatasync.batch.verify.JpaExecutionVerifier;
import com.heojungseok.openmetadatasync.batch.verify.VerificationResult;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OpenMetadataSyncJobIntegrationTest {

	@Container
	static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10");

	private static final AtomicInteger JOB_SEQUENCE = new AtomicInteger();
	private static final Instant BASE_WATERMARK = Instant.parse("2026-08-07T00:00:00.000001Z");
	private static final Instant REQUESTED_UNTIL = Instant.parse("2026-08-08T00:00:00.000001Z");

	private static ConfigurableApplicationContext context;
	private static EntityManager entityManager;
	private static JdbcTemplate jdbc;
	private static JobRepository jobRepository;
	private static TransactionTemplate transaction;

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
		entityManager = context.getBean(EntityManager.class);
		jdbc = context.getBean(JdbcTemplate.class);
		jobRepository = context.getBean(JobRepository.class);
		transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
	}

	@AfterAll
	static void stopApplication() {
		if (context != null) {
			context.close();
		}
	}

	@Test
	void reconcilesTheFrozenRangeIncludingDuplicateDoiAndAdvancesTheWatermark() {
		Fixture fixture = fixture("VERIFYING");
		long older = staging(fixture, "10.1000/duplicate", 1, 1, REQUESTED_UNTIL.minusSeconds(2));
		long winner = staging(fixture, "10.1000/duplicate", 2, 2, REQUESTED_UNTIL.minusSeconds(1));
		long other = staging(fixture, "10.1000/other", 3, 3, REQUESTED_UNTIL);
		freeze(fixture, List.of(older, winner, other));
		target("10.1000/duplicate", 2, REQUESTED_UNTIL.minusSeconds(1));
		target("10.1000/other", 3, REQUESTED_UNTIL);
		outcomes(fixture, older, other, 2, 1, 0, 0, 0, 0, 0);

		VerificationResult result = verifyCommitted(fixture);

		assertThat(result.batchStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(result.exitStatus().getExitCode()).isEqualTo("COMPLETED");
		assertThat(result.businessStatus()).isEqualTo(ExecutionStatus.COMPLETED);
		assertThat(result.eligibleCount()).isEqualTo(3);
		assertThat(result.accountedCount()).isEqualTo(3);
		assertThat(result.duplicateDoiCount()).isEqualTo(1);
		assertThat(status(fixture)).isEqualTo("COMPLETED");
		assertThat(watermark(fixture)).isEqualTo(REQUESTED_UNTIL);
		assertThat(jobRepository.getJobExecution(fixture.stepExecution().getJobExecutionId()).getStatus())
				.isEqualTo(BatchStatus.COMPLETED);
	}

	@Test
	void rejectsOutcomeDoiAndChecksumReconciliationMismatchesWithoutAdvancingWatermark() {
		Fixture outcomeMismatch = fixture("VERIFYING");
		long outcomeKey = staging(outcomeMismatch, "10.1000/outcome-mismatch", 1, 1, REQUESTED_UNTIL);
		freeze(outcomeMismatch, List.of(outcomeKey));
		target("10.1000/outcome-mismatch", 1, REQUESTED_UNTIL);
		outcomes(outcomeMismatch, outcomeKey, outcomeKey, 0, 0, 0, 0, 0, 0, 0);

		Fixture doiMismatch = fixture("VERIFYING");
		long missingTarget = staging(doiMismatch, "10.1000/missing-target", 2, 1, REQUESTED_UNTIL);
		freeze(doiMismatch, List.of(missingTarget));
		outcomes(doiMismatch, missingTarget, missingTarget, 1, 0, 0, 0, 0, 0, 0);

		Fixture checksumMismatch = fixture("VERIFYING");
		long wrongHash = staging(checksumMismatch, "10.1000/wrong-hash", 3, 1, REQUESTED_UNTIL);
		freeze(checksumMismatch, List.of(wrongHash));
		target("10.1000/wrong-hash", 4, REQUESTED_UNTIL);
		outcomes(checksumMismatch, wrongHash, wrongHash, 0, 0, 1, 0, 0, 0, 0);

		List<Fixture> fixtures = List.of(outcomeMismatch, doiMismatch, checksumMismatch);
		List<String> failureEvidence = List.of("outcome counts", "DOI is missing", "checksum differs");
		for (int index = 0; index < fixtures.size(); index++) {
			Fixture fixture = fixtures.get(index);
			VerificationResult result = verifyCommitted(fixture);

			assertThat(result.batchStatus()).isEqualTo(BatchStatus.FAILED);
			assertThat(result.exitStatus().getExitCode()).isEqualTo("FAILED");
			assertThat(result.exitStatus().getExitDescription()).contains(failureEvidence.get(index));
			assertThat(result.businessStatus()).isEqualTo(ExecutionStatus.FAILED);
			assertThat(status(fixture)).isEqualTo("FAILED");
			assertThat(watermark(fixture)).isEqualTo(BASE_WATERMARK);
		}
	}

	@Test
	void validationOnlyCompletesWithErrorsAndAdvancesTheWatermark() {
		Fixture fixture = fixture("VERIFYING");
		long key = staging(fixture, "10.1000/invalid", 1, 1, REQUESTED_UNTIL);
		freeze(fixture, List.of(key));
		error(fixture, key, "VALIDATION", "INVALID_CANONICAL");
		outcomes(fixture, key, key, 0, 0, 0, 0, 0, 0, 1);

		VerificationResult result = verifyCommitted(fixture);

		assertThat(result.batchStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(result.exitStatus().getExitCode()).isEqualTo("COMPLETED_WITH_ERRORS");
		assertThat(result.businessStatus()).isEqualTo(ExecutionStatus.COMPLETED_WITH_ERRORS);
		assertThat(status(fixture)).isEqualTo("COMPLETED_WITH_ERRORS");
		assertThat(watermark(fixture)).isEqualTo(REQUESTED_UNTIL);
		assertThat(openErrors(fixture)).isEqualTo(1);
		JobExecution durableJob = jobRepository.getJobExecution(fixture.stepExecution().getJobExecutionId());
		assertThat(durableJob.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(durableJob.getExitStatus().getExitCode()).isEqualTo("COMPLETED_WITH_ERRORS");
	}

	@Test
	void conflictWinsOverValidationAndKeepsTheWatermarkUnchanged() {
		for (boolean mixed : List.of(false, true)) {
			Fixture fixture = fixture("VERIFYING");
			long conflictKey = staging(fixture, "10.1000/conflict-" + mixed, 1, 1, REQUESTED_UNTIL);
			List<Long> keys = new ArrayList<>(List.of(conflictKey));
			error(fixture, conflictKey, "CONFLICT", "SAME_TIMESTAMP_DIFFERENT_CONTENT");
			long validationCount = 0;
			if (mixed) {
				long validationKey = staging(fixture, "10.1000/invalid-mixed", 2, 2, REQUESTED_UNTIL);
				keys.add(validationKey);
				error(fixture, validationKey, "VALIDATION", "INVALID_CANONICAL");
				validationCount = 1;
			}
			freeze(fixture, keys);
			outcomes(
					fixture, keys.getFirst(), keys.getLast(), 0, 0, 0, 1, 0, 0, validationCount
			);

			VerificationResult result = verifyCommitted(fixture);

			assertThat(result.batchStatus()).isEqualTo(BatchStatus.FAILED);
			assertThat(result.businessStatus()).isEqualTo(ExecutionStatus.FAILED);
			assertThat(status(fixture)).isEqualTo("FAILED");
			assertThat(watermark(fixture)).isEqualTo(BASE_WATERMARK);
			assertThat(openErrors(fixture)).isEqualTo(mixed ? 2 : 1);
			assertThat(jobRepository.getJobExecution(fixture.stepExecution().getJobExecutionId()).getStatus())
					.isEqualTo(BatchStatus.FAILED);
		}
	}

	@Test
	void fixedOpenErrorSnapshotReplaysOriginalStagingAndRestartsAtTheDurableErrorKey() {
		Fixture source = fixture("FAILED");
		List<Long> sourceKeys = List.of(
				staging(source, "10.1000/replay-1", 1, 1, REQUESTED_UNTIL.minusSeconds(2)),
				staging(source, "10.1000/replay-2", 2, 2, REQUESTED_UNTIL.minusSeconds(1)),
				staging(source, "10.1000/replay-3", 3, 3, REQUESTED_UNTIL)
		);
		freeze(source, sourceKeys);
		for (long key : sourceKeys) {
			error(source, key, "VALIDATION", "REPLAYABLE");
		}
		StepExecution step = createStepExecution();
		JpaErrorReplayReader reader = new JpaErrorReplayReader(entityManager, source.executionId(), 2);
		reader.open(step.getExecutionContext());
		jobRepository.updateExecutionContext(step);

		long lateKey = staging(source, "10.1000/replay-late", 4, 4, REQUESTED_UNTIL.plusSeconds(1));
		error(source, lateKey, "VALIDATION", "REPLAYABLE");
		ReplayWork first = read(reader);
		transaction.executeWithoutResult(status -> {
			reader.update(step.getExecutionContext());
			jobRepository.updateExecutionContext(step);
		});
		ReplayWork rolledBack = transaction.execute(status -> {
			ReplayWork work = read(reader);
			reader.update(step.getExecutionContext());
			jobRepository.updateExecutionContext(step);
			status.setRollbackOnly();
			return work;
		});

		StepExecution durable = jobRepository.getStepExecution(step.getId());
		reader.close();
		reader.open(durable.getExecutionContext());
		List<ReplayWork> restarted = readToEnd(reader);

		assertThat(first.work().stagingKey()).isEqualTo(sourceKeys.getFirst());
		assertThat(first.work().doi()).isEqualTo("10.1000/replay-1");
		assertThat(rolledBack.work().stagingKey()).isEqualTo(sourceKeys.get(1));
		assertThat(restarted).extracting(work -> work.work().stagingKey())
				.containsExactly(sourceKeys.get(1), sourceKeys.get(2))
				.doesNotContain(lateKey);
		assertThat(restarted).extracting(ReplayWork::errorKey).isSorted().doesNotHaveDuplicates();
	}

	@Test
	void collectSyncAndVerifyTransactionFailuresKeepApplicationRowsCheckpointAndWatermarkRestartable() {
		for (String phaseStatus : List.of("COLLECTING", "SYNCING", "VERIFYING")) {
			Fixture fixture = fixture(phaseStatus);
			long key = staging(fixture, "10.1000/restart-" + phaseStatus.toLowerCase(), 1, 1, REQUESTED_UNTIL);
			freeze(fixture, List.of(key));
			target("10.1000/restart-" + phaseStatus.toLowerCase(), 1, REQUESTED_UNTIL);
			outcomes(fixture, key, key, 0, 0, 1, 0, 0, 0, 0);
			StepExecution step = fixture.stepExecution();
			step.getExecutionContext().putLong("phase-checkpoint", 0);
			jobRepository.updateExecutionContext(step);

			transaction.executeWithoutResult(status -> {
				entityManager.createQuery("""
						update VerifyExecution execution set execution.businessStatus = 'FAILED'
						where execution.id = :executionId
						""").setParameter("executionId", fixture.executionId()).executeUpdate();
				entityManager.createQuery("""
						update SyncTargetWork target set target.title = 'rolled back'
						where target.doi = :doi
						""").setParameter("doi", "10.1000/restart-" + phaseStatus.toLowerCase()).executeUpdate();
				step.getExecutionContext().putLong("phase-checkpoint", 1);
				jobRepository.updateExecutionContext(step);
				status.setRollbackOnly();
			});

			assertThat(status(fixture)).isEqualTo(phaseStatus);
			assertThat(targetTitle("10.1000/restart-" + phaseStatus.toLowerCase())).isEqualTo("Title");
			assertThat(jobRepository.getStepExecution(step.getId()).getExecutionContext()
					.getLong("phase-checkpoint")).isZero();
			assertThat(watermark(fixture)).isEqualTo(BASE_WATERMARK);
			assertThat(requestId(fixture)).isEqualTo(fixture.requestId());

			jdbc.update("UPDATE sync_execution SET business_status = 'VERIFYING' WHERE id = ?",
					bytes(fixture.executionId()));
			VerificationResult restarted = verifyCommitted(fixture);
			assertThat(restarted.batchStatus()).isEqualTo(BatchStatus.COMPLETED);
			assertThat(requestId(fixture)).isEqualTo(fixture.requestId());
			assertThat(watermark(fixture)).isEqualTo(REQUESTED_UNTIL);
		}
	}

	private static JpaExecutionVerifier verifier() {
		return new JpaExecutionVerifier(entityManager);
	}

	private static VerificationResult verifyCommitted(Fixture fixture) {
		return transaction.execute(status -> {
			VerificationResult result = verifier().verify(fixture.executionId(), fixture.sourceName());
			StepExecution step = fixture.stepExecution();
			step.setStatus(result.batchStatus());
			step.setExitStatus(result.exitStatus());
			jobRepository.update(step);
			JobExecution job = step.getJobExecution();
			job.setStatus(result.batchStatus());
			job.setExitStatus(result.exitStatus());
			jobRepository.update(job);
			return result;
		});
	}

	private static ReplayWork read(JpaErrorReplayReader reader) {
		try {
			return reader.read();
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static List<ReplayWork> readToEnd(JpaErrorReplayReader reader) {
		List<ReplayWork> works = new ArrayList<>();
		ReplayWork work;
		while ((work = read(reader)) != null) {
			works.add(work);
		}
		return works;
	}

	private static Fixture fixture(String status) {
		UUID executionId = UUID.randomUUID();
		String requestId = executionId.toString();
		String sourceName = "crossref-" + executionId;
		StepExecution step = createStepExecution();
		jdbc.update("""
				INSERT INTO sync_execution (
				  id, request_id, mode, sync_contract_hash, canonical_version, business_status,
				  batch_job_execution_id, indexed_from_utc, indexed_until_utc,
				  started_at, created_at, updated_at
				) VALUES (?, ?, 'INCREMENTAL', ?, 1, ?, ?, ?, ?, ?, ?, ?)
				""", bytes(executionId), requestId, "a".repeat(64), status,
				step.getJobExecutionId(), utc(BASE_WATERMARK), utc(REQUESTED_UNTIL),
				utc(BASE_WATERMARK), utc(BASE_WATERMARK), utc(BASE_WATERMARK));
		jdbc.update("""
				INSERT INTO sync_watermark (source_name, indexed_until_utc, execution_id, updated_at)
				VALUES (?, ?, ?, ?)
				""", sourceName, utc(BASE_WATERMARK), bytes(executionId), utc(BASE_WATERMARK));
		return new Fixture(executionId, requestId, sourceName, step, new AtomicInteger());
	}

	private static StepExecution createStepExecution() {
		String name = "task8-" + JOB_SEQUENCE.incrementAndGet();
		JobParameters parameters = new JobParameters();
		JobInstance instance = jobRepository.createJobInstance(name, parameters);
		JobExecution execution = jobRepository.createJobExecution(instance, parameters, new ExecutionContext());
		return jobRepository.createStepExecution("phase", execution);
	}

	private static long staging(Fixture fixture, String doi, int hash, int sequence, Instant indexedAt) {
		int executionSequence = fixture.sequence().incrementAndGet();
		jdbc.update("""
				INSERT INTO staging_work (
				  execution_id, execution_sequence, source_json, doi, title, publisher, work_type,
				  issued_date, issued_date_precision, url, authors_json, canonical_version,
				  content_hash, author_hash, indexed_at, collected_at
				) VALUES (?, ?, JSON_OBJECT('source', ?), ?, 'Title', 'Publisher', 'journal-article',
				          '2026-08-08', 3, ?, JSON_ARRAY(), 1, ?, ?, ?, ?)
				""", bytes(fixture.executionId()), executionSequence, sequence, doi,
				"https://doi.org/" + doi, hash(hash), hash(hash + 50), utc(indexedAt), utc(indexedAt));
		return jdbc.queryForObject("""
				SELECT staging_key FROM staging_work WHERE execution_id = ? AND execution_sequence = ?
				""", Long.class, bytes(fixture.executionId()), executionSequence);
	}

	private static void freeze(Fixture fixture, List<Long> keys) {
		jdbc.update("""
				UPDATE sync_execution SET expected_count = ?, staging_upper_bound = ? WHERE id = ?
				""", keys.size(), keys.getLast(), bytes(fixture.executionId()));
	}

	private static void target(String doi, int hash, Instant indexedAt) {
		jdbc.update("""
				INSERT INTO work (
				  id, doi, title, publisher, work_type, issued_date, issued_date_precision, url,
				  authors_json, canonical_version, content_hash, author_hash, source_indexed_at,
				  created_at, updated_at
				) VALUES (?, ?, 'Title', 'Publisher', 'journal-article', '2026-08-08', 3, ?,
				          JSON_ARRAY(), 1, ?, ?, ?, ?, ?)
				""", bytes(UUID.randomUUID()), doi, "https://doi.org/" + doi, hash(hash), hash(hash + 50),
				utc(indexedAt), utc(indexedAt), utc(indexedAt));
	}

	private static void outcomes(
			Fixture fixture,
			long firstKey,
			long lastKey,
			long inserted,
			long superseded,
			long noOp,
			long conflict,
			long indexAdvanced,
			long updated,
			long validation
	) {
		jdbc.update("""
				INSERT INTO sync_chunk_result (
				  id, execution_id, step_execution_id, chunk_sequence, first_staging_key, last_staging_key,
				  inserted_count, superseded_count, no_op_count, conflict_count,
				  index_advanced_count, updated_count, validation_error_count, created_at
				) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", bytes(UUID.randomUUID()), bytes(fixture.executionId()), fixture.stepExecution().getId(),
				firstKey, lastKey, inserted, superseded, noOp, conflict, indexAdvanced, updated, validation,
				utc(REQUESTED_UNTIL));
	}

	private static void error(Fixture fixture, long stagingKey, String type, String code) {
		jdbc.update("""
				INSERT INTO sync_error (
				  execution_id, staging_key, error_type, error_code, message, status, created_at
				) VALUES (?, ?, ?, ?, 'fixture', 'OPEN', ?)
				""", bytes(fixture.executionId()), stagingKey, type, code, utc(REQUESTED_UNTIL));
	}

	private static String status(Fixture fixture) {
		return jdbc.queryForObject("SELECT business_status FROM sync_execution WHERE id = ?",
				String.class, bytes(fixture.executionId()));
	}

	private static String requestId(Fixture fixture) {
		return jdbc.queryForObject("SELECT request_id FROM sync_execution WHERE id = ?",
				String.class, bytes(fixture.executionId()));
	}

	private static Instant watermark(Fixture fixture) {
		LocalDateTime value = jdbc.queryForObject("""
				SELECT indexed_until_utc FROM sync_watermark WHERE source_name = ?
				""", LocalDateTime.class, fixture.sourceName());
		return value.toInstant(ZoneOffset.UTC);
	}

	private static long openErrors(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM sync_error WHERE execution_id = ? AND status = 'OPEN'
				""", Long.class, bytes(fixture.executionId()));
	}

	private static String targetTitle(String doi) {
		return jdbc.queryForObject("SELECT title FROM work WHERE doi = ?", String.class, doi);
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

	private record Fixture(
			UUID executionId,
			String requestId,
			String sourceName,
			StepExecution stepExecution,
			AtomicInteger sequence
	) {
	}
}
