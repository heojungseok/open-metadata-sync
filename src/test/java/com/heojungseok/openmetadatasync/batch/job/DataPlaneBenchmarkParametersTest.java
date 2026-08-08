package com.heojungseok.openmetadatasync.batch.job;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.parameters.JobParameters;

import com.heojungseok.openmetadatasync.batch.parameter.SyncContract;
import com.heojungseok.openmetadatasync.batch.parameter.Tuning;

import static org.assertj.core.api.Assertions.assertThat;

class DataPlaneBenchmarkParametersTest {

	@Test
	void launchApiFreezesDatasetIdentityAndKeepsOnlyTuningAndEvidenceNonIdentifying() {
		JobParameters parameters = DataPlaneBenchmarkJobConfig.parameters(
				"preflight-100k", 100_000, 42, "v1", "initial",
				new Tuning(1000, 1000), Path.of("build/evidence"), true
		);

		assertThat(parameters.getString("mode")).isEqualTo("BENCHMARK");
		assertThat(parameters.getString("syncContractHash")).isEqualTo(SyncContract.hash());
		assertThat(parameters.getLong("rowCount")).isEqualTo(100_000);
		assertThat(parameters.getLong("seed")).isEqualTo(42);
		assertThat(parameters.getString("generatorVersion")).isEqualTo("v1");
		assertThat(parameters.getString("scenario")).isEqualTo("initial");
		Set<String> identifying = parameters.getIdentifyingParameters().stream()
				.map(parameter -> parameter.name())
				.collect(Collectors.toSet());
		assertThat(identifying).containsExactlyInAnyOrder(
				"requestId", "syncContractHash", "mode", "rowCount", "seed", "generatorVersion", "scenario"
		);
	}
}
