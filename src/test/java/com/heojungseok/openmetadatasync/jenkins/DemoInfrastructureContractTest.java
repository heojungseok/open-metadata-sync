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
		Path mysqlClientPath = Path.of("scripts/demo-mysql-client.sh");
		Path resetReplayPath = Path.of("scripts/demo-reset-replay.sh");
		Path summaryPath = Path.of("scripts/demo-replay-summary.sh");
		assertThat(upPath).exists();
		assertThat(downPath).exists();
		assertThat(fixturePath).exists();
		assertThat(mysqlClientPath).exists();
		assertThat(resetReplayPath).exists();
		assertThat(summaryPath).exists();

		String up = Files.readString(upPath);
		String down = Files.readString(downPath);
		String fixture = Files.readString(fixturePath);
		String mysqlClient = Files.readString(mysqlClientPath);
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
				.contains("source scripts/demo-mysql-client.sh")
				.contains("demo_validate_database_boundary")
				.contains("demo_verify_database_sentinel open_metadata")
				.contains("demo_mysql_stdin open_metadata")
				.contains("00000000-0000-0000-0000-00000000d001")
				.contains("scripts/demo-replay-fixture.sql")
				.contains("open_metadata")
				.contains("BEFORE: source=")
				.contains("replay-before-${REQUEST_ID}.json", "replay-before-${REQUEST_ID}.md")
				.doesNotContain("3307", "open_metadata_benchmark", "DROP DATABASE", "DROP SCHEMA");
		assertThat(summary)
				.contains("RESOLVED", "replay_count", "inserted_count", "target_count")
				.contains("source scripts/demo-mysql-client.sh", "demo_verify_database_sentinel open_metadata")
				.contains("AFTER: replay=")
				.contains("replay-after-${REQUEST_ID}.json", "replay-after-${REQUEST_ID}.md")
				.doesNotContain("no_op_count");
		assertThat(mysqlClient)
				.contains("DEMO_RUNTIME", "mysql:3306", "127.0.0.1", "3308")
				.contains("CURRENT_USER()", "demo_environment_guard")
				.contains("open_metadata@%", "open-metadata-sync-public-demo");
	}

	@Test
	void replayResetRejectsBoundaryMismatchAndContainerDriftBeforeSql(@TempDir Path tempDir)
			throws IOException, InterruptedException {
		Path docker = tempDir.resolve("docker");
		Path log = tempDir.resolve("docker.log");
		Files.writeString(docker, """
				#!/usr/bin/env bash
				printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
				if [[ "$1" == "exec" ]]; then
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
				.hasSize(1)
				.allMatch(line -> line.startsWith("exec ") && !line.contains(" -i "));
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
				.contains("source scripts/demo-mysql-client.sh")
				.contains("demo_verify_database_sentinel open_metadata_benchmark_preflight")
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

	@Test
	void liveLifecycleScriptsAreFixedFailClosedAndCredentialSeparated() throws IOException {
		String bootstrap = script("demo-bootstrap-live-db.sh");
		String reset = script("demo-reset-live.sh");
		String summary = script("demo-live-summary.sh");
		String cleanup = script("demo-cleanup-legacy.sh");

		assertThat(bootstrap)
				.contains("CREATE DATABASE IF NOT EXISTS open_metadata_live_demo")
				.contains("'open_metadata_live_demo'@'%'")
				.contains("REVOKE ALL PRIVILEGES, GRANT OPTION")
				.contains("GRANT ALL PRIVILEGES ON open_metadata_live_demo.*")
				.doesNotContain("GRANT ALL PRIVILEGES ON *.*");
		assertThat(reset)
				.contains("DEMO_LIVE_RESET_ACK", "open_metadata_live_demo")
				.contains("00000000-0000-0000-0000-00000000d100")
				.contains("expected_tables=")
				.doesNotContain("open_metadata_benchmark_preflight", "open_metadata;");
		assertThat(summary)
				.contains("created_from", "created_until", "max_items", "expected_count")
				.contains("collection_pages_fetched", "collection_reported_total", "collection_stop_reason")
				.contains("distinct_doi_count", "target_count", "checksum_mismatches")
				.contains("sync_execution_id", "batch_execution_id", "sync_contract_hash")
				.contains("10000", "COMPLETED", "status = 'OPEN'")
				.contains("live-crossref-${REQUEST_ID}.json", "live-crossref-${REQUEST_ID}.md");
		assertThat(cleanup)
				.contains("recovery_verification=PASS", "replay_schema_sha256")
				.contains("validation_scope=deployed", "legacy_grant_count", "-gt 0")
				.contains("grep -Fqx \"replay_data_sha256=$replay_data\" \"$LIVE_VALIDATION_RECEIPT_FILE\"")
				.contains("DROP DATABASE open_metadata_benchmark_preflight")
				.doesNotContain("DROP DATABASE open_metadata;", "docker volume", "prune");
	}

	@Test
	void noLifecycleScriptDeletesVolumesOrUsesBroadPrune() throws IOException {
		for (String name : new String[] {
				"demo-always-on-up.sh", "demo-always-on-down.sh", "demo-bootstrap-live-db.sh",
				"demo-cleanup-legacy.sh", "demo-export-recovery.sh", "demo-verify-recovery.sh",
				"demo-delete-old-images.sh"
		}) {
			Path path = Path.of("scripts", name);
			assertThat(path).exists();
			assertThat(Files.readString(path))
					.doesNotContain(
							"down -v", "system prune", "image prune",
							"volume rm open-metadata-sync-public-demo-mysql-data",
							"volume rm open-metadata-sync-public-demo-jenkins-home"
					);
		}
	}

	@Test
	void oldImageCleanupIsExactAndRequiresTheVerifiedRecoveryBundle() throws IOException {
		String cleanup = script("demo-delete-old-images.sh");
		assertThat(cleanup)
				.contains("DELETE_OLD_47461BE_IMAGES", "recovery_verification=PASS")
				.contains("LIVE_VALIDATION_RECEIPT_FILE", "validation_scope=deployed", "candidate_revision=")
				.contains("open-metadata-sync-demo-controller:47461be")
				.contains("open-metadata-sync-demo-agent:47461be")
				.contains("open-metadata-sync-demo-gateway:47461be")
				.contains("docker image inspect", "docker image rm")
				.doesNotContain("prune", "docker system", "docker volume");
	}

	@Test
	void deployedValidationReceiptIsBoundToCandidateVolumesAndBothPublicJobs() throws IOException {
		String verify = script("demo-verify-deployed-live.sh");
		assertThat(verify)
				.contains("recovery_verification=PASS", "candidate_revision=")
				.contains("open-metadata-sync-public-demo-mysql-data")
				.contains("open-metadata-sync-public-demo-jenkins-home")
				.contains("org.opencontainers.image.revision")
				.contains("docker exec -i open-metadata-sync-public-demo-gateway")
				.contains("open-metadata-sync-demo-10k", "open-metadata-sync-demo-replay")
				.contains("expected_count", "staging_count", "accounted_count", "pages_fetched")
				.contains("replay_schema_sha256", "replay_data_sha256", "replay_table_count")
				.contains("live_demo_validation=PASS", "validation_scope=deployed")
				.doesNotContain("docker volume rm", "docker image rm", "DROP DATABASE", "prune");
	}

	@Test
	void recoveryBundleContainsAndExercisesTheExactPreCleanupRollbackPath() throws IOException {
		String export = script("demo-export-recovery.sh");
		String rollback = script("demo-rollback-recovery.sh");
		assertThat(export)
				.contains("83e0fab8757590222f827d99e58d497939eccd88:compose.always-on-demo.yaml")
				.contains("legacy-compose.yaml", "SHA256SUMS");
		assertThat(rollback)
				.contains("RESTORE_OLD_PUBLIC_DEMO", "recovery_verification=PASS")
				.contains("open_metadata.demo_environment_guard", "Replay changed during rollback")
				.contains("legacy_grant_count", "-gt 0")
				.contains("mysql-volume-inspect.json", "jenkins-volume-inspect.json")
				.contains("old-demo-images.tar", "legacy-compose.yaml")
				.contains("open-metadata-sync-demo-10k", "DEMO_SCENARIO=NO_OP")
				.doesNotContain("docker volume rm", "down -v", "prune", "DROP DATABASE");
	}

	@Test
	void deterministicFullStackHarnessCoversFailureRotationSuccessReplayAndEgress() throws IOException {
		Path path = Path.of("scripts/demo-test-live-full-stack.sh");
		String e2e = Files.readString(path);
		assertThat(path).isExecutable();
		assertThat(e2e)
				.contains("org.opencontainers.image.revision", "docker network create --internal")
				.contains("http://crossref-stub:8080/metrics", "http://127.0.0.1:8080/healthz")
				.contains("start_stub 3", "Expected partial failed collection")
				.contains("live-old", "bootstrap_live", "sleep 305")
				.contains("open-metadata-sync-demo-10k", "open-metadata-sync-demo-replay")
				.contains("metrics['pages'] == list(range(1, 11))", "metrics['max_active'] == 1")
				.contains("/dev/tcp/api.crossref.org/443")
				.contains("validation_scope=local", "live-validation.env")
				.contains("open-metadata-sync-public-demo-mysql-data", "open-metadata-sync-public-demo-jenkins-home")
				.doesNotContain(
						"\\\"org.opencontainers.image.revision\\\"",
						"-v open-metadata-sync-public-demo-mysql-data",
						"-v open-metadata-sync-public-demo-jenkins-home",
						"docker rm -f open-metadata-sync-public-demo"
				);
	}

	@Test
	void recoveryRehearsalReconcilesRestoredReplayDataAndLegacyGrantBeforeJenkins() throws IOException {
		String verify = script("demo-verify-recovery.sh");
		assertThat(verify)
				.contains("replay_schema_sha256", "replay_data_sha256")
				.contains("replay_table_count", "legacy_grant_count")
				.contains("Scratch replay schema mismatch", "Scratch replay data mismatch")
				.contains("open-metadata-sync-demo-10k", "open-metadata-sync-demo-replay");
	}

	@Test
	void recoveryAndScratchScriptsNeverPutDatabaseSecretsInDockerEnvironmentArguments() throws IOException {
		for (String name : new String[] {
				"demo-export-recovery.sh", "demo-verify-recovery.sh",
				"demo-test-live-db-isolation.sh", "demo-test-live-full-stack.sh"
		}) {
			assertThat(script(name))
					.as(name)
					.doesNotContain("-e MYSQL_PWD=", "-e DB_PASSWORD=", "-e MYSQL_ROOT_PASSWORD=");
		}
	}

	@Test
	void liveDatabaseIsolationHasARepeatableScratchIntegrationTest() throws IOException {
		String test = script("demo-test-live-db-isolation.sh");
		assertThat(test)
				.contains("live-a", "live-b", "demo-bootstrap-live-db.sh")
				.contains("MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root")
				.contains("/run/secrets/client.cnf")
				.contains("SELECT * FROM open_metadata.replay_guard")
				.contains("TRUNCATE TABLE open_metadata.replay_guard")
				.contains("DROP DATABASE open_metadata")
				.contains("SELECT * FROM open_metadata_live_demo.live_guard")
				.contains("TRUNCATE TABLE open_metadata_live_demo.live_guard")
				.contains("DROP DATABASE open_metadata_live_demo")
				.doesNotContain(
						"-e MYSQL_ROOT_PASSWORD=", "-e MYSQL_PWD=", "-e DB_PASSWORD=",
						"open-metadata-sync-public-demo-mysql-data", "down -v", "prune"
				);
		assertThat(test.indexOf("mysql --protocol=TCP -hmysql -P3306 -uroot -e \"SELECT 1\""))
				.isGreaterThanOrEqualTo(0)
				.isLessThan(test.indexOf("bootstrap_live \"$secret_dir/live-a\""));
	}

	private static String script(String name) throws IOException {
		return Files.readString(Path.of("scripts", name));
	}
}
