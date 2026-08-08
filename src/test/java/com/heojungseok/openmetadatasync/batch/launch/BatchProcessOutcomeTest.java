package com.heojungseok.openmetadatasync.batch.launch;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.boot.batch.autoconfigure.JobExecutionEvent;

import static org.assertj.core.api.Assertions.assertThat;

class BatchProcessOutcomeTest {

	@TempDir
	Path directory;

	@Test
	void mapsCleanBusinessAndTechnicalResultsWithoutPersistingSecrets() throws Exception {
		Path file = directory.resolve("result.properties");
		BatchProcessOutcome outcome = new BatchProcessOutcome(file.toString());
		JobExecution execution = execution();

		execution.setStatus(BatchStatus.COMPLETED);
		outcome.onApplicationEvent(new JobExecutionEvent(execution));
		assertThat(outcome.getExitCode()).isZero();
		assertThat(Files.readString(file)).contains("outcome=SUCCESS").doesNotContain("must-not-leak");

		execution.getExecutionContext().putString("verifyExitStatus", "COMPLETED_WITH_ERRORS");
		outcome.onApplicationEvent(new JobExecutionEvent(execution));
		assertThat(outcome.getExitCode()).isEqualTo(2);
		assertThat(Files.readString(file)).contains("outcome=COMPLETED_WITH_ERRORS");

		execution.setStatus(BatchStatus.FAILED);
		outcome.onApplicationEvent(new JobExecutionEvent(execution));
		assertThat(outcome.getExitCode()).isEqualTo(1);
		assertThat(Files.readString(file)).contains("outcome=FAILED");
	}

	private static JobExecution execution() {
		return new JobExecution(12, new JobInstance(8, "dataPlaneBenchmarkJob"), new JobParametersBuilder()
				.addString("requestId", "bench-10", true)
				.addString("mode", "BENCHMARK", true)
				.addString("dbPassword", "must-not-leak", false)
				.toJobParameters());
	}
}
