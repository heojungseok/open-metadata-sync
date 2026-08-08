package com.heojungseok.openmetadatasync.batch.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import tools.jackson.databind.ObjectMapper;

public record BenchmarkEvidence(
		String schemaVersion,
		String scenario,
		long rowCount,
		long seed,
		String generatorVersion,
		String batchStatus,
		String exitStatus,
		Outcomes outcomes,
		Checksums checksums,
		Dml dml,
		Persistence persistence,
		Heap heap,
		Restart restart,
		Timing timing,
		Environment environment
) {

	public BenchmarkEvidence {
		Objects.requireNonNull(schemaVersion);
		Objects.requireNonNull(scenario);
		Objects.requireNonNull(generatorVersion);
		Objects.requireNonNull(batchStatus);
		Objects.requireNonNull(exitStatus);
		Objects.requireNonNull(outcomes);
		Objects.requireNonNull(checksums);
		Objects.requireNonNull(dml);
		Objects.requireNonNull(persistence);
		Objects.requireNonNull(heap);
		Objects.requireNonNull(restart);
		Objects.requireNonNull(timing);
		Objects.requireNonNull(environment);
	}

	public Files write(Path directory) throws IOException {
		java.nio.file.Files.createDirectories(directory);
		Path json = directory.resolve("benchmark-" + scenario + ".json");
		Path markdown = directory.resolve("benchmark-" + scenario + ".md");
		ObjectMapper objectMapper = new ObjectMapper();
		java.nio.file.Files.writeString(json, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this) + "\n");
		java.nio.file.Files.writeString(markdown, markdown());
		return new Files(json, markdown);
	}

	public void requirePreflight() {
		if (rowCount < 100_000) {
			throw new IllegalStateException("Preflight requires at least 100000 rows");
		}
		if (!"COMPLETED".equals(batchStatus) || outcomes.total() != rowCount
				|| !checksums.staging().equals(checksums.target())) {
			throw new IllegalStateException("Preflight reconciliation or checksum failed");
		}
		if (!restart.attempted() || !restart.passed()) {
			throw new IllegalStateException("Preflight restart gate failed");
		}
		if (!heap.plateau()) {
			throw new IllegalStateException("Preflight heap plateau failed");
		}
		if (persistence.jdbcBatches() <= 0) {
			throw new IllegalStateException("Preflight JDBC batch evidence is missing");
		}
	}

	private String markdown() {
		boolean pass;
		try {
			requirePreflight();
			pass = true;
		} catch (IllegalStateException ignored) {
			pass = false;
		}
		return """
				# Data Plane Benchmark

				| Evidence | Value |
				|---|---:|
				| Scenario | %s |
				| Rows | %d |
				| Batch status | %s |
				| Exit status | %s |
				| Accounted outcomes | %d |
				| Staging checksum | `%s` |
				| Target checksum | `%s` |
				| Target inserts | %d |
				| Target updates | %d |
				| Queries | %d |
				| Prepared statements | %d |
				| JDBC batches | %d |
				| Configured batch size | %d |
				| Peak heap bytes | %d |
				| Heap plateau | %s |
				| Restart gate | %s |
				| Preload milliseconds (excluded) | %d |
				| Sync milliseconds | %d |
				| Verify milliseconds | %d |
				| Preflight gate | %s |
				""".formatted(
				scenario, rowCount, batchStatus, exitStatus, outcomes.total(),
				checksums.staging(), checksums.target(), dml.targetInserts(), dml.targetUpdates(),
				persistence.queries(), persistence.preparedStatements(), persistence.jdbcBatches(),
				persistence.configuredBatchSize(), heap.peakBytes(), heap.plateau(), restart.passed(),
				timing.preloadMillis(), timing.syncMillis(), timing.verifyMillis(), pass ? "PASS" : "FAIL"
		);
	}

	public record Outcomes(
			long inserted,
			long superseded,
			long noOp,
			long conflict,
			long indexAdvanced,
			long updated,
			long validationError
	) {
		public long total() {
			return inserted + superseded + noOp + conflict + indexAdvanced + updated + validationError;
		}
	}

	public record Checksums(String staging, String target) {
	}

	public record Dml(long targetInserts, long targetUpdates, String updatedAtBefore, String updatedAtAfter) {
	}

	public record Persistence(long queries, long preparedStatements, long jdbcBatches, int configuredBatchSize) {
	}

	public record Heap(long baselineBytes, long peakBytes, int samples, boolean plateau) {
	}

	public record Restart(boolean attempted, boolean passed) {
	}

	public record Timing(long preloadMillis, long syncMillis, long verifyMillis) {
	}

	public record Environment(
			String javaVersion,
			String osName,
			String osArch,
			int processors,
			long maxHeapBytes,
			String databaseProduct,
			String databaseVersion
	) {
	}

	public record Files(Path json, Path markdown) {
	}
}
