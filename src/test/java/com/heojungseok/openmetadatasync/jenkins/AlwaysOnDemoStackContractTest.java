package com.heojungseok.openmetadatasync.jenkins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlwaysOnDemoStackContractTest {

	@Test
	void composeSeparatesControllerAgentGatewayAndDemoData() throws IOException {
		String compose = Files.readString(Path.of("compose.always-on-demo.yaml"));

		assertThat(compose)
				.contains("name: open-metadata-sync-public-demo")
				.contains("jenkins-controller:", "jenkins-agent:", "gateway:", "mysql:", "crossref-proxy:")
				.contains("127.0.0.1:9092:8080", "127.0.0.1:9093:8081", "127.0.0.1:3308:3306")
				.contains("edge:", "provider:", "provider-egress:", "internal: true", "pids_limit:", "mem_limit:", "max-size: 10m")
				.contains("/home/jenkins/agent:size=2g,exec,uid=1000,gid=1000,mode=0700")
				.contains("agent_ssh_key", "agent_ssh_pubkey", "demo_mysql_password", "demo_mysql_live_password",
						"jenkins_admin_password", "crossref_mailto")
				.contains("name: open-metadata-sync-public-demo-mysql-data")
				.contains("name: open-metadata-sync-public-demo-jenkins-home")
				.doesNotContain("127.0.0.1:9091:8080", "/var/run/docker.sock", "host.docker.internal", "/Users/", "down -v");
	}

	@Test
	void controllerPublishesOneDemoJob() throws IOException {
		String groovy = Files.readString(Path.of(
				"docker/demo-jenkins/controller/init.groovy.d/security-and-jobs.groovy"));

		assertThat(groovy)
				.contains("import hudson.security.AuthorizationMatrixProperty")
				.contains("import hudson.security.ProjectMatrixAuthorizationStrategy")
				.contains("import org.jenkinsci.plugins.matrixauth.PermissionEntry")
				.contains("setNumExecutors(0)")
				.contains("new JDK('jdk21', '/opt/java/openjdk')")
				.contains("new HudsonPrivateSecurityRealm(false, false, null)")
				.contains("createAccount('bootstrap-disabled', UUID.randomUUID().toString())")
				.contains("setInstallState(InstallState.RUNNING)")
				.contains("open-metadata-sync-demo")
				.contains("Files.readString(Path.of('/opt/demo-pipelines/' + scriptName)), true")
				.contains("ParametersDefinitionProperty", "ChoiceParameterDefinition", "StringParameterDefinition")
				.contains("authorization.add(Item.READ, PermissionEntry.group('anonymous'))")
				.contains("authorization.add(Item.BUILD, PermissionEntry.group('anonymous'))")
				.contains("new SSHLauncher(")
				.contains("updateCredentials(Domain.global()")
				.contains("Unexpected credential type or scope")
				.contains("demoNode.setMode(Node.Mode.NORMAL)")
				.doesNotContain("GlobalMatrixAuthorizationStrategy", "API_TOKEN", "Item.READ, 'anonymous'", "scriptName)), false");
	}

	@Test
	void dedicatedControllerHasALoopbackOnlyOwnerAccount() throws IOException {
		String compose = Files.readString(Path.of("compose.always-on-demo.yaml"));
		String startup = Files.readString(Path.of("scripts/demo-always-on-up.sh"));
		String gateway = Files.readString(Path.of("docker/demo-gateway/gateway.py"));
		String ownerVerifier = Files.readString(Path.of("docker/demo-gateway/verify_owner_login.py"));
		String gatewayImage = Files.readString(Path.of("docker/demo-gateway/Dockerfile"));
		String groovy = Files.readString(Path.of(
				"docker/demo-jenkins/controller/init.groovy.d/security-and-jobs.groovy"));
		String gatewayBlock = block(compose, "\n  gateway:\n", "\nnetworks:\n");
		String controllerBlock = block(compose, "\n  jenkins-controller:\n", "\n  jenkins-agent:\n");

		assertThat(gatewayBlock)
				.contains("127.0.0.1:9092:8080", "127.0.0.1:9093:8081")
				.doesNotContain("0.0.0.0:9093:8081");
		assertThat(controllerBlock)
				.contains("jenkins_admin_password")
				.doesNotContain("ports:", "9093");
		assertThat(startup)
				.contains(".demo-secrets/jenkins-admin-password", "openssl rand -hex 32")
				.contains("verify_owner_login.py http://127.0.0.1:9093")
				.contains("chmod 600 .demo-secrets/*");
		assertThat(groovy)
				.contains("/run/secrets/jenkins_admin_password", "'heojungseok'")
				.contains("HudsonPrivateSecurityRealm.Details.fromPlainPassword")
				.contains("globalAuthorization.add(Jenkins.ADMINISTER, PermissionEntry.user('heojungseok'))");
		assertThat(gateway)
				.contains("class AdminHandler", "ADMIN_PORT", "admin_server.serve_forever")
				.contains("forwarded_proto = \"https\"", "forwarded_proto = \"http\"")
				.contains("preserve_content_type = False", "preserve_content_type = True");
		assertThat(ownerVerifier)
				.contains("HTTPCookieProcessor", "LoginCrumbParser", "parser.crumb_values")
				.contains("/j_spring_security_check", "/whoAmI/api/json", "identity.get(\"anonymous\")")
				.contains("/manage", "/crumbIssuer/api/json", "/logout", "cross-origin redirect rejected")
				.contains("login crumb is missing", "duplicate login crumb")
				.contains("logout without crumb was accepted", "session remained authenticated after logout")
				.doesNotContain("Authorization", "Basic ");
		assertThat(gatewayImage).contains("COPY docker/demo-gateway/verify_owner_login.py /app/verify_owner_login.py");
	}

	@Test
	void cutoverDrainsExistingJenkinsAndRejectsResumableRuns() throws IOException {
		String up = Files.readString(Path.of("scripts/demo-always-on-up.sh"));
		Path quiescentPath = Path.of("scripts/demo-assert-jenkins-quiescent.sh");
		String quiescent = Files.readString(quiescentPath);
		assertThat(quiescentPath).isExecutable();

		assertThat(up)
				.contains("restore_gateway_on_failure", "docker stop open-metadata-sync-public-demo-gateway")
				.contains("docker start open-metadata-sync-public-demo-gateway")
				.contains("cutover_started=1", "docker stop open-metadata-sync-public-demo-gateway >/dev/null 2>&1 || true")
				.contains("docker kill open-metadata-sync-public-demo-gateway", "Gateway remained running after fail-closed stop")
				.contains("Initial deployment failed closed; inspect partial resources before retry")
				.contains("CANDIDATE_REVISION=\"$DEMO_INFRA_REVISION\" scripts/demo-assert-jenkins-quiescent.sh")
				.doesNotContain("docker compose -f compose.always-on-demo.yaml down -v");
		assertThat(up.indexOf("docker compose -f compose.always-on-demo.yaml build"))
				.isLessThan(up.indexOf("docker stop open-metadata-sync-public-demo-gateway"));
		assertThat(up.indexOf("scripts/demo-assert-jenkins-quiescent.sh"))
				.isLessThan(up.indexOf("docker compose -f compose.always-on-demo.yaml up -d --no-recreate --wait mysql"));
		assertThat(up.lastIndexOf("trap - EXIT"))
				.isGreaterThan(up.indexOf("for _ in {1..60}"));
		assertThat(quiescent)
				.contains("queue['items'] == []", "currentExecutable", "build.get('building')")
				.contains("build.get('result') is not None", "/var/jenkins_home/jobs/*/builds/*/build.xml")
				.contains("<result>(SUCCESS|UNSTABLE|FAILURE|NOT_BUILT|ABORTED)</result>")
				.contains("Resumable Jenkins run remains");
	}

	@Test
	void imagesPinVersionsAndAgentBakesTheApprovedRevision() throws IOException {
		String controller = Files.readString(Path.of("docker/demo-jenkins/controller/Dockerfile"));
		String plugins = Files.readString(Path.of("docker/demo-jenkins/controller/plugins.txt"));
		String agent = Files.readString(Path.of("docker/demo-jenkins/agent/Dockerfile"));
		String gateway = Files.readString(Path.of("docker/demo-gateway/Dockerfile"));
		String proxy = Files.readString(Path.of("docker/crossref-proxy/Dockerfile"));

		assertThat(controller)
				.contains("jenkins/jenkins:2.576-jdk21@sha256:")
				.contains("jenkins-plugin-cli --plugin-file")
				.contains("security-and-jobs.groovy.override");
		assertThat(plugins)
				.contains("workflow-aggregator:", "git:", "lockable-resources:", "matrix-auth:", "ssh-slaves:")
				.doesNotContain("latest");
		assertThat(agent)
				.contains("jenkins/ssh-agent:8.9.0-jdk21@sha256:")
				.contains("ARG DEMO_REVISION", "grep -Eq '^[0-9a-f]{40}$'")
				.contains("org.opencontainers.image.revision")
				.contains("./gradlew --no-daemon bootJar")
				.contains("/opt/open-metadata-sync/.demo-revision")
				.doesNotContain("ARG DEMO_REVISION=");
		assertThat(gateway)
				.contains("python:3.14.6-alpine3.23@sha256:")
				.contains("USER 65532:65532");
		assertThat(proxy)
				.contains("python:3.14.6-alpine3.23@sha256:")
				.contains("USER 65532:65532");
	}

	@Test
	void namedTunnelUsesAProtectedTokenFileAndNeverTargetsTheExistingJenkins() throws IOException {
		String tunnel = Files.readString(Path.of("scripts/demo-tunnel-run.sh"));

		assertThat(tunnel)
				.contains("cloudflared tunnel run --token-file")
				.contains("127.0.0.1:9092")
				.contains("test \"$(stat -f '%Lp' \"$TOKEN_FILE\")\" = \"600\"")
				.doesNotContain("127.0.0.1:9090", "trycloudflare.com", "--token ");
	}

	@Test
	void demoPipelinesVerifyTheBakedRevisionBeforeRunning() throws IOException {
		for (String pipeline : new String[] {"Jenkinsfile.demo-live-crossref", "Jenkinsfile.crossref"}) {
			assertThat(Files.readString(Path.of(pipeline)))
					.contains("DEMO_SOURCE_DIR", "DEMO_REVISION", ".demo-revision")
					.contains("Prepare approved demo source");
		}
	}

	@Test
	void startupRequiresAnApprovedLiveMainCandidate() throws IOException {
		String startup = Files.readString(Path.of("scripts/demo-always-on-up.sh"));

		assertThat(startup)
				.contains(": \"${CANDIDATE_REVISION:?CANDIDATE_REVISION is required}\"")
				.contains("[[ \"$CANDIDATE_REVISION\" =~ ^[0-9a-f]{40}$ ]]")
				.contains("git status --porcelain --untracked-files=all")
				.contains("canonical_repository=https://github.com/heojungseok/open-metadata-sync.git")
				.contains("git ls-remote \"$canonical_repository\" refs/heads/main")
				.contains("[[ \"$(git rev-parse HEAD)\" == \"$CANDIDATE_REVISION\" ]]")
				.contains("assert_candidate\ndocker compose -f compose.always-on-demo.yaml build")
				.contains("docker compose -f compose.always-on-demo.yaml build jenkins-agent jenkins-controller gateway crossref-proxy\nassert_candidate")
				.contains("DEMO_INFRA_REVISION=\"$CANDIDATE_REVISION\"")
				.contains("DEMO_IMAGE_TAG=\"$DEMO_INFRA_REVISION\"")
				.doesNotContain("expected_revision", "build.gradle settings.gradle gradlew gradle src/main")
				.doesNotContain("RECOVERY_BUNDLE", "recovery_verification=PASS");
	}

	@Test
	void composeUsesOneRevisionAndPreservesTheMysqlContainer() throws IOException {
		String compose = Files.readString(Path.of("compose.always-on-demo.yaml"));
		String startup = Files.readString(Path.of("scripts/demo-always-on-up.sh"));
		String groovy = Files.readString(Path.of(
				"docker/demo-jenkins/controller/init.groovy.d/security-and-jobs.groovy"));

		assertThat(compose)
				.contains("DEMO_REVISION: ${DEMO_INFRA_REVISION:?DEMO_INFRA_REVISION is required}")
				.doesNotContain("DEMO_REVISION: c38fa23ff126267bf97409a29c3f1c9d851b2492");
		assertThat(groovy)
				.contains("System.getenv('DEMO_REVISION')", "/[0-9a-f]{40}/")
				.doesNotContain("'c38fa23ff126267bf97409a29c3f1c9d851b2492'");
		assertThat(startup)
				.contains("existing_components", "existing_components != 0 && existing_components != 7")
				.contains("build/demo/$service-image-id-before.txt", "agent-source-revision-before.txt")
				.contains("src/main/resources/db/migration", "Candidate changes database migrations; separate data plan is required")
				.contains("up -d --no-recreate --wait mysql")
				.contains("up -d --no-deps --force-recreate --wait --wait-timeout 60 crossref-proxy")
				.contains("up -d --no-deps --force-recreate jenkins-agent")
				.contains("up -d --no-deps --force-recreate jenkins-controller")
				.contains("up -d --no-deps --force-recreate gateway")
				.contains(".State.Health.Status", "open-metadata-sync-public-demo-crossref-proxy")
				.doesNotContain("down --remove-orphans", "force-recreate mysql");
	}

	@Test
	void preservedJenkinsHomeInitIsRefreshedBeforeControllerStarts() throws IOException {
		String compose = Files.readString(Path.of("compose.always-on-demo.yaml"));
		String dockerfile = Files.readString(Path.of("docker/demo-jenkins/controller/Dockerfile"));
		String bootstrap = Files.readString(Path.of("docker/demo-jenkins/controller/demo-bootstrap-jenkins-home.sh"));
		String startup = Files.readString(Path.of("scripts/demo-always-on-up.sh"));
		String recovery = Files.readString(Path.of("scripts/demo-verify-recovery.sh"));

		assertThat(compose)
				.contains("jenkins-home-bootstrap:", "profiles: [\"bootstrap\"]", "user: \"0:0\"")
				.contains("/usr/local/bin/demo-bootstrap-jenkins-home")
				.contains("public-demo-jenkins-home:/var/jenkins_home");
		assertThat(dockerfile).contains("demo-bootstrap-jenkins-home");
		assertThat(bootstrap)
				.contains("install -o jenkins -g jenkins -m 0644", "cmp \"$source_file\" \"$target_file\"");
		assertThat(startup.indexOf("run --rm --no-deps jenkins-home-bootstrap"))
				.isLessThan(startup.indexOf("up -d --no-deps --force-recreate jenkins-controller"));
		assertThat(recovery.indexOf("/clean-init.groovy"))
				.isLessThan(recovery.indexOf("--name \"$scratch_controller\""));
		assertThat(recovery).contains("-e DEMO_REVISION=\"$candidate_revision\"");
	}

	@Test
	void flywayRunsBeforeTheEnvironmentSentinelIsInstalled() throws IOException {
		String bootstrap = Files.readString(Path.of("scripts/demo-bootstrap-live-db.sh"));
		String compose = Files.readString(Path.of("compose.always-on-demo.yaml"));

		assertThat(bootstrap).doesNotContain("demo_environment_guard");
		assertThat(compose)
				.contains("scripts/demo-install-live-sentinel.sql")
				.satisfies(value -> assertThat(value.indexOf("--spring.profiles.active=actual"))
						.isLessThan(value.indexOf("scripts/demo-install-live-sentinel.sql")));
	}

	@Test
	void privilegedSecretsAndProviderEgressAreNarrowlyScoped() throws IOException {
		String compose = Files.readString(Path.of("compose.always-on-demo.yaml"));
		String proxyBlock = block(compose, "\n  crossref-proxy:\n", "\n  gateway:\n");
		String agentBlock = block(compose, "\n  jenkins-agent:\n", "\n  crossref-proxy:\n");
		String controllerBlock = block(compose, "\n  jenkins-controller:\n", "\n  jenkins-agent:\n");

		assertThat(proxyBlock).contains("crossref_mailto", "provider-egress").doesNotContain("ports:", "demo_mysql_root_password");
		assertThat(agentBlock).contains("- provider").doesNotContain("provider-egress", "crossref_mailto", "demo_mysql_root_password");
		assertThat(controllerBlock).contains("demo_mysql_live_password")
				.doesNotContain("crossref_mailto", "demo_mysql_root_password", "demo_mysql_password");
	}

	private static String block(String source, String from, String until) {
		int start = source.indexOf(from);
		int end = source.indexOf(until, start + from.length());
		return source.substring(start, end);
	}
}
