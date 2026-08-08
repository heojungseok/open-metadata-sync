package com.heojungseok.openmetadatasync.batch.launch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;

import com.heojungseok.openmetadatasync.batch.parameter.SyncContract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlreadyCompletedAwareJobLauncherApplicationRunnerTest {

	@TempDir
	Path directory;

	@Test
	void alreadyCompletedLaunchLogsExistingExecutionAndReturnsMachineReadableSkipWithoutRetry() throws Exception {
		JobOperator operator = mock(JobOperator.class);
		JobRepository repository = mock(JobRepository.class);
		Job job = mock(Job.class);
		JobParameters parameters = new JobParametersBuilder()
				.addString("requestId", "req-complete", true)
				.addString("mode", "BACKFILL", true)
				.toJobParameters();
		JobInstance instance = new JobInstance(7, "crossrefSyncJob");
		JobExecution completed = new JobExecution(42, instance, parameters);
		completed.setStatus(BatchStatus.COMPLETED);
		JobExecution failed = new JobExecution(41, instance, parameters);
		failed.setStatus(BatchStatus.FAILED);
		when(job.getName()).thenReturn("crossrefSyncJob");
		when(operator.start(job, parameters)).thenThrow(new JobInstanceAlreadyCompleteException("complete"));
		when(repository.getJobInstance("crossrefSyncJob", parameters)).thenReturn(instance);
		when(repository.getJobExecutions(instance)).thenReturn(List.of(failed, completed));
		List<String> logs = new ArrayList<>();
		Path outcomeFile = directory.resolve("outcome.properties");
		BatchProcessOutcome outcome = new BatchProcessOutcome(outcomeFile.toString());
		AlreadyCompletedAwareJobLauncherApplicationRunner runner =
				new AlreadyCompletedAwareJobLauncherApplicationRunner(operator, repository, outcome, logs::add);

		runner.execute(job, parameters);

		assertThat(logs).containsExactly(
				"BATCH_LAUNCH_SKIPPED reason=ALREADY_COMPLETED job=crossrefSyncJob requestId=req-complete mode=BACKFILL existingExecutionId=42"
		);
		assertThat(outcome.getExitCode()).isEqualTo(3);
		assertThat(Files.readString(outcomeFile)).isEqualTo("""
				code=3
				outcome=ALREADY_COMPLETED
				job=crossrefSyncJob
				requestId=req-complete
				mode=BACKFILL
				executionId=42
				""");
		verify(operator, times(1)).start(job, parameters);
		verify(repository, never()).createJobInstance("crossrefSyncJob", parameters);
		verify(job, never()).execute(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void commandLineBoundaryAddsTheBuildContractAsAnIdentifyingParameter() throws Exception {
		JobOperator operator = mock(JobOperator.class);
		JobRepository repository = mock(JobRepository.class);
		Job job = mock(Job.class);
		when(job.getName()).thenReturn("crossrefSyncJob");
		JobExecution launched = new JobExecution(43, new JobInstance(9, "crossrefSyncJob"), new JobParameters());
		when(operator.start(org.mockito.ArgumentMatchers.eq(job), org.mockito.ArgumentMatchers.any()))
				.thenReturn(launched);
		AlreadyCompletedAwareJobLauncherApplicationRunner runner =
				new AlreadyCompletedAwareJobLauncherApplicationRunner(
						operator, repository, new BatchProcessOutcome(""), ignored -> { }
				);
		runner.setJobs(List.of(job));
		runner.setJobName("crossrefSyncJob");
		runner.afterPropertiesSet();

		runner.run(
				"requestId=req-contract,java.lang.String,true",
				"mode=BACKFILL,java.lang.String,true"
		);

		ArgumentCaptor<JobParameters> captured = ArgumentCaptor.forClass(JobParameters.class);
		verify(operator).start(org.mockito.ArgumentMatchers.eq(job), captured.capture());
		assertThat(captured.getValue().getString("syncContractHash")).isEqualTo(SyncContract.hash());
		assertThat(captured.getValue().getParameter("syncContractHash").identifying()).isTrue();
	}
}
