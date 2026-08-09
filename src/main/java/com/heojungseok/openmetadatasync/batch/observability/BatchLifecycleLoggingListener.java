package com.heojungseok.openmetadatasync.batch.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.stereotype.Component;

import com.heojungseok.openmetadatasync.batch.sync.SyncWorkDto;

@Component
public class BatchLifecycleLoggingListener implements JobExecutionListener, StepExecutionListener,
		ChunkListener<SyncWorkDto, SyncWorkDto> {

	private static final Logger LOGGER = LoggerFactory.getLogger(BatchLifecycleLoggingListener.class);
	private static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(60);

	private final Clock clock;
	private final Consumer<String> log;
	private final Map<Long, Instant> lastProgress = new ConcurrentHashMap<>();
	private final Map<Long, Long> attemptedChunks = new ConcurrentHashMap<>();
	private final ThreadLocal<StepExecution> currentStep = new ThreadLocal<>();

	public BatchLifecycleLoggingListener() {
		this(Clock.systemUTC(), LOGGER::info);
	}

	BatchLifecycleLoggingListener(Clock clock, Consumer<String> log) {
		this.clock = clock;
		this.log = log;
	}

	@Override
	public void beforeJob(JobExecution job) {
		log.accept("BATCH_JOB_START [배치 시작] " + jobFields(job));
	}

	@Override
	public void afterJob(JobExecution job) {
		if (job.getStatus() == BatchStatus.FAILED) {
			log.accept("BATCH_JOB_FAILURE [배치 실패] " + jobFields(job) + " | status=" + job.getStatus()
					+ " | exitCode=" + job.getExitStatus().getExitCode()
					+ " | reason=" + failureReason(job)
					+ " | error=" + failureType(job.getFailureExceptions()));
		}
		log.accept("BATCH_JOB_END [배치 종료] " + jobFields(job) + " | status=" + job.getStatus()
				+ " | " + counters(job));
	}

	@Override
	public void beforeStep(StepExecution step) {
		currentStep.set(step);
		lastProgress.put(step.getId(), clock.instant());
		log.accept("BATCH_STEP_START [단계 시작] " + stepFields(step));
	}

	@Override
	public ExitStatus afterStep(StepExecution step) {
		if (step.getStatus() == BatchStatus.FAILED) {
			log.accept("BATCH_STEP_FAILURE [" + step.getStepName() + " 단계 실패] " + stepFields(step)
					+ " | status=" + step.getStatus()
					+ " | exitCode=" + step.getExitStatus().getExitCode()
					+ " | reason=" + failureReason(step)
					+ " | error=" + failureType(step.getFailureExceptions()) + " | " + counters(step));
		}
		log.accept("BATCH_STEP_END [" + step.getStepName() + " 단계 종료] " + stepFields(step)
				+ " | status=" + step.getStatus() + " | " + counters(step));
		lastProgress.remove(step.getId());
		attemptedChunks.remove(step.getId());
		currentStep.remove();
		return null;
	}

	@Override
	public void beforeChunk(Chunk<SyncWorkDto> items) {
		StepExecution step = currentStep.get();
		long completed = step.getCommitCount();
		Instant now = clock.instant();
		Instant previous = lastProgress.getOrDefault(step.getId(), now);
		if (completed > 0 && (completed % 100 == 0 || !now.isBefore(previous.plus(PROGRESS_INTERVAL)))) {
			log.accept("BATCH_CHUNK_PROGRESS [" + step.getStepName() + " 진행] " + stepFields(step)
					+ " | " + counters(step));
			lastProgress.put(step.getId(), now);
		}
		attemptedChunks.put(step.getId(), completed + 1);
	}

	@Override
	public void afterChunk(Chunk<SyncWorkDto> items) {
		StepExecution step = currentStep.get();
		attemptedChunks.remove(step.getId());
	}

	@Override
	public void onChunkError(Exception error, Chunk<SyncWorkDto> items) {
		StepExecution step = currentStep.get();
		long attempted = attemptedChunks.getOrDefault(step.getId(), step.getCommitCount() + 1);
		String errorType = error == null ? "Unknown" : error.getClass().getSimpleName();
		log.accept("BATCH_CHUNK_ERROR [" + step.getStepName() + " 청크 실패] " + stepFields(step)
				+ " | attemptedChunk=" + attempted
				+ " | lastCommittedChunk=" + (attempted - 1)
				+ " | restartFromChunk=" + attempted
				+ " | " + counters(step)
				+ " | error=" + errorType);
		attemptedChunks.remove(step.getId());
	}

	private static String jobFields(JobExecution job) {
		return "job=" + job.getJobInstance().getJobName()
				+ " requestId=" + value(job, "requestId")
				+ " mode=" + value(job, "mode")
				+ " jobExecutionId=" + job.getId();
	}

	private static String stepFields(StepExecution step) {
		return jobFields(step.getJobExecution())
				+ " step=" + step.getStepName()
				+ " stepExecutionId=" + step.getId();
	}

	private static String counters(StepExecution step) {
		return counters(
				step.getReadCount(), step.getWriteCount(), step.getFilterCount(),
				step.getCommitCount(), step.getRollbackCount(), step.getSkipCount()
		);
	}

	private static String counters(JobExecution job) {
		long read = 0;
		long write = 0;
		long filter = 0;
		long commit = 0;
		long rollback = 0;
		long skip = 0;
		for (StepExecution step : job.getStepExecutions()) {
			read += step.getReadCount();
			write += step.getWriteCount();
			filter += step.getFilterCount();
			commit += step.getCommitCount();
			rollback += step.getRollbackCount();
			skip += step.getSkipCount();
		}
		return counters(read, write, filter, commit, rollback, skip);
	}

	private static String counters(long read, long write, long filter, long commit, long rollback, long skip) {
		return "읽음=" + read
				+ " | 저장=" + write
				+ " | 걸러냄=" + filter
				+ " | 커밋=" + commit
				+ " | 롤백=" + rollback
				+ " | 스킵=" + skip;
	}

	private static String value(JobExecution job, String name) {
		String value = job.getJobParameters().getString(name);
		return value == null ? "" : value.replaceAll("[\\s=]", "_");
	}

	private static String failureType(java.util.List<Throwable> failures) {
		return failures.isEmpty() ? "Unknown" : failures.getFirst().getClass().getSimpleName();
	}

	private static String failureReason(JobExecution job) {
		if (!job.getFailureExceptions().isEmpty()) {
			return "TECHNICAL_EXCEPTION";
		}
		return job.getStepExecutions().stream()
				.filter(step -> step.getStatus() == BatchStatus.FAILED)
				.map(BatchLifecycleLoggingListener::failureReason)
				.filter(reason -> !"UNAVAILABLE".equals(reason))
				.findFirst()
				.orElseGet(() -> logReason(job.getExitStatus().getExitDescription()));
	}

	private static String failureReason(StepExecution step) {
		return step.getFailureExceptions().isEmpty()
				? logReason(step.getExitStatus().getExitDescription())
				: "TECHNICAL_EXCEPTION";
	}

	private static String logReason(String description) {
		if (description == null || description.isBlank()) {
			return "UNAVAILABLE";
		}
		return description.lines().findFirst().orElse("UNAVAILABLE").replaceAll("[\\s=]", "_");
	}
}
