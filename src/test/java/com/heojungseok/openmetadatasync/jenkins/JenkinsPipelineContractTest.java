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
		assertThat(pipeline)
				.contains("--spring.batch.job.enabled=true")
				.contains("--spring.batch.job.name=crossrefSyncJob")
				.doesNotContain("--spring.batch.job.name=dataPlaneBenchmarkJob")
				.contains("build/jenkins/crossref-outcome.properties");
		assertResultMapping(pipeline);
	}

	@Test
	void benchmarkPipelineUsesTheSameNonWaitingLockAndArchivesOnlyApprovedEvidence() throws IOException {
		String pipeline = pipeline("Jenkinsfile.benchmark");

		assertThat(parameters(pipeline)).containsExactlyInAnyOrder(
				"REQUEST_ID", "PROFILE", "ROW_COUNT", "SEED", "GENERATOR_VERSION", "SCENARIO",
				"CHUNK_SIZE", "HIBERNATE_BATCH_SIZE", "EVIDENCE_DIRECTORY", "FAIL_FIRST_EXECUTION"
		);
		assertSharedManualSafety(pipeline);
		assertThat(pipeline)
				.contains("--spring.batch.job.enabled=true")
				.contains("--spring.batch.job.name=dataPlaneBenchmarkJob")
				.doesNotContain("--spring.batch.job.name=crossrefSyncJob")
				.contains("build/jenkins/benchmark-outcome.properties")
				.contains("benchmark-evidence/**/*.json", "benchmark-evidence/**/*.md")
				.doesNotContain("archiveArtifacts artifacts: '**/*'", "archiveArtifacts artifacts: 'build/**'");
		assertResultMapping(pipeline);
	}

	private static void assertSharedManualSafety(String pipeline) {
		assertThat(pipeline)
				.contains("resource: '" + LOCK_RESOURCE + "'", "skipIfLocked: true")
				.contains("enteredLock = true", "currentBuild.result = 'NOT_BUILT'")
				.doesNotContain("disableConcurrentBuilds", "triggers {", "cron(", "pollSCM(", "git push", "git commit", "DROP DATABASE",
						"TRUNCATE ", "docker volume", "cleanWs(")
				.doesNotContain("password(name:", "PASSWORD', defaultValue:");
		assertThat(pipeline.indexOf("lock(resource:")).isLessThan(pipeline.indexOf("java -jar"));
		assertThat(pipeline.indexOf("java -jar")).isLessThan(pipeline.indexOf("archiveArtifacts"));
		assertThat(pipeline.indexOf("archiveArtifacts")).isLessThan(pipeline.indexOf("if (!enteredLock)"));
		assertThat(count(pipeline, "java -jar")).isEqualTo(1);
	}

	private static void assertResultMapping(String pipeline) {
		assertThat(pipeline)
				.contains("status == 0", "currentBuild.result = 'SUCCESS'")
				.contains("status == 2", "currentBuild.result = 'UNSTABLE'")
				.contains("status == 3", "currentBuild.result = 'NOT_BUILT'")
				.contains("currentBuild.result = 'FAILURE'")
				.contains("test -f", "grep -qx 'code=${status}'");
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
