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
				.contains("127.0.0.1:9092:8080", "127.0.0.1:3308:3306")
				.contains("edge:", "provider:", "provider-egress:", "internal: true", "pids_limit:", "mem_limit:", "max-size: 10m")
				.contains("/home/jenkins/agent:size=2g,exec,uid=1000,gid=1000,mode=0700")
				.contains("agent_ssh_key", "agent_ssh_pubkey", "demo_mysql_password", "demo_mysql_live_password", "crossref_mailto")
				.contains("name: open-metadata-sync-public-demo-mysql-data")
				.contains("name: open-metadata-sync-public-demo-jenkins-home")
				.doesNotContain("127.0.0.1:9091:8080", "/var/run/docker.sock", "host.docker.internal", "/Users/", "down -v");
	}

	@Test
	void controllerHasNoInteractiveAdminAndOnlyTwoPublicJobs() throws IOException {
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
				.contains("open-metadata-sync-demo-10k", "open-metadata-sync-demo-replay")
				.contains("Files.readString(Path.of('/opt/demo-pipelines/' + scriptName)), true")
				.contains("ParametersDefinitionProperty", "ChoiceParameterDefinition", "StringParameterDefinition")
				.contains("authorization.add(Item.READ, PermissionEntry.group('anonymous'))")
				.contains("authorization.add(Item.BUILD, PermissionEntry.group('anonymous'))")
				.contains("new SSHLauncher(")
				.contains("demoNode.setMode(Node.Mode.NORMAL)")
				.doesNotContain("GlobalMatrixAuthorizationStrategy", "API_TOKEN", "Overall/Administer", "add(Jenkins.ADMINISTER", "Item.READ, 'anonymous'", "scriptName)), false");
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
				.contains("ARG DEMO_REVISION=8e266d82c5305b5d0b870760c7adbd7b8c46498c")
				.contains("./gradlew --no-daemon bootJar")
				.contains("/opt/open-metadata-sync/.demo-revision");
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
	void startupPinsApplicationSourceWithoutRejectingInfrastructureCommits() throws IOException {
		String startup = Files.readString(Path.of("scripts/demo-always-on-up.sh"));

		assertThat(startup)
				.contains("git diff --quiet \"$expected_revision\" -- build.gradle settings.gradle gradlew gradle src/main")
				.doesNotContain("git rev-parse HEAD");
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
		assertThat(controllerBlock).contains("demo_mysql_live_password").doesNotContain("crossref_mailto", "demo_mysql_root_password");
	}

	private static String block(String source, String from, String until) {
		int start = source.indexOf(from);
		int end = source.indexOf(until, start + from.length());
		return source.substring(start, end);
	}
}
