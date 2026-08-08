package com.heojungseok.openmetadatasync.batch;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.heojungseok.openmetadatasync.OpenMetadataSyncApplication;
import com.heojungseok.openmetadatasync.batch.collect.CrossrefCollector;
import com.heojungseok.openmetadatasync.batch.collect.JpaCollectStore;
import com.heojungseok.openmetadatasync.batch.execution.ExecutionStatus;
import com.heojungseok.openmetadatasync.batch.replay.JpaErrorReplayPreparer;
import com.heojungseok.openmetadatasync.batch.replay.JpaErrorReplayPreparerProbe;
import com.heojungseok.openmetadatasync.batch.sync.ChunkAwareJpaWorkWriter;
import com.heojungseok.openmetadatasync.batch.sync.JpaKeysetWorkReader;
import com.heojungseok.openmetadatasync.batch.sync.SyncWorkDto;
import com.heojungseok.openmetadatasync.batch.verify.JpaExecutionVerifier;
import com.heojungseok.openmetadatasync.batch.verify.VerificationResult;
import com.heojungseok.openmetadatasync.crossref.CrossrefPage;

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
		outcomes(outcomeMismatch, outcomeKey, outcomeKey, 1, 0, 0, 0, 0, 0, 0);
		jdbc.update("UPDATE sync_execution SET expected_count = 2 WHERE id = ?",
				bytes(outcomeMismatch.executionId()));

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
	void rejectsBalancedOmissionDuplicateReversedAndChunkSequenceGap() {
		Fixture balanced = fixture("VERIFYING");
		List<Long> balancedKeys = fourStagingWithTargets(balanced, "balanced");
		freeze(balanced, balancedKeys);
		outcomes(balanced, 1, balancedKeys.get(0), balancedKeys.get(0), 2, 0, 0, 0, 0, 0, 0);
		outcomes(balanced, 2, balancedKeys.get(2), balancedKeys.get(3), 2, 0, 0, 0, 0, 0, 0);

		Fixture duplicate = fixture("VERIFYING");
		List<Long> duplicateKeys = twoStagingWithTargets(duplicate, "duplicate-range");
		freeze(duplicate, duplicateKeys);
		outcomes(duplicate, 1, duplicateKeys.getFirst(), duplicateKeys.getFirst(), 1, 0, 0, 0, 0, 0, 0);
		outcomes(duplicate, 2, duplicateKeys.getFirst(), duplicateKeys.getFirst(), 1, 0, 0, 0, 0, 0, 0);

		Fixture reversed = fixture("VERIFYING");
		List<Long> reversedKeys = twoStagingWithTargets(reversed, "reversed-range");
		freeze(reversed, reversedKeys);
		outcomes(reversed, 1, reversedKeys.getLast(), reversedKeys.getFirst(), 2, 0, 0, 0, 0, 0, 0);

		Fixture sequenceGap = fixture("VERIFYING");
		List<Long> gapKeys = twoStagingWithTargets(sequenceGap, "sequence-gap");
		freeze(sequenceGap, gapKeys);
		outcomes(sequenceGap, 1, gapKeys.getFirst(), gapKeys.getFirst(), 1, 0, 0, 0, 0, 0, 0);
		outcomes(sequenceGap, 3, gapKeys.getLast(), gapKeys.getLast(), 1, 0, 0, 0, 0, 0, 0);

		for (Fixture fixture : List.of(balanced, duplicate, reversed, sequenceGap)) {
			VerificationResult result = verifyCommitted(fixture);

			assertThat(result.batchStatus()).isEqualTo(BatchStatus.FAILED);
			assertThat(result.exitStatus().getExitDescription()).contains("chunk coverage");
			assertThat(watermark(fixture)).isEqualTo(BASE_WATERMARK);
		}
	}

	@Test
	void boundedChunkCoverageCountsOnlyTheExecutionAcrossForeignStagingKeyGaps() {
		Fixture fixture = fixture("VERIFYING");
		long first = staging(fixture, "10.1000/foreign-gap-1", 1, 1, REQUESTED_UNTIL.minusSeconds(1));
		Fixture foreign = fixture("FAILED");
		staging(foreign, "10.1000/foreign-row", 9, 1, REQUESTED_UNTIL);
		long last = staging(fixture, "10.1000/foreign-gap-2", 2, 2, REQUESTED_UNTIL);
		freeze(fixture, List.of(first, last));
		target("10.1000/foreign-gap-1", 1, REQUESTED_UNTIL.minusSeconds(1));
		target("10.1000/foreign-gap-2", 2, REQUESTED_UNTIL);
		outcomes(fixture, first, last, 2, 0, 0, 0, 0, 0, 0);

		VerificationResult result = verifyCommitted(fixture);

		assertThat(result.batchStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(result.eligibleCount()).isEqualTo(2);
		assertThat(result.accountedCount()).isEqualTo(2);
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
	void unknownOpenErrorTypeFailsClosedWithoutAdvancingWatermark() {
		Fixture fixture = fixture("VERIFYING");
		long key = staging(fixture, "10.1000/unknown-error", 1, 1, REQUESTED_UNTIL);
		freeze(fixture, List.of(key));
		error(fixture, key, "UNKNOWN", "UNCLASSIFIED");
		outcomes(fixture, key, key, 0, 0, 0, 0, 0, 0, 1);

		VerificationResult result = verifyCommitted(fixture);

		assertThat(result.batchStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(result.exitStatus().getExitDescription()).contains("Unknown OPEN error type");
		assertThat(status(fixture)).isEqualTo("FAILED");
		assertThat(watermark(fixture)).isEqualTo(BASE_WATERMARK);
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
	void onlyIncrementalAdvancesWatermarkWhileBackfillAndReplayPreserveItAcrossAllOutcomes() {
		for (String mode : List.of("BACKFILL", "REPLAY_ERRORS")) {
			Fixture completed = fixture("VERIFYING", mode, null);
			long completedKey = staging(
					completed, "10.1000/mode-completed-" + mode.toLowerCase(), 1, 1, REQUESTED_UNTIL
			);
			freeze(completed, List.of(completedKey));
			target("10.1000/mode-completed-" + mode.toLowerCase(), 1, REQUESTED_UNTIL);
			outcomes(completed, completedKey, completedKey, 0, 0, 1, 0, 0, 0, 0);

			Fixture validation = fixture("VERIFYING", mode, null);
			long validationKey = staging(
					validation, "10.1000/mode-validation-" + mode.toLowerCase(), 2, 1, REQUESTED_UNTIL
			);
			freeze(validation, List.of(validationKey));
			error(validation, validationKey, "VALIDATION", "MODE_VALIDATION");
			outcomes(validation, validationKey, validationKey, 0, 0, 0, 0, 0, 0, 1);

			Fixture conflict = fixture("VERIFYING", mode, null);
			long conflictKey = staging(
					conflict, "10.1000/mode-conflict-" + mode.toLowerCase(), 3, 1, REQUESTED_UNTIL
			);
			freeze(conflict, List.of(conflictKey));
			error(conflict, conflictKey, "CONFLICT", "MODE_CONFLICT");
			outcomes(conflict, conflictKey, conflictKey, 0, 0, 0, 1, 0, 0, 0);

			Fixture technical = fixture("VERIFYING", mode, null);
			long technicalKey = staging(
					technical, "10.1000/mode-technical-" + mode.toLowerCase(), 4, 1, REQUESTED_UNTIL
			);
			freeze(technical, List.of(technicalKey));
			target("10.1000/mode-technical-" + mode.toLowerCase(), 4, REQUESTED_UNTIL);
			outcomes(technical, technicalKey, technicalKey, 0, 0, 0, 0, 0, 0, 0);

			assertThat(verifyCommitted(completed).businessStatus()).isEqualTo(ExecutionStatus.COMPLETED);
			assertThat(verifyCommitted(validation).businessStatus())
					.isEqualTo(ExecutionStatus.COMPLETED_WITH_ERRORS);
			assertThat(verifyCommitted(conflict).businessStatus()).isEqualTo(ExecutionStatus.FAILED);
			assertThat(verifyCommitted(technical).businessStatus()).isEqualTo(ExecutionStatus.FAILED);
			for (Fixture fixture : List.of(completed, validation, conflict, technical)) {
				assertThat(watermark(fixture)).isEqualTo(BASE_WATERMARK);
			}
		}
	}

	@Test
	void copiesAnImmutableOpenErrorSnapshotToReplayStagingDespiteLateAndStatusMutations() {
		Fixture source = fixture("FAILED");
		List<Long> sourceKeys = List.of(
				staging(source, "10.1000/replay-1", 1, 1, REQUESTED_UNTIL.minusSeconds(2)),
				staging(source, "10.1000/replay-2", 2, 2, REQUESTED_UNTIL.minusSeconds(1)),
				staging(source, "10.1000/replay-3", 3, 3, REQUESTED_UNTIL)
		);
		freeze(source, sourceKeys);
		List<Long> initialErrors = new ArrayList<>();
		for (long key : sourceKeys) {
			initialErrors.add(error(source, key, "VALIDATION", "REPLAYABLE"));
		}
		long newlyOpenKey = staging(source, "10.1000/replay-newly-open", 4, 4, REQUESTED_UNTIL.plusSeconds(1));
		long newlyOpenError = error(source, newlyOpenKey, "VALIDATION", "NEWLY_OPEN");
		jdbc.update("UPDATE sync_error SET status = 'RESOLVED' WHERE error_key = ?", newlyOpenError);
		long lateKey = staging(source, "10.1000/replay-late", 5, 5, REQUESTED_UNTIL.plusSeconds(2));
		Fixture replay = replayFixture(source);
		JpaErrorReplayPreparer preparer = JpaErrorReplayPreparerProbe.afterSnapshot(
				entityManager,
				() -> mutateErrorsAfterSnapshot(
						source, initialErrors.getFirst(), newlyOpenError, lateKey
				)
		);

		JpaErrorReplayPreparer.Prepared prepared = transaction.execute(status -> preparer.prepare(
				replay.executionId(), source.executionId()
		));

		assertThat(prepared.expectedCount()).isEqualTo(3);
		assertThat(prepared.errorUpperBound()).isLessThan(newlyOpenError);
		assertThat(jdbc.queryForList("""
				SELECT doi FROM staging_work WHERE execution_id = ? ORDER BY execution_sequence
				""", String.class, bytes(replay.executionId()))).containsExactly(
					"10.1000/replay-1", "10.1000/replay-2", "10.1000/replay-3"
			);
		assertThat(jdbc.queryForObject("""
				SELECT expected_count FROM sync_execution WHERE id = ?
				""", Long.class, bytes(replay.executionId()))).isEqualTo(3);
		JpaKeysetWorkReader reader = new JpaKeysetWorkReader(
				entityManager, replay.executionId(), prepared.stagingUpperBound(), 2
		);
		reader.open(new ExecutionContext());
		assertThat(readToEnd(reader)).extracting(SyncWorkDto::doi).containsExactly(
				"10.1000/replay-1", "10.1000/replay-2", "10.1000/replay-3"
		);
	}

	@Test
	void failedReplayPrepareRollsBackThenCopiedReplayReaderRestartsAtTheDurableChunkCheckpoint() {
		Fixture source = fixture("FAILED");
		List<Long> sourceKeys = List.of(
				staging(source, "10.1000/restart-replay-1", 1, 1, REQUESTED_UNTIL.minusSeconds(2)),
				staging(source, "10.1000/restart-replay-2", 2, 2, REQUESTED_UNTIL.minusSeconds(1)),
				staging(source, "10.1000/restart-replay-3", 3, 3, REQUESTED_UNTIL)
		);
		freeze(source, sourceKeys);
		for (long key : sourceKeys) {
			error(source, key, "VALIDATION", "RESTARTABLE");
		}
		Fixture replay = replayFixture(source);
		JpaErrorReplayPreparer failing = JpaErrorReplayPreparerProbe.afterSnapshot(
				entityManager, () -> { throw new IllegalStateException("prepare failed"); }
		);

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.executeWithoutResult(
				status -> failing.prepare(replay.executionId(), source.executionId())
		)).isInstanceOf(IllegalStateException.class).hasMessage("prepare failed");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staging_work WHERE execution_id = ?",
				Long.class, bytes(replay.executionId()))).isZero();
		assertThat(jdbc.queryForObject("SELECT expected_count FROM sync_execution WHERE id = ?",
				Long.class, bytes(replay.executionId()))).isNull();

		JpaErrorReplayPreparer.Prepared prepared = transaction.execute(status ->
				new JpaErrorReplayPreparer(entityManager).prepare(replay.executionId(), source.executionId())
		);
		StepExecution step = replay.stepExecution();
		JpaKeysetWorkReader reader = new JpaKeysetWorkReader(
				entityManager, replay.executionId(), prepared.stagingUpperBound(), 2
		);
		reader.open(step.getExecutionContext());
		SyncWorkDto first = read(reader);
		transaction.executeWithoutResult(status -> {
			reader.update(step.getExecutionContext());
			jobRepository.updateExecutionContext(step);
		});
		SyncWorkDto rolledBack = transaction.execute(status -> {
			SyncWorkDto work = read(reader);
			reader.update(step.getExecutionContext());
			jobRepository.updateExecutionContext(step);
			status.setRollbackOnly();
			return work;
		});

		StepExecution durable = jobRepository.getStepExecution(step.getId());
		reader.close();
		reader.open(durable.getExecutionContext());
		List<SyncWorkDto> restarted = readToEnd(reader);

		assertThat(first.doi()).isEqualTo("10.1000/restart-replay-1");
		assertThat(rolledBack.doi()).isEqualTo("10.1000/restart-replay-2");
		assertThat(restarted).extracting(SyncWorkDto::doi).containsExactly(
				"10.1000/restart-replay-2", "10.1000/restart-replay-3"
		).doesNotHaveDuplicates();
	}

	@Test
	void actualCollectStoreAndCheckpointRollbackReplayOnceOnANewStep() {
		Fixture fixture = fixture("COLLECTING");
		UUID windowId = window(fixture);
		StepExecution failedStep = fixture.stepExecution();
		initializeCheckpoint(failedStep, "collect-checkpoint");
		JpaCollectStore store = context.getBean(JpaCollectStore.class);
		CrossrefCollector.PageWrite page = new CrossrefCollector.PageWrite(
				fixture.executionId(), windowId, "*", "next", 1, 1, 0,
				List.of(crossrefWork("10.1000/actual-collect")), REQUESTED_UNTIL
		);

		transaction.executeWithoutResult(status -> {
			store.persist(page, CrossrefCollector.Completion.PAGE);
			advanceCheckpoint(failedStep, "collect-checkpoint", 1);
			status.setRollbackOnly();
		});

		assertThat(stagingCount(fixture)).isZero();
		assertThat(windowCount(windowId)).isZero();
		assertThat(durableCheckpoint(failedStep, "collect-checkpoint")).isZero();
		StepExecution restart = restartStep(fixture, "collect-restart", failedStep);
		transaction.executeWithoutResult(status -> {
			store.persist(page, CrossrefCollector.Completion.PAGE);
			advanceCheckpoint(restart, "collect-checkpoint", 1);
		});

		assertThat(stagingCount(fixture)).isEqualTo(1);
		assertThat(windowCount(windowId)).isEqualTo(1);
		assertThat(durableCheckpoint(restart, "collect-checkpoint")).isEqualTo(1);
		assertThat(requestId(fixture)).isEqualTo(fixture.requestId());
		assertThat(watermark(fixture)).isEqualTo(BASE_WATERMARK);
	}

	@Test
	void actualSyncWriterAndCheckpointRollbackReplayOutcomesOnceOnANewStep() {
		Fixture fixture = fixture("SYNCING");
		SyncWorkDto inserted = syncWork(fixture, "10.1000/actual-sync-insert", 1, REQUESTED_UNTIL);
		SyncWorkDto conflict = syncWork(fixture, "10.1000/actual-sync-conflict", 2, REQUESTED_UNTIL);
		freeze(fixture, List.of(inserted.stagingKey(), conflict.stagingKey()));
		target(conflict.doi(), 3, REQUESTED_UNTIL);
		StepExecution failedStep = fixture.stepExecution();
		initializeCheckpoint(failedStep, "sync-checkpoint");
		ChunkAwareJpaWorkWriter failedWriter = new ChunkAwareJpaWorkWriter(
				entityManager, fixture.executionId(), failedStep.getId()
		);

		transaction.executeWithoutResult(status -> {
			failedWriter.write(new Chunk<>(List.of(inserted, conflict)));
			advanceCheckpoint(failedStep, "sync-checkpoint", conflict.stagingKey());
			status.setRollbackOnly();
		});

		assertThat(targetCount(inserted.doi())).isZero();
		assertThat(openErrors(fixture)).isZero();
		assertThat(chunkResultCount(fixture)).isZero();
		assertThat(durableCheckpoint(failedStep, "sync-checkpoint")).isZero();
		StepExecution restart = restartStep(fixture, "sync-restart", failedStep);
		ChunkAwareJpaWorkWriter restartedWriter = new ChunkAwareJpaWorkWriter(
				entityManager, fixture.executionId(), restart.getId()
		);
		transaction.executeWithoutResult(status -> {
			restartedWriter.write(new Chunk<>(List.of(inserted, conflict)));
			advanceCheckpoint(restart, "sync-checkpoint", conflict.stagingKey());
		});

		assertThat(targetCount(inserted.doi())).isEqualTo(1);
		assertThat(openErrors(fixture)).isEqualTo(1);
		assertThat(chunkResultCount(fixture)).isEqualTo(1);
		assertThat(durableCheckpoint(restart, "sync-checkpoint")).isEqualTo(conflict.stagingKey());
		assertThat(requestId(fixture)).isEqualTo(fixture.requestId());
		assertThat(watermark(fixture)).isEqualTo(BASE_WATERMARK);
	}

	@Test
	void actualVerifierAndCheckpointRollbackThenCompleteOnceOnANewStep() {
		Fixture fixture = fixture("VERIFYING");
		long key = staging(fixture, "10.1000/actual-verify", 1, 1, REQUESTED_UNTIL);
		freeze(fixture, List.of(key));
		target("10.1000/actual-verify", 1, REQUESTED_UNTIL);
		outcomes(fixture, key, key, 0, 0, 1, 0, 0, 0, 0);
		StepExecution failedStep = fixture.stepExecution();
		initializeCheckpoint(failedStep, "verify-checkpoint");

		transaction.executeWithoutResult(status -> {
			verifier().verify(fixture.executionId(), fixture.sourceName());
			advanceCheckpoint(failedStep, "verify-checkpoint", key);
			status.setRollbackOnly();
		});

		assertThat(status(fixture)).isEqualTo("VERIFYING");
		assertThat(watermark(fixture)).isEqualTo(BASE_WATERMARK);
		assertThat(durableCheckpoint(failedStep, "verify-checkpoint")).isZero();
		StepExecution restart = restartStep(fixture, "verify-restart", failedStep);
		VerificationResult result = verifyCommitted(fixture, restart, "verify-checkpoint", key);

		assertThat(result.batchStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(status(fixture)).isEqualTo("COMPLETED");
		assertThat(watermark(fixture)).isEqualTo(REQUESTED_UNTIL);
		assertThat(durableCheckpoint(restart, "verify-checkpoint")).isEqualTo(key);
		assertThat(requestId(fixture)).isEqualTo(fixture.requestId());
	}

	private static JpaExecutionVerifier verifier() {
		return new JpaExecutionVerifier(entityManager);
	}

	private static VerificationResult verifyCommitted(Fixture fixture) {
		return verifyCommitted(fixture, fixture.stepExecution());
	}

	private static VerificationResult verifyCommitted(Fixture fixture, StepExecution step) {
		return verifyCommitted(fixture, step, null, 0);
	}

	private static VerificationResult verifyCommitted(
			Fixture fixture,
			StepExecution step,
			String checkpointKey,
			long checkpointValue
	) {
		return transaction.execute(status -> {
			VerificationResult result = verifier().verify(fixture.executionId(), fixture.sourceName());
			step.setStatus(result.batchStatus());
			step.setExitStatus(result.exitStatus());
			jobRepository.update(step);
			JobExecution job = step.getJobExecution();
			job.setStatus(result.batchStatus());
			job.setExitStatus(result.exitStatus());
			jobRepository.update(job);
			if (checkpointKey != null) {
				advanceCheckpoint(step, checkpointKey, checkpointValue);
			}
			return result;
		});
	}

	private static SyncWorkDto read(JpaKeysetWorkReader reader) {
		try {
			return reader.read();
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static List<SyncWorkDto> readToEnd(JpaKeysetWorkReader reader) {
		List<SyncWorkDto> works = new ArrayList<>();
		SyncWorkDto work;
		while ((work = read(reader)) != null) {
			works.add(work);
		}
		return works;
	}

	private static void initializeCheckpoint(StepExecution step, String key) {
		step.getExecutionContext().putLong(key, 0);
		jobRepository.updateExecutionContext(step);
	}

	private static void advanceCheckpoint(StepExecution step, String key, long value) {
		step.getExecutionContext().putLong(key, value);
		jobRepository.updateExecutionContext(step);
	}

	private static long durableCheckpoint(StepExecution step, String key) {
		return jobRepository.getStepExecution(step.getId()).getExecutionContext().getLong(key);
	}

	private static StepExecution restartStep(Fixture fixture, String name, StepExecution failedStep) {
		StepExecution restart = jobRepository.createStepExecution(name, fixture.stepExecution().getJobExecution());
		restart.setExecutionContext(new ExecutionContext(
				jobRepository.getStepExecution(failedStep.getId()).getExecutionContext()
		));
		jobRepository.updateExecutionContext(restart);
		return restart;
	}

	private static Fixture fixture(String status) {
		return fixture(status, "INCREMENTAL", REQUESTED_UNTIL);
	}

	private static Fixture fixture(String status, String mode, Instant indexedUntil) {
		UUID executionId = UUID.randomUUID();
		String requestId = executionId.toString();
		String sourceName = "crossref-" + executionId;
		StepExecution step = createStepExecution();
		jdbc.update("""
				INSERT INTO sync_execution (
				  id, request_id, mode, sync_contract_hash, canonical_version, business_status,
				  batch_job_execution_id, indexed_from_utc, indexed_until_utc,
				  started_at, created_at, updated_at
				) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
				""", bytes(executionId), requestId, mode, "a".repeat(64), status,
				step.getJobExecutionId(), utc(BASE_WATERMARK), indexedUntil == null ? null : utc(indexedUntil),
				utc(BASE_WATERMARK), utc(BASE_WATERMARK), utc(BASE_WATERMARK));
		jdbc.update("""
				INSERT INTO sync_watermark (source_name, indexed_until_utc, execution_id, updated_at)
				VALUES (?, ?, ?, ?)
				""", sourceName, utc(BASE_WATERMARK), bytes(executionId), utc(BASE_WATERMARK));
		return new Fixture(executionId, requestId, sourceName, step, new AtomicInteger());
	}

	private static Fixture replayFixture(Fixture source) {
		Fixture replay = fixture("PREPARING");
		jdbc.update("""
				UPDATE sync_execution SET mode = 'REPLAY_ERRORS', source_execution_id = ? WHERE id = ?
				""", bytes(source.executionId()), bytes(replay.executionId()));
		return replay;
	}

	private static UUID window(Fixture fixture) {
		UUID windowId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO sync_window (
				  id, execution_id, window_sequence, cursor_value, collected_count,
				  status, created_at, updated_at
				) VALUES (?, ?, 0, '*', 0, 'COLLECTING', ?, ?)
				""", bytes(windowId), bytes(fixture.executionId()), utc(BASE_WATERMARK), utc(BASE_WATERMARK));
		return windowId;
	}

	private static CrossrefPage.Work crossrefWork(String doi) {
		return new CrossrefPage.Work(
				doi, List.of("Title"), "Publisher", "journal-article",
				new CrossrefPage.DateParts(List.of(List.of(2026, 8, 8))),
				"https://doi.org/" + doi, List.of(),
				new CrossrefPage.Timestamp(REQUESTED_UNTIL.toString()), null
		);
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

	private static SyncWorkDto syncWork(Fixture fixture, String doi, int hashValue, Instant indexedAt) {
		long stagingKey = staging(fixture, doi, hashValue, hashValue, indexedAt);
		return new SyncWorkDto(
				stagingKey, doi, "Title", "Publisher", "journal-article", "2026-08-08", (byte) 3,
				"https://doi.org/" + doi, "[]", 1, hash(hashValue), hash(hashValue + 50), indexedAt
		);
	}

	private static List<Long> fourStagingWithTargets(Fixture fixture, String prefix) {
		return stagingWithTargets(fixture, prefix, 4);
	}

	private static List<Long> twoStagingWithTargets(Fixture fixture, String prefix) {
		return stagingWithTargets(fixture, prefix, 2);
	}

	private static List<Long> stagingWithTargets(Fixture fixture, String prefix, int count) {
		List<Long> keys = new ArrayList<>();
		for (int index = 1; index <= count; index++) {
			String doi = "10.1000/" + prefix + "-" + fixture.executionId() + "-" + index;
			keys.add(staging(fixture, doi, index, index, REQUESTED_UNTIL.plusNanos(index)));
			target(doi, index, REQUESTED_UNTIL.plusNanos(index));
		}
		return keys;
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
		outcomes(
				fixture, 1, firstKey, lastKey, inserted, superseded, noOp,
				conflict, indexAdvanced, updated, validation
		);
	}

	private static void outcomes(
			Fixture fixture,
			long chunkSequence,
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
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", bytes(UUID.randomUUID()), bytes(fixture.executionId()), fixture.stepExecution().getId(),
				chunkSequence, firstKey, lastKey, inserted, superseded, noOp, conflict, indexAdvanced, updated, validation,
				utc(REQUESTED_UNTIL));
	}

	private static long error(Fixture fixture, long stagingKey, String type, String code) {
		jdbc.update("""
				INSERT INTO sync_error (
				  execution_id, staging_key, error_type, error_code, message, status, created_at
				) VALUES (?, ?, ?, ?, 'fixture', 'OPEN', ?)
				""", bytes(fixture.executionId()), stagingKey, type, code, utc(REQUESTED_UNTIL));
		return jdbc.queryForObject("""
				SELECT error_key FROM sync_error
				WHERE execution_id = ? AND staging_key = ? AND error_code = ?
				""", Long.class, bytes(fixture.executionId()), stagingKey, code);
	}

	private static void mutateErrorsAfterSnapshot(
			Fixture source,
			long resolvedAfterSnapshot,
			long openedAfterSnapshot,
			long lateStagingKey
	) {
		String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/open_metadata";
		try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword())) {
			try (PreparedStatement statement = connection.prepareStatement(
					"UPDATE sync_error SET status = ? WHERE error_key = ?")) {
				statement.setString(1, "RESOLVED");
				statement.setLong(2, resolvedAfterSnapshot);
				statement.executeUpdate();
				statement.setString(1, "OPEN");
				statement.setLong(2, openedAfterSnapshot);
				statement.executeUpdate();
			}
			try (PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO sync_error (
					  execution_id, staging_key, error_type, error_code, message, status, created_at
					) VALUES (?, ?, 'VALIDATION', 'LATE', 'late', 'OPEN', UTC_TIMESTAMP(6))
					""")) {
				statement.setBytes(1, bytes(source.executionId()));
				statement.setLong(2, lateStagingKey);
				statement.executeUpdate();
			}
		} catch (SQLException exception) {
			throw new IllegalStateException(exception);
		}
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

	private static long stagingCount(Fixture fixture) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM staging_work WHERE execution_id = ?",
				Long.class, bytes(fixture.executionId()));
	}

	private static long windowCount(UUID windowId) {
		return jdbc.queryForObject("SELECT collected_count FROM sync_window WHERE id = ?",
				Long.class, bytes(windowId));
	}

	private static long targetCount(String doi) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM work WHERE doi = ?", Long.class, doi);
	}

	private static long chunkResultCount(Fixture fixture) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM sync_chunk_result WHERE execution_id = ?",
				Long.class, bytes(fixture.executionId()));
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
