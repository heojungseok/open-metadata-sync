package com.heojungseok.openmetadatasync.batch.parameter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunRequestTest {

	private static final Tuning TUNING = new Tuning(1_000, 1_000);

	@Test
	void backfillUsesOnlyItsFrozenRequestContractAsIdentity() {
		RunRequest request = new RunRequest.Backfill(
				"request-1", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), 100_000
		);

		assertIdentity(
				request.toJobParameters(TUNING),
				Set.of("requestId", "mode", "createdFrom", "createdUntil", "maxItems", "syncContractHash")
		);
	}

	@Test
	void incrementalReplayAndBenchmarkUseTheirDocumentedIdentities() {
		assertIdentity(
				new RunRequest.Incremental(
						"request-2", Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z")
				).toJobParameters(TUNING),
				Set.of("requestId", "mode", "indexedFromUtc", "indexedUntilUtc", "syncContractHash")
		);
		assertIdentity(
				new RunRequest.ReplayErrors("request-3", UUID.fromString("00000000-0000-0000-0000-000000000001"))
						.toJobParameters(TUNING),
				Set.of("requestId", "mode", "sourceExecutionId", "syncContractHash")
		);
		assertIdentity(
				new RunRequest.Benchmark("request-4", 1_000_000, 42, "v1").toJobParameters(TUNING),
				Set.of("requestId", "rowCount", "seed", "generatorVersion", "syncContractHash")
		);
	}

	@Test
	void rowsAndTuningNeverChangeJobInstanceIdentity() {
		JobParameters parameters = new RunRequest.Backfill(
				"request-1", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), 100_000
		).toJobParameters(new Tuning(250, 500));

		assertThat(parameters.getLong("rows")).isEqualTo(1_000);
		assertThat(parameters.getLong("chunkSize")).isEqualTo(250);
		assertThat(parameters.getLong("hibernateBatchSize")).isEqualTo(500);
		assertThat(List.of("rows", "chunkSize", "hibernateBatchSize"))
				.allMatch(name -> !parameters.getParameter(name).identifying());
		assertThat(parameters.getParameter("schema")).isNull();
	}

	@Test
	void everyRunTypeRejectsAChangedContractUnderTheSameRequestId() {
		List<List<RunRequest>> changedPairs = List.of(
				List.of(
						new RunRequest.Backfill("same", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), 10),
						new RunRequest.Backfill("same", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"), 10)
				),
				List.of(
						new RunRequest.Incremental("same", Instant.EPOCH, Instant.parse("2026-01-01T00:00:00Z")),
						new RunRequest.Incremental("same", Instant.EPOCH, Instant.parse("2026-01-02T00:00:00Z"))
				),
				List.of(
						new RunRequest.ReplayErrors("same", UUID.fromString("00000000-0000-0000-0000-000000000001")),
						new RunRequest.ReplayErrors("same", UUID.fromString("00000000-0000-0000-0000-000000000002"))
				),
				List.of(
						new RunRequest.Benchmark("same", 100_000, 42, "v1"),
						new RunRequest.Benchmark("same", 1_000_000, 42, "v1")
				)
		);

		assertThat(changedPairs).allSatisfy(pair ->
				assertThatThrownBy(() -> pair.get(1).validateRestart(pair.getFirst(), SyncContract.hash()))
						.isInstanceOf(IllegalArgumentException.class)
		);
	}

	@Test
	void sameRequestRejectsChangedCanonicalContractButNewRequestMeansIntentionalRerun() {
		RunRequest frozen = new RunRequest.Benchmark("request-1", 100_000, 42, "v1");

		assertThatThrownBy(() -> frozen.validateRestart(frozen, "different-hash"))
				.isInstanceOf(IllegalArgumentException.class);
		new RunRequest.Benchmark("request-2", 100_000, 42, "v1")
				.validateRestart(frozen, "different-hash");
	}

	private static void assertIdentity(JobParameters parameters, Set<String> names) {
		assertThat(parameters.getIdentifyingParameters())
				.extracting(JobParameter::name)
				.containsExactlyInAnyOrderElementsOf(names);
		assertThat(parameters.getString("syncContractHash")).isEqualTo(SyncContract.hash());
	}
}
