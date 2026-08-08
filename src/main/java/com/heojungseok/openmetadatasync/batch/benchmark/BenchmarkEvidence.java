package com.heojungseok.openmetadatasync.batch.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import tools.jackson.databind.ObjectMapper;

public record BenchmarkEvidence(
		String schemaVersion,
		String syncContractHash,
		String scenario,
		long rowCount,
		long seed,
		String generatorVersion,
		int chunkSize,
		String batchStatus,
		String exitStatus,
		Outcomes outcomes,
		Rows rows,
		Checksums checksums,
		Dml dml,
		Persistence persistence,
		Heap heap,
		Restart restart,
		Timing timing,
		Environment environment
) {
	private static final long PREFLIGHT_MAX_HEAP_BYTES = 256L * 1024 * 1024;

	public BenchmarkEvidence {
		Objects.requireNonNull(schemaVersion);
		Objects.requireNonNull(syncContractHash);
		Objects.requireNonNull(scenario);
		Objects.requireNonNull(generatorVersion);
		Objects.requireNonNull(batchStatus);
		Objects.requireNonNull(exitStatus);
		Objects.requireNonNull(outcomes);
		Objects.requireNonNull(rows);
		Objects.requireNonNull(checksums);
		Objects.requireNonNull(dml);
		Objects.requireNonNull(persistence);
		Objects.requireNonNull(heap);
		Objects.requireNonNull(restart);
		Objects.requireNonNull(timing);
		Objects.requireNonNull(environment);
	}

	public static void requireMillionGate(
			Path directory,
			long seed,
			String generatorVersion,
			String syncContractHash,
			int chunkSize,
			int batchSize
	) {
		for (String scenario : java.util.List.of("initial", "no-op")) {
			BenchmarkEvidence evidence;
			try {
				Path json = evidencePath(directory, 100_000, scenario, "json");
				if (!java.nio.file.Files.exists(json)) {
					json = directory.resolve("benchmark-" + scenario + ".json");
				}
				evidence = new ObjectMapper().readValue(
						java.nio.file.Files.readString(json),
						BenchmarkEvidence.class
				);
			} catch (IOException exception) {
				throw new IllegalStateException(
						"1M benchmark requires persisted 100k initial and no-op PASS evidence", exception
				);
			}
			evidence.requirePreflightQualification();
			if (!scenario.equals(evidence.scenario()) || evidence.seed() != seed
					|| !generatorVersion.equals(evidence.generatorVersion())
					|| !syncContractHash.equals(evidence.syncContractHash())
					|| evidence.chunkSize() != chunkSize
					|| evidence.persistence().configuredBatchSize() != batchSize
					|| !"v2".equals(evidence.schemaVersion())) {
				throw new IllegalStateException("100k preflight profile does not match the 1M launch");
			}
		}
	}

	public Files write(Path directory) throws IOException {
		requireScenarioSemantics();
		java.nio.file.Files.createDirectories(directory);
		Path json = evidencePath(directory, rowCount, scenario, "json");
		Path markdown = evidencePath(directory, rowCount, scenario, "md");
		ObjectMapper objectMapper = new ObjectMapper();
		writeAtomically(json, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this) + "\n");
		writeAtomically(markdown, markdown());
		return new Files(json, markdown);
	}

	private static Path evidencePath(Path directory, long rowCount, String scenario, String extension) {
		return directory.resolve("benchmark-" + rowCount + "-" + scenario + "." + extension);
	}

	private static void writeAtomically(Path target, String content) throws IOException {
		Path temporary = java.nio.file.Files.createTempFile(
				target.getParent(), target.getFileName().toString() + ".", ".tmp"
		);
		try {
			java.nio.file.Files.writeString(temporary, content);
			java.nio.file.Files.move(
					temporary, target,
					java.nio.file.StandardCopyOption.ATOMIC_MOVE,
					java.nio.file.StandardCopyOption.REPLACE_EXISTING
			);
		} finally {
			java.nio.file.Files.deleteIfExists(temporary);
		}
	}

	public void requireProcessingResult() {
		requireScenarioSemantics();
		if (!"COMPLETED".equals(batchStatus) || !"COMPLETED".equals(exitStatus)
				|| outcomes.total() != rowCount
				|| !checksums.staging().equals(checksums.target())) {
			throw new IllegalStateException("Benchmark processing reconciliation or checksum failed");
		}
		if (rows.staging() != rowCount || rows.target() != rowCount || rows.distinctDoi() != rowCount) {
			throw new IllegalStateException("Benchmark processing row integrity failed");
		}
	}

	public void requirePreflightQualification() {
		if (!"v2".equals(schemaVersion)) {
			throw new IllegalStateException("Preflight evidence schema must be v2");
		}
		requireProcessingResult();
		if (rowCount != 100_000) {
			throw new IllegalStateException("Preflight requires exactly 100000 rows");
		}
		if (environment.maxHeapBytes() != PREFLIGHT_MAX_HEAP_BYTES) {
			throw new IllegalStateException("Preflight max heap must be exactly 268435456 bytes");
		}
		if (!restart.attempted() || !restart.passed()) {
			throw new IllegalStateException("Preflight restart qualification failed");
		}
		requireHeapRetentionQualification();
		if (persistence.jdbcBatches() <= 0) {
			throw new IllegalStateException("Preflight persistence batch evidence is missing");
		}
	}

	private void requireScenarioSemantics() {
		if ("initial".equals(scenario) && (outcomes.inserted() != rowCount
				|| outcomes.superseded() != 0 || outcomes.noOp() != 0 || outcomes.conflict() != 0
				|| outcomes.indexAdvanced() != 0 || outcomes.updated() != 0 || outcomes.validationError() != 0
				|| dml.targetInserts() != rowCount || dml.targetUpdates() != 0)) {
			throw new IllegalStateException("initial semantics failed");
		}
		if ("no-op".equals(scenario) && (outcomes.inserted() != 0
				|| outcomes.superseded() != 0 || outcomes.noOp() != rowCount || outcomes.conflict() != 0
				|| outcomes.indexAdvanced() != 0 || outcomes.updated() != 0 || outcomes.validationError() != 0
				|| dml.targetInserts() != 0 || dml.targetUpdates() != 0
				|| !Objects.equals(dml.updatedAtBefore(), dml.updatedAtAfter()))) {
			throw new IllegalStateException("no-op semantics failed");
		}
		if (!("initial".equals(scenario) || "no-op".equals(scenario))) {
			throw new IllegalStateException("Unsupported benchmark scenario");
		}
	}

	private String markdown() {
		boolean processing = passes(this::requireProcessingResult);
		boolean restartQualified = restart.attempted() && restart.passed();
		boolean heapQualified = passes(this::requireHeapRetentionQualification);
		boolean persistenceQualified = persistence.jdbcBatches() > 0;
		boolean preflightQualified = passes(this::requirePreflightQualification);
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
				| Max heap bytes | %d |
				| Peak heap bytes | %d |
				| First heap window floor bytes | %d |
				| Last heap window floor bytes | %d |
				| Retained heap growth bytes | %d |
				| Allowed heap growth bytes | %d |
				| Preload milliseconds (excluded) | %d |
				| Sync milliseconds | %d |
				| Verify milliseconds | %d |
				| Processing result | %s |
				| Restart qualification | %s |
				| Heap retention qualification | %s |
				| Persistence qualification | %s |
				| Preflight qualification | %s |
				""".formatted(
				scenario, rowCount, batchStatus, exitStatus, outcomes.total(),
				checksums.staging(), checksums.target(), dml.targetInserts(), dml.targetUpdates(),
				persistence.queries(), persistence.preparedStatements(), persistence.jdbcBatches(),
				persistence.configuredBatchSize(), environment.maxHeapBytes(), heap.peakBytes(),
				heap.firstWindowFloorBytes(), heap.lastWindowFloorBytes(),
				heap.retainedGrowthBytes(), heap.allowedGrowthBytes(),
				timing.preloadMillis(), timing.syncMillis(), timing.verifyMillis(),
				verdict(processing), verdict(restartQualified), verdict(heapQualified),
				verdict(persistenceQualified), verdict(preflightQualified)
		);
	}

	private static boolean passes(Runnable requirement) {
		try {
			requirement.run();
			return true;
		} catch (IllegalStateException ignored) {
			return false;
		}
	}

	private void requireHeapRetentionQualification() {
		long expectedGrowth = heap.lastWindowFloorBytes() - heap.firstWindowFloorBytes();
		long expectedAllowed = Math.max(8L * 1024 * 1024, heap.firstWindowFloorBytes() / 10);
		if (!"v2".equals(schemaVersion) || heap.samples() < 64
				|| heap.firstWindowFloorBytes() <= 0 || heap.lastWindowFloorBytes() <= 0
				|| heap.retainedGrowthBytes() != expectedGrowth
				|| heap.allowedGrowthBytes() != expectedAllowed
				|| !heap.plateau() || heap.retainedGrowthBytes() > heap.allowedGrowthBytes()) {
			throw new IllegalStateException("Preflight heap retention qualification failed");
		}
	}

	private static String verdict(boolean passed) {
		return passed ? "PASS" : "FAIL";
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

	public record Rows(long staging, long target, long distinctDoi) {
	}

	public record Checksums(String staging, String target) {
	}

	public record Dml(long targetInserts, long targetUpdates, String updatedAtBefore, String updatedAtAfter) {
	}

	public record Persistence(long queries, long preparedStatements, long jdbcBatches, int configuredBatchSize) {
	}

	public record Heap(
			long baselineBytes,
			long peakBytes,
			int samples,
			long firstWindowFloorBytes,
			long lastWindowFloorBytes,
			long retainedGrowthBytes,
			long allowedGrowthBytes,
			boolean plateau
	) {
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
