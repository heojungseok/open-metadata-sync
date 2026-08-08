package com.heojungseok.openmetadatasync.batch.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;

import static org.assertj.core.api.Assertions.assertThat;

class BatchLifecycleLoggingListenerTest {

	@Test
	void logsJobAndStepLifecycleWithOnlyApprovedIdentityAndCounters() {
		List<String> logs = new ArrayList<>();
		BatchLifecycleLoggingListener listener = new BatchLifecycleLoggingListener(
				Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC), logs::add
		);
		JobExecution job = job();
		StepExecution step = step(job);
		step.setCommitCount(3);
		step.setReadCount(12);
		step.setWriteCount(10);

		listener.beforeJob(job);
		listener.beforeStep(step);
		step.setStatus(BatchStatus.COMPLETED);
		listener.afterStep(step);
		job.setStatus(BatchStatus.COMPLETED);
		listener.afterJob(job);

		assertThat(logs).containsExactly(
				"BATCH_JOB_START job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11",
				"BATCH_STEP_START job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21",
				"BATCH_STEP_END job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21 status=COMPLETED commitCount=3 readCount=12 writeCount=10",
				"BATCH_JOB_END job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 status=COMPLETED"
		);
		assertThat(String.join("\n", logs)).doesNotContain("password", "secret", "token");
	}

	@Test
	void throttlesNormalChunkProgressToOneHundredChunksOrSixtySeconds() {
		List<String> logs = new ArrayList<>();
		MutableClock clock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
		BatchLifecycleLoggingListener listener = new BatchLifecycleLoggingListener(clock, logs::add);
		StepExecution step = step(job());
		listener.beforeStep(step);

		for (int chunk = 1; chunk < 100; chunk++) {
			step.setCommitCount(chunk);
			step.setReadCount(chunk * 10L);
			step.setWriteCount(chunk * 10L);
			listener.afterChunk(new Chunk<>());
		}
		assertThat(logs).noneMatch(log -> log.startsWith("BATCH_CHUNK_PROGRESS"));

		step.setCommitCount(100);
		listener.afterChunk(new Chunk<>());
		clock.advance(Duration.ofSeconds(60));
		step.setCommitCount(101);
		listener.afterChunk(new Chunk<>());

		assertThat(logs).filteredOn(log -> log.startsWith("BATCH_CHUNK_PROGRESS")).containsExactly(
				"BATCH_CHUNK_PROGRESS job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21 commitCount=100 readCount=990 writeCount=990",
				"BATCH_CHUNK_PROGRESS job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21 commitCount=101 readCount=990 writeCount=990"
		);
	}

	@Test
	void logsChunkFailureImmediatelyWithRestartCoordinates() {
		List<String> logs = new ArrayList<>();
		BatchLifecycleLoggingListener listener = new BatchLifecycleLoggingListener(
				Clock.systemUTC(), logs::add
		);
		StepExecution step = step(job());
		step.setCommitCount(4);
		step.setReadCount(50);
		step.setWriteCount(40);
		listener.beforeStep(step);
		logs.clear();

		listener.beforeChunk(new Chunk<>());
		listener.onChunkError(new IllegalStateException("write failed"), new Chunk<>());

		assertThat(logs).containsExactly(
				"BATCH_CHUNK_ERROR job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21 attemptedChunk=5 lastCommittedChunk=4 restartFromChunk=5 commitCount=4 readCount=50 writeCount=40 error=IllegalStateException"
		);
	}

	@Test
	void logsStepAndJobFailureTypesWithoutExceptionMessages() {
		List<String> logs = new ArrayList<>();
		BatchLifecycleLoggingListener listener = new BatchLifecycleLoggingListener(Clock.systemUTC(), logs::add);
		JobExecution job = job();
		StepExecution step = step(job);
		step.setStatus(BatchStatus.FAILED);
		step.addFailureException(new IllegalArgumentException("secret details"));
		job.setStatus(BatchStatus.FAILED);
		job.addFailureException(new IllegalStateException("secret details"));

		listener.afterStep(step);
		listener.afterJob(job);

		assertThat(logs).contains(
				"BATCH_STEP_FAILURE job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21 status=FAILED error=IllegalArgumentException commitCount=0 readCount=0 writeCount=0",
				"BATCH_JOB_FAILURE job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 status=FAILED error=IllegalStateException"
		);
		assertThat(String.join("\n", logs)).doesNotContain("secret details");
	}

	private static JobExecution job() {
		return new JobExecution(11, new JobInstance(7, "crossrefSyncJob"), new JobParametersBuilder()
				.addString("requestId", "req-10", true)
				.addString("mode", "BACKFILL", true)
				.addString("dbPassword", "must-not-log", false)
				.toJobParameters());
	}

	private static StepExecution step(JobExecution job) {
		return new StepExecution(21, "sync", job);
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
