package com.heojungseok.openmetadatasync.jenkins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

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
		Path summaryPath = Path.of("scripts/demo-replay-summary.sh");
		assertThat(upPath).exists();
		assertThat(downPath).exists();
		assertThat(fixturePath).exists();
		assertThat(summaryPath).exists();

		String up = Files.readString(upPath);
		String down = Files.readString(downPath);
		String fixture = Files.readString(fixturePath);
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
				.contains("'OPEN', 0")
				.contains("ON DUPLICATE KEY UPDATE");
		assertThat(summary)
				.contains("RESOLVED", "replay_count", "no_op_count", "target_count")
				.contains("replay-${REQUEST_ID}.json", "replay-${REQUEST_ID}.md");
	}
}
