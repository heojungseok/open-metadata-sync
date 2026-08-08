package com.heojungseok.openmetadatasync.batch.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.heojungseok.openmetadatasync.batch.parameter.SyncContract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkEvidenceTest {
	private static final long MIB = 1024L * 1024;

	@TempDir
	Path output;

	@Test
	void writesStableSecretFreeJsonAndMarkdownWithTheCompleteEvidenceContract() throws Exception {
		BenchmarkEvidence evidence = evidence(100_000, true, true, 4);

		BenchmarkEvidence.Files first = evidence.write(output);
		String json = java.nio.file.Files.readString(first.json());
		String markdown = java.nio.file.Files.readString(first.markdown());
		BenchmarkEvidence.Files second = evidence.write(output);

		assertThat(first.json()).hasFileName("benchmark-100000-initial.json");
		assertThat(first.markdown()).hasFileName("benchmark-100000-initial.md");
		assertThat(Files.readString(second.json())).isEqualTo(json);
		assertThat(json).contains(
				"\"schemaVersion\" : \"v2\"",
				"\"outcomes\"", "\"checksums\"", "\"dml\"", "\"persistence\"",
				"\"heap\"", "\"restart\"", "\"environment\"",
				"\"firstWindowFloorBytes\"", "\"lastWindowFloorBytes\"",
				"\"retainedGrowthBytes\"", "\"allowedGrowthBytes\""
		);
		assertThat(markdown).contains(
				"# Data Plane Benchmark", "Scenario | initial",
				"| Processing result | PASS |",
				"| Restart qualification | PASS |",
				"| Heap retention qualification | PASS |",
				"| Persistence qualification | PASS |",
				"| Preflight qualification | PASS |"
		);
		assertThat((json + markdown).toLowerCase())
				.doesNotContain("password", "secret", "jdbc:mysql", "username");
		try (java.util.stream.Stream<Path> paths = Files.list(output)) {
			assertThat(paths.map(path -> path.getFileName().toString()))
					.noneMatch(name -> name.endsWith(".tmp"));
		}
	}

	@Test
	void preflightRequiresOneHundredThousandRowsRestartHeapChecksumAndJdbcBatchEvidence() {
		evidence(100_000, true, true, 1).requirePreflightQualification();

		assertThatThrownBy(() -> evidence(99_999, true, true, 1).requirePreflightQualification())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("100000");
		assertThatThrownBy(() -> evidence(100_000, false, true, 1).requirePreflightQualification())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("restart");
		assertThatThrownBy(() -> evidence(100_000, true, false, 1).requirePreflightQualification())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("heap");
		assertThatThrownBy(() -> evidence(100_000, true, true, 0).requirePreflightQualification())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("batch");
		assertThatThrownBy(() -> evidence(100_000, true, true, 1, 99_999).requirePreflightQualification())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("row integrity");
	}

	@Test
	void completedProcessingRemainsPassWhenHeapQualificationIsNotMet() throws Exception {
		BenchmarkEvidence evidence = evidence(100_000, true, false, 1);

		evidence.requireProcessingResult();
		assertThatThrownBy(evidence::requirePreflightQualification)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("heap");

		String markdown = Files.readString(evidence.write(output).markdown());
		assertThat(markdown).contains(
				"| Processing result | PASS |",
				"| Heap retention qualification | FAIL |",
				"| Preflight qualification | FAIL |"
		);
	}

	@Test
	void preflightRejectsInconsistentHeapQualificationEvidence() {
		BenchmarkEvidence valid = evidence(100_000, true, true, 1);

		assertThatThrownBy(() -> withHeap(valid, new BenchmarkEvidence.Heap(
				50 * MIB, 80 * MIB, 63, 50 * MIB, 55 * MIB, 5 * MIB, 8 * MIB, true
		)).requirePreflightQualification())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("heap");
		assertThatThrownBy(() -> withHeap(valid, new BenchmarkEvidence.Heap(
				50 * MIB, 80 * MIB, 64, 50 * MIB, 60 * MIB, 10 * MIB, 8 * MIB, true
		)).requirePreflightQualification())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("heap");
	}

	@Test
	void preflightRejectsEvidenceOutsideTheFixedHeapEnvelope() {
		BenchmarkEvidence valid = evidence(100_000, true, true, 1);
		BenchmarkEvidence.Environment wrongHeap = new BenchmarkEvidence.Environment(
				"21", "Mac OS X", "aarch64", 10, 4L * 1024 * MIB, "MySQL", "8.4"
		);

		assertThatThrownBy(() -> withEnvironment(valid, wrongHeap).requirePreflightQualification())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("max heap");
	}

	@Test
	void millionGateRequiresExactInitialAndNoOpPreflightProfiles() throws Exception {
		evidence("initial", 100_000, true, true, 1).write(output);
		evidence("no-op", 100_000, true, true, 1).write(output);

		BenchmarkEvidence.requireMillionGate(output, 42, "v1", SyncContract.hash(), 1000, 1000);

		evidence("no-op", 99_999, true, true, 1).write(output);
		Files.copy(
				output.resolve("benchmark-99999-no-op.json"),
				output.resolve("benchmark-100000-no-op.json"),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING
		);
		assertThatThrownBy(() -> BenchmarkEvidence.requireMillionGate(
				output, 42, "v1", SyncContract.hash(), 1000, 1000
		)).isInstanceOf(IllegalStateException.class).hasMessageContaining("100000");

		evidence("no-op", 100_000, true, true, 1, 100_000,
				43, "v1", SyncContract.hash(), 1000, 1000)
				.write(output);
		assertThatThrownBy(() -> BenchmarkEvidence.requireMillionGate(
				output, 42, "v1", SyncContract.hash(), 1000, 1000
		)).isInstanceOf(IllegalStateException.class).hasMessageContaining("profile");
	}

	@Test
	void hundredThousandAndMillionRunsKeepFourEvidenceProfilesWithoutOverwrite() throws Exception {
		evidence("initial", 100_000, true, true, 1).write(output);
		evidence("no-op", 100_000, true, true, 1).write(output);
		BenchmarkEvidence.requireMillionGate(output, 42, "v1", SyncContract.hash(), 1000, 1000);

		evidence("initial", 1_000_000, true, true, 1).write(output);
		evidence("no-op", 1_000_000, true, true, 1).write(output);

		BenchmarkEvidence.requireMillionGate(output, 42, "v1", SyncContract.hash(), 1000, 1000);
		assertThat(Files.readString(output.resolve("benchmark-100000-initial.json")))
				.contains("\"rowCount\" : 100000");
		assertThat(Files.readString(output.resolve("benchmark-100000-no-op.json")))
				.contains("\"rowCount\" : 100000");
		assertThat(Files.readString(output.resolve("benchmark-1000000-initial.json")))
				.contains("\"rowCount\" : 1000000");
		assertThat(Files.readString(output.resolve("benchmark-1000000-no-op.json")))
				.contains("\"rowCount\" : 1000000");
		try (java.util.stream.Stream<Path> paths = Files.list(output)) {
			assertThat(paths.map(path -> path.getFileName().toString())).containsExactlyInAnyOrder(
					"benchmark-100000-initial.json", "benchmark-100000-initial.md",
					"benchmark-100000-no-op.json", "benchmark-100000-no-op.md",
					"benchmark-1000000-initial.json", "benchmark-1000000-initial.md",
					"benchmark-1000000-no-op.json", "benchmark-1000000-no-op.md"
			);
		}
	}

	@Test
	void millionGateStillAcceptsLegacyHundredThousandJsonFiles() throws Exception {
		evidence("initial", 100_000, true, true, 1).write(output);
		evidence("no-op", 100_000, true, true, 1).write(output);
		Files.move(
				output.resolve("benchmark-100000-initial.json"),
				output.resolve("benchmark-initial.json")
		);
		Files.move(
				output.resolve("benchmark-100000-no-op.json"),
				output.resolve("benchmark-no-op.json")
		);

		BenchmarkEvidence.requireMillionGate(output, 42, "v1", SyncContract.hash(), 1000, 1000);
	}

	@Test
	void millionGateRejectsSchemaV1Evidence() throws Exception {
		evidence("initial", 100_000, true, true, 1).write(output);
		evidence("no-op", 100_000, true, true, 1).write(output);
		Path initial = output.resolve("benchmark-100000-initial.json");
		Files.writeString(initial, Files.readString(initial)
				.replace("\"schemaVersion\" : \"v2\"", "\"schemaVersion\" : \"v1\""));

		assertThatThrownBy(() -> BenchmarkEvidence.requireMillionGate(
				output, 42, "v1", SyncContract.hash(), 1000, 1000
		)).isInstanceOf(IllegalStateException.class).hasMessageContaining("schema");
	}

	@Test
	void millionGateRejectsEvidenceFromAnotherHeapEnvelope() throws Exception {
		evidence("initial", 100_000, true, true, 1).write(output);
		evidence("no-op", 100_000, true, true, 1).write(output);
		Path initial = output.resolve("benchmark-100000-initial.json");
		Files.writeString(initial, Files.readString(initial)
				.replace("\"maxHeapBytes\" : 268435456", "\"maxHeapBytes\" : 4294967296"));

		assertThatThrownBy(() -> BenchmarkEvidence.requireMillionGate(
				output, 42, "v1", SyncContract.hash(), 1000, 1000
		)).isInstanceOf(IllegalStateException.class).hasMessageContaining("max heap");
	}

	@Test
	void unsafeScenarioCannotInfluenceTheEvidencePath() {
		assertThatThrownBy(() -> evidence("../escape", 100_000, true, true, 1).write(output))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Unsupported benchmark scenario");
		assertThat(output).isEmptyDirectory();
	}

	@Test
	void millionGateRejectsEitherEvidenceWithOppositeScenarioOutcomes() throws Exception {
		evidence("initial", 100_000, true, true, 1).write(output);
		evidence("no-op", 100_000, true, true, 1).write(output);
		Path initial = output.resolve("benchmark-100000-initial.json");
		Files.writeString(initial, Files.readString(initial)
				.replace("\"inserted\" : 100000", "\"inserted\" : 0")
				.replace("\"noOp\" : 0", "\"noOp\" : 100000")
				.replace("\"targetInserts\" : 100000", "\"targetInserts\" : 0"));

		assertThatThrownBy(() -> BenchmarkEvidence.requireMillionGate(
				output, 42, "v1", SyncContract.hash(), 1000, 1000
		)).isInstanceOf(IllegalStateException.class).hasMessageContaining("initial semantics");

		evidence("initial", 100_000, true, true, 1).write(output);
		Path noOp = output.resolve("benchmark-100000-no-op.json");
		Files.writeString(noOp, Files.readString(noOp)
				.replace("\"inserted\" : 0", "\"inserted\" : 100000")
				.replace("\"noOp\" : 100000", "\"noOp\" : 0")
				.replace("\"targetInserts\" : 0", "\"targetInserts\" : 100000"));

		assertThatThrownBy(() -> BenchmarkEvidence.requireMillionGate(
				output, 42, "v1", SyncContract.hash(), 1000, 1000
		)).isInstanceOf(IllegalStateException.class).hasMessageContaining("no-op semantics");
	}

	@Test
	void smallEvidenceUsesTheSameScenarioSemanticsAndRejectsFalseDml() throws Exception {
		BenchmarkEvidence valid = evidence("initial", 12, true, true, 1);
		valid.write(output);
		BenchmarkEvidence invalid = new BenchmarkEvidence(
				valid.schemaVersion(), valid.syncContractHash(), valid.scenario(), valid.rowCount(),
				valid.seed(), valid.generatorVersion(), valid.chunkSize(), valid.batchStatus(), valid.exitStatus(),
				valid.outcomes(), valid.rows(), valid.checksums(),
				new BenchmarkEvidence.Dml(7, 0, "same", "same"), valid.persistence(), valid.heap(),
				valid.restart(), valid.timing(), valid.environment()
		);

		assertThatThrownBy(() -> invalid.write(output))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("initial semantics");
	}

	private static BenchmarkEvidence evidence(
			long rows,
			boolean restartPassed,
			boolean heapPlateau,
			long jdbcBatches
	) {
		return evidence(rows, restartPassed, heapPlateau, jdbcBatches, rows);
	}

	private static BenchmarkEvidence evidence(
			long rows,
			boolean restartPassed,
			boolean heapPlateau,
			long jdbcBatches,
			long distinctDoi
	) {
		return evidence("initial", rows, restartPassed, heapPlateau, jdbcBatches, distinctDoi);
	}

	private static BenchmarkEvidence evidence(
			String scenario,
			long rows,
			boolean restartPassed,
			boolean heapPlateau,
			long jdbcBatches
	) {
		return evidence(scenario, rows, restartPassed, heapPlateau, jdbcBatches, rows,
				42, "v1", SyncContract.hash(), 1000, 1000);
	}

	private static BenchmarkEvidence evidence(
			String scenario,
			long rows,
			boolean restartPassed,
			boolean heapPlateau,
			long jdbcBatches,
			long distinctDoi
	) {
		return evidence(scenario, rows, restartPassed, heapPlateau, jdbcBatches, distinctDoi,
				42, "v1", SyncContract.hash(), 1000, 1000);
	}

	private static BenchmarkEvidence evidence(
			String scenario,
			long rows,
			boolean restartPassed,
			boolean heapPlateau,
			long jdbcBatches,
			long distinctDoi,
			long seed,
			String generatorVersion,
			String syncContractHash,
			int chunkSize,
			int batchSize
	) {
		boolean initial = "initial".equals(scenario);
		return new BenchmarkEvidence(
				"v2", syncContractHash, scenario, rows, seed, generatorVersion, chunkSize,
				"COMPLETED", "COMPLETED",
				new BenchmarkEvidence.Outcomes(initial ? rows : 0, 0, initial ? 0 : rows, 0, 0, 0, 0),
				new BenchmarkEvidence.Rows(rows, rows, distinctDoi),
				new BenchmarkEvidence.Checksums("abc", "abc"),
				new BenchmarkEvidence.Dml(initial ? rows : 0, 0, "same", "same"),
				new BenchmarkEvidence.Persistence(205, 210, jdbcBatches, batchSize),
				new BenchmarkEvidence.Heap(
						50 * MIB, 80 * MIB, 64,
						50 * MIB, (heapPlateau ? 55 : 60) * MIB,
						(heapPlateau ? 5 : 10) * MIB, 8 * MIB,
						heapPlateau
				),
				new BenchmarkEvidence.Restart(true, restartPassed),
				new BenchmarkEvidence.Timing(0, 1200, 50),
				new BenchmarkEvidence.Environment("21", "Mac OS X", "aarch64", 10, 256 * MIB, "MySQL", "8.4")
		);
	}

	private static BenchmarkEvidence withHeap(BenchmarkEvidence evidence, BenchmarkEvidence.Heap heap) {
		return new BenchmarkEvidence(
				evidence.schemaVersion(), evidence.syncContractHash(), evidence.scenario(), evidence.rowCount(),
				evidence.seed(), evidence.generatorVersion(), evidence.chunkSize(),
				evidence.batchStatus(), evidence.exitStatus(), evidence.outcomes(), evidence.rows(),
				evidence.checksums(), evidence.dml(), evidence.persistence(), heap,
				evidence.restart(), evidence.timing(), evidence.environment()
		);
	}

	private static BenchmarkEvidence withEnvironment(
			BenchmarkEvidence evidence,
			BenchmarkEvidence.Environment environment
	) {
		return new BenchmarkEvidence(
				evidence.schemaVersion(), evidence.syncContractHash(), evidence.scenario(), evidence.rowCount(),
				evidence.seed(), evidence.generatorVersion(), evidence.chunkSize(),
				evidence.batchStatus(), evidence.exitStatus(), evidence.outcomes(), evidence.rows(),
				evidence.checksums(), evidence.dml(), evidence.persistence(), evidence.heap(),
				evidence.restart(), evidence.timing(), environment
		);
	}
}
