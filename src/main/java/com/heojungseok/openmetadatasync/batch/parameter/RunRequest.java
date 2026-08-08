package com.heojungseok.openmetadatasync.batch.parameter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

import com.heojungseok.openmetadatasync.batch.execution.RunMode;

public sealed interface RunRequest {

	String requestId();

	RunMode mode();

	default JobParameters toJobParameters(Tuning tuning) {
		JobParametersBuilder parameters = new JobParametersBuilder()
				.addString("requestId", requestId(), true)
				.addString("syncContractHash", SyncContract.hash(), true);

		switch (this) {
			case Backfill request -> parameters
					.addString("mode", request.mode().name(), true)
					.addLocalDate("createdFrom", request.createdFrom(), true)
					.addLocalDate("createdUntil", request.createdUntil(), true)
					.addLong("maxItems", request.maxItems(), true);
			case Incremental request -> parameters
					.addString("mode", request.mode().name(), true)
					.addString("indexedFromUtc", request.indexedFromUtc().toString(), true)
					.addString("indexedUntilUtc", request.indexedUntilUtc().toString(), true);
			case ReplayErrors request -> parameters
					.addString("mode", request.mode().name(), true)
					.addString("sourceExecutionId", request.sourceExecutionId().toString(), true);
			case Benchmark request -> parameters
					.addLong("rowCount", request.rowCount(), true)
					.addLong("seed", request.seed(), true)
					.addString("generatorVersion", request.generatorVersion(), true);
		}

		return parameters
				.addLong("rows", (long) Tuning.CROSSREF_ROWS, false)
				.addLong("chunkSize", (long) tuning.chunkSize(), false)
				.addLong("hibernateBatchSize", (long) tuning.hibernateBatchSize(), false)
				.toJobParameters();
	}

	default void validateRestart(RunRequest frozen, String frozenSyncContractHash) {
		if (requestId().equals(frozen.requestId())
				&& (!equals(frozen) || !SyncContract.hash().equals(frozenSyncContractHash))) {
			throw new IllegalArgumentException("A frozen request contract cannot change during restart");
		}
	}

	record Backfill(String requestId, LocalDate createdFrom, LocalDate createdUntil, long maxItems)
			implements RunRequest {

		public Backfill {
			requireRequestId(requestId);
			Objects.requireNonNull(createdFrom, "createdFrom");
			Objects.requireNonNull(createdUntil, "createdUntil");
			if (createdUntil.isBefore(createdFrom) || maxItems <= 0) {
				throw new IllegalArgumentException("Invalid backfill range or maxItems");
			}
		}

		@Override
		public RunMode mode() {
			return RunMode.BACKFILL;
		}
	}

	record Incremental(String requestId, Instant indexedFromUtc, Instant indexedUntilUtc)
			implements RunRequest {

		public Incremental {
			requireRequestId(requestId);
			Objects.requireNonNull(indexedFromUtc, "indexedFromUtc");
			Objects.requireNonNull(indexedUntilUtc, "indexedUntilUtc");
			if (!indexedFromUtc.isBefore(indexedUntilUtc)) {
				throw new IllegalArgumentException("Incremental range must be increasing");
			}
		}

		@Override
		public RunMode mode() {
			return RunMode.INCREMENTAL;
		}
	}

	record ReplayErrors(String requestId, UUID sourceExecutionId) implements RunRequest {

		public ReplayErrors {
			requireRequestId(requestId);
			Objects.requireNonNull(sourceExecutionId, "sourceExecutionId");
		}

		@Override
		public RunMode mode() {
			return RunMode.REPLAY_ERRORS;
		}
	}

	record Benchmark(String requestId, long rowCount, long seed, String generatorVersion) implements RunRequest {

		public Benchmark {
			requireRequestId(requestId);
			requireText(generatorVersion, "generatorVersion");
			if (rowCount <= 0) {
				throw new IllegalArgumentException("rowCount must be positive");
			}
		}

		@Override
		public RunMode mode() {
			return RunMode.BENCHMARK;
		}
	}

	private static void requireRequestId(String requestId) {
		requireText(requestId, "requestId");
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " is required");
		}
	}
}
