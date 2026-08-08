package com.heojungseok.openmetadatasync.batch.launch;

import java.util.Comparator;
import java.util.Properties;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.batch.autoconfigure.JobLauncherApplicationRunner;

import com.heojungseok.openmetadatasync.batch.parameter.SyncContract;

public class AlreadyCompletedAwareJobLauncherApplicationRunner extends JobLauncherApplicationRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(AlreadyCompletedAwareJobLauncherApplicationRunner.class);

	private final JobRepository jobRepository;
	private final BatchProcessOutcome outcome;
	private final Consumer<String> log;

	public AlreadyCompletedAwareJobLauncherApplicationRunner(
			JobOperator jobOperator,
			JobRepository jobRepository,
			BatchProcessOutcome outcome
	) {
		this(jobOperator, jobRepository, outcome, LOGGER::info);
	}

	AlreadyCompletedAwareJobLauncherApplicationRunner(
			JobOperator jobOperator,
			JobRepository jobRepository,
			BatchProcessOutcome outcome,
			Consumer<String> log
	) {
		super(jobOperator);
		this.jobRepository = jobRepository;
		this.outcome = outcome;
		this.log = log;
	}

	@Override
	protected void launchJobFromProperties(Properties properties) throws JobExecutionException {
		Properties launchProperties = new Properties();
		launchProperties.putAll(properties);
		launchProperties.putIfAbsent(
				"syncContractHash", SyncContract.hash() + ",java.lang.String,true"
		);
		super.launchJobFromProperties(launchProperties);
	}

	@Override
	protected void execute(Job job, JobParameters parameters)
			throws JobExecutionAlreadyRunningException, JobRestartException,
			JobInstanceAlreadyCompleteException, InvalidJobParametersException {
		try {
			super.execute(job, parameters);
		} catch (JobInstanceAlreadyCompleteException exception) {
			JobInstance instance = jobRepository.getJobInstance(job.getName(), parameters);
			JobExecution existing = instance == null ? null : jobRepository.getJobExecutions(instance).stream()
					.filter(execution -> execution.getStatus() == BatchStatus.COMPLETED)
					.max(Comparator.comparingLong(JobExecution::getId))
					.orElse(null);
			if (existing == null) {
				throw exception;
			}
			String requestId = safe(parameters.getString("requestId"));
			String mode = safe(parameters.getString("mode"));
			log.accept("BATCH_LAUNCH_SKIPPED reason=ALREADY_COMPLETED job=" + safe(job.getName())
					+ " requestId=" + requestId
					+ " mode=" + mode
					+ " existingExecutionId=" + existing.getId());
			outcome.skipped(job.getName(), parameters, existing.getId());
		}
	}

	private static String safe(String value) {
		return value == null ? "" : value.replaceAll("[\\s=]", "_");
	}
}
