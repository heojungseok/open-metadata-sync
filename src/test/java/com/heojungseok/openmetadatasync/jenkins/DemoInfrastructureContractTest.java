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
				.contains("DROP USER IF EXISTS 'open_metadata_live_demo'@'%'")
				.contains("CREATE USER 'open_metadata_live_demo'@'%'")
				.contains("mysql.role_edges", "mysql.default_roles")
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
				.contains("validation_scope=deployed", "legacy_grant", "-gt 0")
				.contains("visitor_path=PASS", "otp_access=PASS")
				.contains("grep -Fqx \"replay_data_sha256=$replay_data\" \"$LIVE_VALIDATION_RECEIPT_FILE\"")
				.contains("DROP DATABASE open_metadata_benchmark_preflight")
				.doesNotContain("DROP DATABASE open_metadata;", "docker volume", "prune");
	}

	@Test
	void unifiedLivePreflightAndSummaryAreDeterministicSafeAndNoTargetAware() throws IOException {
		String preflight = script("demo-live-preflight.sh");
		String summary = script("demo-crossref-summary.sh");
		String mysql = script("demo-mysql-client.sh");

		assertThat(preflight)
				.contains("execution.mode = 'BACKFILL'", "execution.business_status = 'COMPLETED_WITH_ERRORS'")
				.contains("error.status = 'OPEN'", "staging.execution_id = error.execution_id")
				.contains("ORDER BY execution.started_at DESC, BIN_TO_UUID(execution.id) DESC")
				.contains("MAX(error.error_key)", "selected_error_upper_bound", "selected_error_count")
				.contains("OPEN_ERRORS_REQUIRE_REPLAY", "OPERATOR_REVIEW", "NO_REPLAY_TARGET")
				.contains("[[ \"$total_open_errors\" == \"0\" ]]", "-z \"$source_execution_id\"",
						"next_mode=OPERATOR_REVIEW")
				.doesNotContain("message", "source_json", "doi", "url", "cursor");
		assertThat(summary)
				.contains("schema_version", "total_open_errors", "replayable_open_errors")
				.contains("selected_error_upper_bound", "next_mode", "error_groups")
				.contains("source_remaining_open_errors", "source_resolved_errors")
				.contains("GROUP_CONCAT(JSON_OBJECT", "ORDER BY grouped.safe_type, grouped.safe_code")
				.contains("BACKFILL SUCCESS evidence is inconsistent", "REPLAY_ERRORS SUCCESS evidence is inconsistent")
				.contains("VALIDATION", "CONFLICT", "OTHER")
				.contains("crossref-${REQUEST_ID}.json", "crossref-${REQUEST_ID}.md", "crossref-${REQUEST_ID}.properties")
				.doesNotContain("error.message", "source_json", "staging.doi", "staging.url", "cursor_value");
		assertThat(mysql).contains("demo_live_data_hash", "--no-create-info", "--no-tablespaces",
				"open_metadata_live_demo");
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
	void deployedValidationReceiptIsBoundToCandidateVolumesAndUnifiedActualJob() throws IOException {
		String verify = script("demo-verify-deployed-live.sh");
		assertThat(verify)
				.contains("candidate_revision=")
				.contains("VISITOR_EVIDENCE_FILE", "visitor_path=$(evidence_value visitor_path)",
						"otp_access=$(evidence_value otp_access)")
				.contains("live_cf_ray", "replay_cf_ray", "docker logs")
				.contains("open-metadata-sync-public-demo-mysql-data")
				.contains("open-metadata-sync-public-demo-jenkins-home")
				.contains("org.opencontainers.image.revision")
				.contains("docker exec -i open-metadata-sync-public-demo-gateway")
					.contains("open-metadata-sync-demo-crossref", "MODE", "BACKFILL", "REPLAY_ERRORS")
					.contains("SUCCESS", "NOT_BUILT", "NO_REPLAY_TARGET")
					.contains("expected_count", "staging_count", "accounted_count", "pages_fetched")
				.contains("replay_schema_sha256", "replay_data_sha256", "replay_table_count")
				.contains("live_demo_validation=PASS", "validation_scope=deployed")
				.doesNotContain("RECOVERY_BUNDLE", "recovery_verification=PASS")
				.doesNotContain("docker volume rm", "docker image rm", "DROP DATABASE", "prune");
	}

	@Test
	void deployedValidationRejectsLocalOrMismatchedVisitorEvidence(@TempDir Path tempDir)
			throws IOException, InterruptedException {
		Path evidence = tempDir.resolve("visitor.env");
		String liveRequest = "public-1786301622855-78e093067b44380f";
		String replayRequest = "public-1786301922855-11e093067b44380f";

		Files.writeString(evidence, visitorEvidence(liveRequest, replayRequest, "local", "1234567890abcdef-ICN"));
		assertThat(run(visitorValidationProcess(evidence, liveRequest, replayRequest))).isNotZero();

		Files.writeString(evidence, visitorEvidence("public-1-wrongtoken", replayRequest,
				"abcdef1234567890-ICN", "1234567890abcdef-ICN"));
		assertThat(run(visitorValidationProcess(evidence, liveRequest, replayRequest))).isNotZero();

		Files.writeString(evidence, visitorEvidence(liveRequest, replayRequest,
				"abcdef1234567890-ICN", "1234567890abcdef-ICN"));
		assertThat(run(visitorValidationProcess(evidence, liveRequest, replayRequest))).isZero();
	}

	private static ProcessBuilder visitorValidationProcess(Path evidence, String liveRequest, String replayRequest) {
		ProcessBuilder process = new ProcessBuilder("bash", "scripts/demo-verify-deployed-live.sh");
		Map<String, String> environment = process.environment();
		environment.put("CANDIDATE_REVISION", "0123456789abcdef0123456789abcdef01234567");
		environment.put("LIVE_REQUEST_ID", liveRequest);
		environment.put("REPLAY_REQUEST_ID", replayRequest);
		environment.put("LIVE_VALIDATION_RECEIPT_FILE", evidence.resolveSibling("unused-receipt.env").toString());
		environment.put("VISITOR_EVIDENCE_FILE", evidence.toString());
		environment.put("VALIDATE_VISITOR_EVIDENCE_ONLY", "1");
		return process.redirectErrorStream(true);
	}

	private static String visitorEvidence(String liveRequest, String replayRequest, String liveRay, String replayRay) {
		return """
				visitor_path=PASS
				otp_access=PASS
				public_hostname=demo.heojungseok.com
				live_request_id=%s
				live_cf_ray=%s
				live_chunk_size=1000
				replay_request_id=%s
				replay_cf_ray=%s
				""".formatted(liveRequest, liveRay, replayRequest, replayRay);
	}

	@Test
	void recoveryBundleEncryptsAndVerifiesTheCurrentLiveDemoRatherThanTheSyntheticDemo() throws IOException {
		String export = script("demo-export-recovery.sh");
		String verify = script("demo-verify-recovery.sh");
		String guards = script("demo-test-recovery-guards.sh");
		assertThat(export)
				.contains("CANDIDATE_REVISION", "LIVE_VALIDATION_RECEIPT_FILE")
				.contains("RECOVERY_KEY_FILE", "RECOVERY_PUBLIC_KEY_FILE")
				.contains("open_metadata_live_demo", "open_metadata", "candidate-compose.yaml", "SHA256SUMS")
				.contains("RECOVERY_KEY_FILE", "umask 077", "chmod 700")
				.contains("openssl enc -aes-256-cbc -pbkdf2", "openssl pkeyutl -sign -rawin", ".enc")
					.contains("wait_for_runtime", "Public demo runtime did not recover after export")
					.contains("open-metadata-sync-demo-crossref")
				.contains("mysql_volume_inspect_sha256", "jenkins_volume_inspect_sha256")
				.contains("docker inspect -f '{{.Image}}'", "docker image inspect -f '{{.Id}}'")
				.contains("docker save", "mysqldump", "-czf -")
				.contains("Plaintext recovery secret remained")
				.doesNotContain("mktemp -d \"$RECOVERY_ROOT", "sensitive_dir")
				.doesNotContain("47461be", "open_metadata_benchmark_preflight", "legacy-compose.yaml");
		assertThat(verify)
				.contains("recovery_verification=PENDING", "recovery_verification=PASS")
				.contains("RECOVERY_KEY_FILE", "openssl enc -d -aes-256-cbc -pbkdf2")
				.contains("openssl pkeyutl -verify -rawin")
				.contains("open_metadata_live_demo", "open_metadata")
				.contains("${label}_schema_sha256", "${label}_data_sha256")
				.contains("mysql-volume-inspect.json", "jenkins-volume-inspect.json")
				.contains("decrypt_file", "candidate-images.tar")
				.contains("open-metadata-sync-live-recovery-agent", "open-metadata-sync-live-recovery-controller")
				.contains("open-metadata-sync-live-recovery-gateway", "open-metadata-sync-live-recovery-proxy")
				.contains("open-metadata-sync-live-recovery-proxy-secrets", "chown 65532:65532")
					.contains("demo-agent", "open-metadata-sync-demo-crossref", "recovery_replay=")
					.contains("MODE=REPLAY_ERRORS", "CHUNK_SIZE=1000", "crossref-")
				.doesNotContain("chmod 644 \"$secret_dir/agent_ssh_key.pub\" \"$secret_dir/crossref-mailto\"")
				.doesNotContain("47461be", "open_metadata_benchmark_preflight", "legacy-compose.yaml");
		assertThat(Path.of("scripts/demo-rollback-recovery.sh")).doesNotExist();
		assertThat(guards)
				.contains("demo-delete-old-images.sh", "signature (verification )?failure")
				.contains("wrong-public-key", "tampered-manifest", "tampered-receipt");
	}

	@Test
	void recoveryReadinessChecksTheProviderProxyFromTheAgentNetwork() throws IOException {
		String export = script("demo-export-recovery.sh");

		assertThat(export)
				.contains("docker exec open-metadata-sync-public-demo-agent python3 -c")
				.contains("urllib.request.urlopen('http://crossref-proxy:8080/healthz', timeout=2)");
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
				.contains("open-metadata-sync-demo-crossref", "MODE=BACKFILL", "MODE=REPLAY_ERRORS")
				.contains("FULL_STACK_TRANSIENT_WRITE", "COMPLETED_WITH_ERRORS")
				.contains("OPEN_ERRORS_REQUIRE_REPLAY", "Replay did not resolve the injected live error")
				.contains("NO_REPLAY_TARGET", "No-target replay changed the live database")
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
	void recoveryRehearsalReconcilesRestoredLiveAndReplayDataBeforeJenkins() throws IOException {
		String verify = script("demo-verify-recovery.sh");
		assertThat(verify)
				.contains("RECOVERY_KEY_FILE", "openssl enc -d -aes-256-cbc -pbkdf2")
				.contains("${label}_schema_sha256", "${label}_data_sha256", "${label}_table_count")
				.contains("Scratch $label schema mismatch", "Scratch $label data mismatch")
				.contains("verify_schema live open_metadata_live_demo", "verify_schema replay open_metadata")
				.contains("open-metadata-sync-demo-10k", "open-metadata-sync-demo-replay")
				.doesNotContain("open_metadata_benchmark_preflight", "legacy_grant_count");
	}

	@Test
	void alwaysOnReadinessUsesOnlyTheUnifiedPublicJob() throws IOException {
		String up = script("demo-always-on-up.sh");
		assertThat(up)
				.contains("open-metadata-sync-demo-crossref")
				.doesNotContain("/job/open-metadata-sync-demo-10k/api/json",
						"/job/open-metadata-sync-demo-replay/api/json");
	}

	@Test
	void destructiveCleanupRunsOnlyFromTheImmutableCandidateImage() throws IOException {
		String compose = Files.readString(Path.of("compose.always-on-demo.yaml"));
		String cleanup = script("demo-cleanup-legacy.sh");
		String agentDockerfile = Files.readString(Path.of("docker/demo-jenkins/agent/Dockerfile"));
		assertThat(compose.substring(compose.indexOf("  legacy-demo-cleanup:"),
				compose.indexOf("  jenkins-controller:")))
				.contains("open-metadata-sync-demo-agent:${DEMO_IMAGE_TAG", "/opt/open-metadata-sync/scripts/demo-cleanup-legacy.sh")
				.contains("RECOVERY_PUBLIC_KEY_FILE")
				.doesNotContain("RECOVERY_KEY_FILE: /run/secrets/recovery_key")
				.doesNotContain("./scripts:/opt/demo/scripts");
		assertThat(agentDockerfile)
				.contains("scripts/demo-cleanup-legacy.sh", ".demo-infra-revision");
		assertThat(cleanup)
				.contains("/opt/open-metadata-sync/.demo-infra-revision", "CANDIDATE_REVISION", "-pubin");
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
				.contains("CREATE ROLE", "SET DEFAULT ROLE ALL")
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
