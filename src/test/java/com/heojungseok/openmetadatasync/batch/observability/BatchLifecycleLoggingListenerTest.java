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
import org.springframework.batch.core.ExitStatus;
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
		StepExecution prepare = step(22, "prepareCrossrefExecution", job);
		prepare.setCommitCount(1);
		job.addStepExecution(prepare);
		StepExecution sync = step(21, "sync", job);
		sync.setCommitCount(3);
		sync.setReadCount(12);
		sync.setWriteCount(10);
		sync.setFilterCount(2);
		sync.setRollbackCount(1);
		sync.setReadSkipCount(1);
		sync.setProcessSkipCount(2);
		sync.setWriteSkipCount(3);
		job.addStepExecution(sync);

		listener.beforeJob(job);
		listener.beforeStep(sync);
		sync.setStatus(BatchStatus.COMPLETED);
		listener.afterStep(sync);
		job.setStatus(BatchStatus.COMPLETED);
		listener.afterJob(job);

		assertThat(logs).containsExactly(
				"BATCH_JOB_START [배치 시작] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11",
				"BATCH_STEP_START [단계 시작] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21",
				"BATCH_STEP_END [sync 단계 종료] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21 | status=COMPLETED | 읽음=12 | 저장=10 | 걸러냄=2 | 커밋=3 | 롤백=1 | 스킵=6",
				"BATCH_JOB_END [배치 종료] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 | status=COMPLETED | 읽음=12 | 저장=10 | 걸러냄=2 | 커밋=4 | 롤백=1 | 스킵=6"
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

		step.setCommitCount(0);
		listener.beforeChunk(new Chunk<>());
		listener.afterChunk(new Chunk<>());
		step.setCommitCount(99);
		step.setReadCount(990);
		step.setWriteCount(990);
		listener.beforeChunk(new Chunk<>());
		listener.afterChunk(new Chunk<>());
		assertThat(logs).noneMatch(log -> log.startsWith("BATCH_CHUNK_PROGRESS"));

		step.setCommitCount(100);
		step.setReadCount(1000);
		step.setWriteCount(1000);
		listener.beforeChunk(new Chunk<>());
		listener.afterChunk(new Chunk<>());
		clock.advance(Duration.ofSeconds(60));
		step.setCommitCount(101);
		step.setReadCount(1010);
		step.setWriteCount(1010);
		listener.beforeChunk(new Chunk<>());
		listener.afterChunk(new Chunk<>());

		assertThat(logs).filteredOn(log -> log.startsWith("BATCH_CHUNK_PROGRESS")).containsExactly(
				"BATCH_CHUNK_PROGRESS [sync 진행] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21 | 읽음=1000 | 저장=1000 | 걸러냄=0 | 커밋=100 | 롤백=0 | 스킵=0",
				"BATCH_CHUNK_PROGRESS [sync 진행] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21 | 읽음=1010 | 저장=1010 | 걸러냄=0 | 커밋=101 | 롤백=0 | 스킵=0"
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
				"BATCH_CHUNK_ERROR [sync 청크 실패] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21 | attemptedChunk=5 | lastCommittedChunk=4 | restartFromChunk=5 | 읽음=50 | 저장=40 | 걸러냄=0 | 커밋=4 | 롤백=0 | 스킵=0 | error=IllegalStateException"
		);
	}

	@Test
	void logsStepAndJobFailureTypesWithoutExceptionMessages() {
		List<String> logs = new ArrayList<>();
		BatchLifecycleLoggingListener listener = new BatchLifecycleLoggingListener(Clock.systemUTC(), logs::add);
		JobExecution job = job();
		StepExecution step = step(job);
		step.setStatus(BatchStatus.FAILED);
		step.setExitStatus(ExitStatus.FAILED.addExitDescription("secret details"));
		step.addFailureException(new IllegalArgumentException("secret details"));
		job.setStatus(BatchStatus.FAILED);
		job.setExitStatus(ExitStatus.FAILED.addExitDescription("secret details"));
		job.addFailureException(new IllegalStateException("secret details"));

		listener.afterStep(step);
		listener.afterJob(job);

		assertThat(logs).contains(
				"BATCH_STEP_FAILURE [sync 단계 실패] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=sync stepExecutionId=21 | status=FAILED | exitCode=FAILED | reason=TECHNICAL_EXCEPTION | error=IllegalArgumentException | 읽음=0 | 저장=0 | 걸러냄=0 | 커밋=0 | 롤백=0 | 스킵=0",
				"BATCH_JOB_FAILURE [배치 실패] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 | status=FAILED | exitCode=FAILED | reason=TECHNICAL_EXCEPTION | error=IllegalStateException"
		);
		assertThat(String.join("\n", logs)).doesNotContain("secret details");
	}

	@Test
	void logsBusinessFailureReasonForOperatorAction() {
		List<String> logs = new ArrayList<>();
		BatchLifecycleLoggingListener listener = new BatchLifecycleLoggingListener(Clock.systemUTC(), logs::add);
		JobExecution job = job();
		StepExecution verify = step(23, "verify", job);
		verify.setStatus(BatchStatus.FAILED);
		verify.setExitStatus(ExitStatus.FAILED.addExitDescription("Conflict remains OPEN\nsecret=details"));
		job.addStepExecution(verify);
		job.setStatus(BatchStatus.FAILED);
		job.setExitStatus(ExitStatus.FAILED);

		listener.afterStep(verify);
		listener.afterJob(job);

		assertThat(logs).contains(
				"BATCH_STEP_FAILURE [verify 단계 실패] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 step=verify stepExecutionId=23 | status=FAILED | exitCode=FAILED | reason=Conflict_remains_OPEN | error=Unknown | 읽음=0 | 저장=0 | 걸러냄=0 | 커밋=0 | 롤백=0 | 스킵=0",
				"BATCH_JOB_FAILURE [배치 실패] job=crossrefSyncJob requestId=req-10 mode=BACKFILL jobExecutionId=11 | status=FAILED | exitCode=FAILED | reason=Conflict_remains_OPEN | error=Unknown"
		);
		assertThat(String.join("\n", logs)).doesNotContain("secret=details");
	}

	private static JobExecution job() {
		return new JobExecution(11, new JobInstance(7, "crossrefSyncJob"), new JobParametersBuilder()
				.addString("requestId", "req-10", true)
				.addString("mode", "BACKFILL", true)
				.addString("dbPassword", "must-not-log", false)
				.toJobParameters());
	}

	private static StepExecution step(JobExecution job) {
		return step(21, "sync", job);
	}

	private static StepExecution step(long id, String name, JobExecution job) {
		return new StepExecution(id, name, job);
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
