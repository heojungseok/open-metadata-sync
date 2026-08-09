package com.heojungseok.openmetadatasync.jenkins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JenkinsPipelineContractTest {

	private static final Pattern PARAMETER = Pattern.compile("(?:string|choice|booleanParam)\\(name: '([^']+)'");
	private static final String LOCK_RESOURCE = "open-metadata-sync-data-plane";

	@Test
	void crossrefPipelineIsManualAllowlistedAndMapsEveryResult() throws IOException {
		String pipeline = pipeline("Jenkinsfile.crossref");

		assertThat(parameters(pipeline)).containsExactlyInAnyOrder(
				"REQUEST_ID", "MODE", "CREATED_FROM", "CREATED_UNTIL", "MAX_ITEMS",
				"SOURCE_NAME", "BOOTSTRAP_INDEXED_FROM", "INDEXED_FROM_UTC", "INDEXED_UNTIL_UTC",
				"SOURCE_EXECUTION_ID", "CHUNK_SIZE", "HIBERNATE_BATCH_SIZE"
		);
		assertSharedManualSafety(pipeline);
		assertProjectJdk(pipeline);
		assertFolderScopedDbEnvironment(pipeline);
		assertThat(pipeline)
				.contains("--spring.batch.job.enabled=true")
				.contains("--spring.batch.job.name=crossrefSyncJob")
				.doesNotContain("--spring.batch.job.name=dataPlaneBenchmarkJob")
				.doesNotContain(
						"-Xmx256m", "Preflight qualification",
						"BENCHMARK_PROCESSING_FAILURE", "BENCHMARK_QUALIFICATION_NOT_MET"
				)
				.contains("build/jenkins/crossref-outcome.properties")
				.contains("rm -f -- build/jenkins/crossref-outcome.properties")
				.contains("grep -Fqx 'requestId=${params.REQUEST_ID}'")
				.contains("grep -Fqx 'job=crossrefSyncJob'")
				.contains("grep -Fqx 'mode=${params.MODE}'");
		assertOutcomeIsRemovedBeforeLaunch(pipeline, "crossref-outcome.properties");
		assertResultMapping(pipeline);
	}

	@Test
	void benchmarkPipelineUsesTheSameNonWaitingLockAndArchivesOnlyApprovedEvidence() throws IOException {
		String pipeline = pipeline("Jenkinsfile.benchmark");

		assertThat(parameters(pipeline)).containsExactlyInAnyOrder(
				"REQUEST_ID", "BENCHMARK_GATE", "SEED", "GENERATOR_VERSION", "WORKLOAD_SCENARIO",
				"CHUNK_SIZE", "HIBERNATE_BATCH_SIZE", "FAIL_FIRST_EXECUTION"
		);
		assertSharedManualSafety(pipeline);
		assertProjectJdk(pipeline);
		assertFolderScopedDbEnvironment(pipeline);
		assertThat(pipeline)
				.contains("choice(name: 'BENCHMARK_GATE', choices: ['PREFLIGHT', 'MAIN']")
				.contains("PREFLIGHT: [profile: 'benchmark-preflight', rowCount: '100000']")
				.contains("MAIN: [profile: 'benchmark', rowCount: '1000000']")
				.contains("choice(name: 'WORKLOAD_SCENARIO', choices: ['initial', 'no-op']")
				.contains("requireValue('WORKLOAD_SCENARIO', params.WORKLOAD_SCENARIO, 'initial|no-op')")
				.contains("--spring.batch.job.enabled=true")
				.contains("--spring.batch.job.name=dataPlaneBenchmarkJob")
				.contains("java -Xms128m -Xmx256m -jar")
				.doesNotContain("--spring.batch.job.name=crossrefSyncJob")
				.doesNotContain("name: 'PROFILE'", "name: 'ROW_COUNT'", "name: 'SCENARIO'", "EVIDENCE_DIRECTORY")
				.contains("evidenceDirectory=benchmark-evidence,java.lang.String,false")
				.contains("build/jenkins/benchmark-outcome.properties")
				.contains("rm -f -- build/jenkins/benchmark-outcome.properties")
				.contains("grep -Fqx 'requestId=${params.REQUEST_ID}'")
				.contains("grep -Fqx 'job=dataPlaneBenchmarkJob'", "grep -Fqx 'mode=BENCHMARK'")
				.contains("benchmark-${gate.rowCount}-${params.WORKLOAD_SCENARIO}.json")
				.contains("benchmark-${gate.rowCount}-${params.WORKLOAD_SCENARIO}.md")
				.contains("benchmark-100000-initial.json", "benchmark-100000-initial.md")
				.contains("benchmark-100000-no-op.json", "benchmark-100000-no-op.md")
				.contains("grep -Fqx '| Processing result | PASS |' ${currentMarkdown}")
				.contains("grep -Fqx '| Preflight qualification | PASS |' ${currentMarkdown}")
				.contains("grep -Fqx '| Restart qualification | PASS |' ${currentMarkdown}")
				.contains("grep -Fqx '| Heap retention qualification | PASS |' ${currentMarkdown}")
				.contains("grep -Fqx '| Persistence qualification | PASS |' ${currentMarkdown}")
				.contains("params.BENCHMARK_GATE != 'PREFLIGHT'")
				.contains("BENCHMARK_PROCESSING_FAILURE [벤치마크 처리 검증 실패]")
				.contains("BENCHMARK_QUALIFICATION_NOT_MET [벤치마크 자격 미충족]")
				.contains("currentBuild.result = 'UNSTABLE'")
				.contains("status == 0 || status == 2", "status == 3")
				.contains("if (outcomeValid && successLike && evidenceFilesValid)")
				.doesNotContain("BENCHMARK_GATE_FAILURE [벤치마크 판정 실패]")
				.doesNotContain("benchmark-evidence/**/*.json", "benchmark-evidence/**/*.md", "..")
				.doesNotContain("archiveArtifacts artifacts: '**/*'", "archiveArtifacts artifacts: 'build/**'");
		assertOutcomeIsRemovedBeforeLaunch(pipeline, "benchmark-outcome.properties");
		assertResultMapping(pipeline);
	}

	private static void assertSharedManualSafety(String pipeline) {
		String launch = "-jar build/libs/open-metadata-sync-0.0.1-SNAPSHOT.jar";
		assertThat(pipeline)
				.contains("resource: '" + LOCK_RESOURCE + "'", "skipIfLocked: true")
				.contains("enteredLock = true", "currentBuild.result = 'NOT_BUILT'")
				.doesNotContain("disableConcurrentBuilds", "triggers {", "cron(", "pollSCM(", "git push", "git commit", "DROP DATABASE",
						"TRUNCATE ", "docker volume", "cleanWs(")
				.doesNotContain("password(name:", "PASSWORD', defaultValue:");
		assertThat(pipeline.indexOf("lock(resource:")).isLessThan(pipeline.indexOf(launch));
		assertThat(pipeline.indexOf(launch)).isLessThan(pipeline.indexOf("archiveArtifacts"));
		assertThat(pipeline.indexOf("archiveArtifacts")).isLessThan(pipeline.indexOf("if (!enteredLock)"));
		assertThat(count(pipeline, launch)).isEqualTo(1);
	}

	private static void assertProjectJdk(String pipeline) {
		assertThat(pipeline)
				.contains("tools {", "jdk 'jdk21'");
		assertThat(pipeline.indexOf("jdk 'jdk21'"))
				.isLessThan(pipeline.indexOf("parameters {"));
	}

	private static void assertFolderScopedDbEnvironment(String pipeline) {
		assertThat(pipeline)
				.contains("withEnv(['DB_HOST=', 'DB_PORT='])")
				.contains("withFolderProperties {")
				.contains("requireValue('DB_HOST', env.DB_HOST, '[A-Za-z0-9._-]+')")
				.contains("requireValue('DB_PORT', env.DB_PORT, '[1-9][0-9]{0,4}')")
				.doesNotContain("string(name: 'DB_HOST'", "string(name: 'DB_PORT'",
						"DB_HOST=localhost", "DB_PORT=3307");
		assertThat(pipeline.indexOf("withEnv(['DB_HOST=', 'DB_PORT='])"))
				.isLessThan(pipeline.indexOf("withFolderProperties {"));
		assertThat(pipeline.indexOf("withFolderProperties {")).isLessThan(pipeline.indexOf("lock(resource:"));
		assertThat(pipeline.indexOf("requireValue('DB_HOST'")).isLessThan(pipeline.indexOf("lock(resource:"));
		assertThat(pipeline.indexOf("requireValue('DB_PORT'")).isLessThan(pipeline.indexOf("lock(resource:"));
	}

	private static void assertResultMapping(String pipeline) {
		assertThat(pipeline)
				.contains("status == 0", "currentBuild.result = 'SUCCESS'")
				.contains("status == 2", "currentBuild.result = 'UNSTABLE'")
				.contains("status == 3", "currentBuild.result = 'NOT_BUILT'")
				.contains("currentBuild.result = 'FAILURE'")
				.contains("test -f", "grep -Fqx 'code=${status}'");
	}

	private static void assertOutcomeIsRemovedBeforeLaunch(String pipeline, String outcomeFile) {
		assertThat(pipeline.indexOf("rm -f -- build/jenkins/" + outcomeFile))
				.isGreaterThan(pipeline.indexOf("lock(resource:"))
				.isLessThan(pipeline.indexOf("-jar build/libs/open-metadata-sync-0.0.1-SNAPSHOT.jar"));
	}

	private static int count(String text, String needle) {
		return (text.length() - text.replace(needle, "").length()) / needle.length();
	}

	private static Set<String> parameters(String pipeline) {
		Matcher matcher = PARAMETER.matcher(pipeline);
		java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
		while (matcher.find()) {
			names.add(matcher.group(1));
		}
		return names;
	}

	private static String pipeline(String name) throws IOException {
		return Files.readString(Path.of(name));
	}

	@Test
	void applicationHasNoAutomaticLaunchSurface() throws IOException {
		String sources;
		try (var paths = Files.walk(Path.of("src/main"))) {
			sources = paths.filter(Files::isRegularFile)
					.map(path -> {
						try {
							return Files.readString(path);
						} catch (IOException exception) {
							throw new java.io.UncheckedIOException(exception);
						}
					})
					.reduce("", (left, right) -> left + "\n" + right);
		}
		assertThat(sources).doesNotContain("@Scheduled", "@RestController", "@Controller");
		assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
				.contains("job:\n      enabled: false");
	}
}
