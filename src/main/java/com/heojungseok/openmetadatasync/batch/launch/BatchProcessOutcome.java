package com.heojungseok.openmetadatasync.batch.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.batch.autoconfigure.JobExecutionEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class BatchProcessOutcome implements ApplicationListener<JobExecutionEvent>, ExitCodeGenerator {

	private final String outcomeFile;
	private volatile int exitCode;

	public BatchProcessOutcome(@Value("${batch.outcome-file:}") String outcomeFile) {
		this.outcomeFile = outcomeFile;
	}

	@Override
	public void onApplicationEvent(JobExecutionEvent event) {
		JobExecution execution = event.getJobExecution();
		String outcome;
		if (execution.getStatus() != BatchStatus.COMPLETED) {
			exitCode = 1;
			outcome = "FAILED";
		} else if ("COMPLETED_WITH_ERRORS".equals(
				execution.getExecutionContext().getString("verifyExitStatus", "")
		)) {
			exitCode = 2;
			outcome = "COMPLETED_WITH_ERRORS";
		} else {
			exitCode = 0;
			outcome = "SUCCESS";
		}
		write(outcome, execution.getJobInstance().getJobName(), execution.getJobParameters(), execution.getId());
	}

	void skipped(String jobName, JobParameters parameters, long existingExecutionId) {
		exitCode = 3;
		write("ALREADY_COMPLETED", jobName, parameters, existingExecutionId);
	}

	@Override
	public int getExitCode() {
		return exitCode;
	}

	private void write(String outcome, String jobName, JobParameters parameters, long executionId) {
		if (outcomeFile == null || outcomeFile.isBlank()) {
			return;
		}
		Path path = Path.of(outcomeFile);
		try {
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(path, "code=" + exitCode
					+ "\noutcome=" + safe(outcome)
					+ "\njob=" + safe(jobName)
					+ "\nrequestId=" + safe(parameters.getString("requestId"))
					+ "\nmode=" + safe(parameters.getString("mode"))
					+ "\nexecutionId=" + executionId + "\n");
		} catch (IOException exception) {
			exitCode = 1;
			throw new IllegalStateException("Could not write Batch process outcome", exception);
		}
	}

	private static String safe(String value) {
		return value == null ? "" : value.replaceAll("[\\r\\n=]", "_");
	}
}
