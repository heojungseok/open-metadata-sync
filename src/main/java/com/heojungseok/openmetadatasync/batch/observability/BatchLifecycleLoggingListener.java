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
		log.accept("BATCH_JOB_START " + jobFields(job));
	}

	@Override
	public void afterJob(JobExecution job) {
		if (job.getStatus() == BatchStatus.FAILED) {
			log.accept("BATCH_JOB_FAILURE " + jobFields(job) + " status=" + job.getStatus()
					+ " error=" + failureType(job.getFailureExceptions()));
		}
		log.accept("BATCH_JOB_END " + jobFields(job) + " status=" + job.getStatus());
	}

	@Override
	public void beforeStep(StepExecution step) {
		currentStep.set(step);
		lastProgress.put(step.getId(), clock.instant());
		log.accept("BATCH_STEP_START " + stepFields(step));
	}

	@Override
	public ExitStatus afterStep(StepExecution step) {
		if (step.getStatus() == BatchStatus.FAILED) {
			log.accept("BATCH_STEP_FAILURE " + stepFields(step) + " status=" + step.getStatus()
					+ " error=" + failureType(step.getFailureExceptions()) + " " + counters(step));
		}
		log.accept("BATCH_STEP_END " + stepFields(step) + " status=" + step.getStatus() + " " + counters(step));
		lastProgress.remove(step.getId());
		attemptedChunks.remove(step.getId());
		currentStep.remove();
		return null;
	}

	@Override
	public void beforeChunk(Chunk<SyncWorkDto> items) {
		StepExecution step = currentStep.get();
		attemptedChunks.put(step.getId(), step.getCommitCount() + 1);
	}

	@Override
	public void afterChunk(Chunk<SyncWorkDto> items) {
		StepExecution step = currentStep.get();
		Instant now = clock.instant();
		Instant previous = lastProgress.getOrDefault(step.getId(), now);
		if (step.getCommitCount() % 100 == 0 || !now.isBefore(previous.plus(PROGRESS_INTERVAL))) {
			log.accept("BATCH_CHUNK_PROGRESS " + stepFields(step) + " " + counters(step));
			lastProgress.put(step.getId(), now);
		}
		attemptedChunks.remove(step.getId());
	}

	@Override
	public void onChunkError(Exception error, Chunk<SyncWorkDto> items) {
		StepExecution step = currentStep.get();
		long attempted = attemptedChunks.getOrDefault(step.getId(), step.getCommitCount() + 1);
		String errorType = error == null ? "Unknown" : error.getClass().getSimpleName();
		log.accept("BATCH_CHUNK_ERROR " + stepFields(step)
				+ " attemptedChunk=" + attempted
				+ " lastCommittedChunk=" + (attempted - 1)
				+ " restartFromChunk=" + attempted
				+ " " + counters(step)
				+ " error=" + errorType);
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
		return "commitCount=" + step.getCommitCount()
				+ " readCount=" + step.getReadCount()
				+ " writeCount=" + step.getWriteCount();
	}

	private static String value(JobExecution job, String name) {
		String value = job.getJobParameters().getString(name);
		return value == null ? "" : value.replaceAll("[\\s=]", "_");
	}

	private static String failureType(java.util.List<Throwable> failures) {
		return failures.isEmpty() ? "Unknown" : failures.getFirst().getClass().getSimpleName();
	}
}
