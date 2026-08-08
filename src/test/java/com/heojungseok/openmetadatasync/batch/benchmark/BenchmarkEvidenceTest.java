package com.heojungseok.openmetadatasync.batch.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.heojungseok.openmetadatasync.batch.parameter.SyncContract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkEvidenceTest {

	@TempDir
	Path output;

	@Test
	void writesStableSecretFreeJsonAndMarkdownWithTheCompleteEvidenceContract() throws Exception {
		BenchmarkEvidence evidence = evidence(100_000, true, true, 4);

		BenchmarkEvidence.Files first = evidence.write(output);
		String json = java.nio.file.Files.readString(first.json());
		String markdown = java.nio.file.Files.readString(first.markdown());
		BenchmarkEvidence.Files second = evidence.write(output);

		assertThat(first.json()).hasFileName("benchmark-initial.json");
		assertThat(first.markdown()).hasFileName("benchmark-initial.md");
		assertThat(Files.readString(second.json())).isEqualTo(json);
		assertThat(json).contains(
				"\"schemaVersion\" : \"v1\"",
				"\"outcomes\"", "\"checksums\"", "\"dml\"", "\"persistence\"",
				"\"heap\"", "\"restart\"", "\"environment\""
		);
		assertThat(markdown).contains("# Data Plane Benchmark", "Scenario | initial", "Preflight gate | PASS");
		assertThat((json + markdown).toLowerCase())
				.doesNotContain("password", "secret", "jdbc:mysql", "username");
	}

	@Test
	void preflightRequiresOneHundredThousandRowsRestartHeapChecksumAndJdbcBatchEvidence() {
		evidence(100_000, true, true, 1).requirePreflight();

		assertThatThrownBy(() -> evidence(99_999, true, true, 1).requirePreflight())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("100000");
		assertThatThrownBy(() -> evidence(100_000, false, true, 1).requirePreflight())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("restart");
		assertThatThrownBy(() -> evidence(100_000, true, false, 1).requirePreflight())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("heap");
		assertThatThrownBy(() -> evidence(100_000, true, true, 0).requirePreflight())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("batch");
		assertThatThrownBy(() -> evidence(100_000, true, true, 1, 99_999).requirePreflight())
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("row integrity");
	}

	@Test
	void millionGateRequiresExactInitialAndNoOpPreflightProfiles() throws Exception {
		evidence("initial", 100_000, true, true, 1).write(output);
		evidence("no-op", 100_000, true, true, 1).write(output);

		BenchmarkEvidence.requireMillionGate(output, 42, "v1", SyncContract.hash(), 1000, 1000);

		evidence("no-op", 99_999, true, true, 1).write(output);
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
		return new BenchmarkEvidence(
				"v1", syncContractHash, scenario, rows, seed, generatorVersion, chunkSize,
				"COMPLETED", "COMPLETED",
				new BenchmarkEvidence.Outcomes(rows, 0, 0, 0, 0, 0, 0),
				new BenchmarkEvidence.Rows(rows, rows, distinctDoi),
				new BenchmarkEvidence.Checksums("abc", "abc"),
				new BenchmarkEvidence.Dml(rows, 0, "before", "after"),
				new BenchmarkEvidence.Persistence(205, 210, jdbcBatches, batchSize),
				new BenchmarkEvidence.Heap(50, 80, 8, heapPlateau),
				new BenchmarkEvidence.Restart(true, restartPassed),
				new BenchmarkEvidence.Timing(0, 1200, 50),
				new BenchmarkEvidence.Environment("21", "Mac OS X", "aarch64", 10, 1024, "MySQL", "8.4")
		);
	}
}
