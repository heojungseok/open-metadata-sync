package com.heojungseok.openmetadatasync.jenkins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DemoInfrastructureContractTest {

	@Test
	void demoComposeUsesAnIsolatedLoopbackMysqlVolume() throws IOException {
		Path path = Path.of("compose.demo.yaml");
		assertThat(path).exists();
		String compose = Files.readString(path);

		assertThat(compose)
				.contains("name: open-metadata-sync-demo")
				.contains("container_name: open-metadata-sync-demo-mysql")
				.contains("mysql:8.4.10")
				.contains("127.0.0.1:3308:3306")
				.contains("open-metadata-sync-demo-mysql-data:/var/lib/mysql")
				.contains("name: open-metadata-sync-demo-mysql-data")
				.contains("-p$${MYSQL_ROOT_PASSWORD}")
				.doesNotContain(
						"3306:3306",
						"open-metadata-sync-mysql:/var/lib/mysql",
						"-p${DEMO_MYSQL_ROOT_PASSWORD}"
				);
	}

	@Test
	void demoLifecycleKeepsTunnelAndDatabaseShutdownOrdered() throws IOException {
		Path upPath = Path.of("scripts/demo-up.sh");
		Path downPath = Path.of("scripts/demo-down.sh");
		Path fixturePath = Path.of("scripts/demo-replay-fixture.sql");
		Path resetReplayPath = Path.of("scripts/demo-reset-replay.sh");
		Path summaryPath = Path.of("scripts/demo-replay-summary.sh");
		assertThat(upPath).exists();
		assertThat(downPath).exists();
		assertThat(fixturePath).exists();
		assertThat(resetReplayPath).exists();
		assertThat(summaryPath).exists();

		String up = Files.readString(upPath);
		String down = Files.readString(downPath);
		String fixture = Files.readString(fixturePath);
		String resetReplay = Files.readString(resetReplayPath);
		String summary = Files.readString(summaryPath);
		assertThat(up)
				.contains("docker compose -f compose.demo.yaml up -d mysql")
				.contains("./gradlew bootJar")
				.contains("JAVA_HOME=$(/usr/libexec/java_home -v 21)")
				.contains("\"$JAVA_HOME/bin/java\" -jar")
				.contains("for profile in actual benchmark-preflight")
				.contains("--spring.profiles.active=\"$profile\"")
				.contains("scripts/demo-replay-fixture.sql")
				.contains("curl --fail --silent --show-error http://127.0.0.1:9090/login")
				.contains("cloudflared tunnel --url http://127.0.0.1:9090");
		assertThat(up.indexOf("scripts/demo-replay-fixture.sql"))
				.isLessThan(up.indexOf("cloudflared tunnel --url"));
		assertThat(down)
				.contains("kill \"$(cat \"$PID_FILE\")\"")
				.contains("docker compose -f compose.demo.yaml stop mysql")
				.doesNotContain("docker compose -f compose.demo.yaml down", "docker volume rm");
		assertThat(down.indexOf("kill \"$(cat \"$PID_FILE\")\""))
				.isLessThan(down.indexOf("docker compose -f compose.demo.yaml stop mysql"));
		assertThat(fixture)
				.contains("00000000-0000-0000-0000-00000000d001")
				.contains("10.5555/demo-replay")
				.contains("'PERSISTENCE', 'DEMO_TRANSIENT_WRITE'")
				.contains("'OPEN', 0")
				.contains("WHERE NOT EXISTS")
				.contains("UPDATE sync_error", "status = 'RESOLVED'")
				.contains("DELETE FROM work WHERE doi = '10.5555/demo-replay';")
				.contains("ON DUPLICATE KEY UPDATE");
		assertThat(fixture.lines().map(String::trim).filter(line -> line.startsWith("DELETE ")))
				.containsExactly("DELETE FROM work WHERE doi = '10.5555/demo-replay';");
		assertThat(resetReplay)
				.contains("DEMO_REPLAY_RESET_ACK:?DEMO_REPLAY_RESET_ACK is required")
				.contains("[[ \"$DEMO_REPLAY_RESET_ACK\" != \"REPLAY_ERRORS\" ]]")
				.contains("[[ \"$DB_PORT\" != \"3308\" ]]")
				.contains("DEMO_DB_CONTAINER:-open-metadata-sync-demo-mysql")
				.contains("com.docker.compose.project", "open-metadata-sync-demo")
				.contains("127.0.0.1:3308")
				.contains("open-metadata-sync-demo-mysql-data:/var/lib/mysql")
				.contains("00000000-0000-0000-0000-00000000d001")
				.contains("scripts/demo-replay-fixture.sql")
				.contains("open_metadata")
				.contains("BEFORE: source=")
				.contains("replay-before-${REQUEST_ID}.json", "replay-before-${REQUEST_ID}.md")
				.doesNotContain("3307", "open_metadata_benchmark", "DROP DATABASE", "DROP SCHEMA");
		assertThat(summary)
				.contains("RESOLVED", "replay_count", "inserted_count", "target_count")
				.contains("AFTER: replay=")
				.contains("replay-after-${REQUEST_ID}.json", "replay-after-${REQUEST_ID}.md")
				.doesNotContain("no_op_count");
	}

	@Test
	void replayResetRejectsBoundaryMismatchAndContainerDriftBeforeSql(@TempDir Path tempDir)
			throws IOException, InterruptedException {
		Path docker = tempDir.resolve("docker");
		Path log = tempDir.resolve("docker.log");
		Files.writeString(docker, """
				#!/usr/bin/env bash
				printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
				if [[ "$1" == "inspect" ]]; then
				  printf 'wrong-target\n'
				fi
				""");
		assertThat(docker.toFile().setExecutable(true)).isTrue();

		for (Map.Entry<String, String> mismatch : List.of(
				Map.entry("DB_HOST", "db.example.com"),
				Map.entry("DB_PORT", "3307"),
				Map.entry("DEMO_DB_CONTAINER", "another-container"),
				Map.entry("SOURCE_EXECUTION_ID", "00000000-0000-0000-0000-00000000ffff")
		)) {
			Files.deleteIfExists(log);
			ProcessBuilder process = replayResetProcess(tempDir, log);
			process.environment().put(mismatch.getKey(), mismatch.getValue());
			assertThat(run(process)).isNotZero();
			assertThat(log).doesNotExist();
		}

		Files.deleteIfExists(log);
		assertThat(run(replayResetProcess(tempDir, log))).isNotZero();
		assertThat(Files.readAllLines(log))
				.isNotEmpty()
				.allMatch(line -> line.startsWith("inspect "));
	}

	private static ProcessBuilder replayResetProcess(Path fakeDockerDirectory, Path log) {
		ProcessBuilder process = new ProcessBuilder("bash", "scripts/demo-reset-replay.sh");
		Map<String, String> environment = process.environment();
		environment.put("PATH", fakeDockerDirectory + ":" + environment.get("PATH"));
		environment.put("FAKE_DOCKER_LOG", log.toString());
		environment.put("REQUEST_ID", "guard-contract");
		environment.put("SOURCE_EXECUTION_ID", "00000000-0000-0000-0000-00000000d001");
		environment.put("DB_HOST", "127.0.0.1");
		environment.put("DB_PORT", "3308");
		environment.put("DB_USERNAME", "unused");
		environment.put("DB_PASSWORD", "unused");
		environment.put("DEMO_REPLAY_RESET_ACK", "REPLAY_ERRORS");
		environment.put("DEMO_DB_CONTAINER", "open-metadata-sync-demo-mysql");
		return process.redirectErrorStream(true);
	}

	private static int run(ProcessBuilder process) throws IOException, InterruptedException {
		Process running = process.start();
		running.getInputStream().readAllBytes();
		return running.waitFor();
	}

	@Test
	void initialResetIsExplicitAndLimitedToTheDemoPreflightTables() throws IOException {
		Path resetPath = Path.of("scripts/demo-reset-10k.sh");
		assertThat(resetPath).exists();
		String reset = Files.readString(resetPath);

		assertThat(reset)
				.contains("DEMO_RESET_ACK:?DEMO_RESET_ACK is required")
				.contains("[[ \"$DEMO_RESET_ACK\" != \"INITIAL\" ]]")
				.contains("[[ \"$DB_PORT\" != \"3308\" ]]")
				.contains("DEMO_DB_CONTAINER:-open-metadata-sync-demo-mysql")
				.contains("open_metadata_benchmark_preflight")
				.contains("TRUNCATE TABLE work;")
				.contains("TRUNCATE TABLE BATCH_JOB_INSTANCE;")
				.contains("INSERT INTO BATCH_STEP_EXECUTION_SEQ (ID, UNIQUE_KEY) VALUES (0, '0');")
				.contains("INSERT INTO BATCH_JOB_EXECUTION_SEQ (ID, UNIQUE_KEY) VALUES (0, '0');")
				.contains("INSERT INTO BATCH_JOB_INSTANCE_SEQ (ID, UNIQUE_KEY) VALUES (0, '0');")
				.contains("SELECT COUNT(*) FROM work")
				.doesNotContain(
						"DROP DATABASE", "DROP SCHEMA", "3307",
						"USE open_metadata;", "open_metadata_benchmark;"
				);
	}
}
