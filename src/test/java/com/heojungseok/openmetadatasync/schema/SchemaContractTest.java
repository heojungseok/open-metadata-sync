package com.heojungseok.openmetadatasync.schema;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.heojungseok.openmetadatasync.OpenMetadataSyncApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SchemaContractTest {

	private static final Map<String, String> PROFILE_SCHEMAS = new LinkedHashMap<>();
	private static final Set<String> REQUIRED_TABLES = Set.of(
			"batch_job_execution", "batch_job_execution_context", "batch_job_execution_params",
			"batch_job_execution_seq", "batch_job_instance", "batch_job_instance_seq",
			"batch_step_execution", "batch_step_execution_context", "batch_step_execution_seq",
			"flyway_schema_history", "staging_work", "sync_chunk_result", "sync_error",
			"sync_execution", "sync_watermark", "sync_window", "work"
	);

	static {
		PROFILE_SCHEMAS.put("actual", "open_metadata");
		PROFILE_SCHEMAS.put("benchmark-preflight", "open_metadata_benchmark_preflight");
		PROFILE_SCHEMAS.put("benchmark", "open_metadata_benchmark");
	}

	@Container
	static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10");

	@BeforeAll
	static void createSchemas() throws SQLException {
		try (Connection connection = DriverManager.getConnection(rootUrl("mysql"), "root", MYSQL.getPassword());
				Statement statement = connection.createStatement()) {
			for (String schema : PROFILE_SCHEMAS.values()) {
				statement.execute("CREATE DATABASE `" + schema
						+ "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
			}
		}
	}

	@Test
	void flywayOwnsTheSameCompleteSchemaInEveryProfile() throws SQLException {
		for (String schema : PROFILE_SCHEMAS.values()) {
			Flyway flyway = migrate(schema);

			assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
			assertThat(flyway.info().applied()).hasSize(4);
			assertThat(rows(schema, """
					SELECT LOWER(table_name)
					FROM information_schema.tables
					WHERE table_schema = ?
					ORDER BY table_name
					""")).containsExactlyInAnyOrderElementsOf(REQUIRED_TABLES);
		}
	}

	@Test
	void schemasHaveMatchingChecksumsStructuresIndexesAndRestrictForeignKeys() throws SQLException {
		Map<String, List<String>> signatures = new LinkedHashMap<>();

		for (String schema : PROFILE_SCHEMAS.values()) {
			migrate(schema);
			List<String> signature = new ArrayList<>();
			signature.addAll(rows(schema, """
					SELECT CONCAT(LOWER(table_name), '|', LOWER(column_name), '|', ordinal_position,
					       '|', column_type, '|', is_nullable, '|', column_key, '|', extra)
					FROM information_schema.columns
					WHERE table_schema = ? AND table_name <> 'flyway_schema_history'
					ORDER BY table_name, ordinal_position
					"""));
			signature.addAll(rows(schema, """
					SELECT CONCAT(LOWER(table_name), '|', LOWER(index_name), '|', non_unique,
					       '|', seq_in_index, '|', LOWER(column_name))
					FROM information_schema.statistics
					WHERE table_schema = ? AND table_name <> 'flyway_schema_history'
					ORDER BY table_name, index_name, seq_in_index
					"""));
			signature.addAll(rows(schema, """
					SELECT CONCAT(LOWER(table_name), '|', LOWER(constraint_name), '|', delete_rule)
					FROM information_schema.referential_constraints
					WHERE constraint_schema = ?
					ORDER BY table_name, constraint_name
					"""));
			signature.addAll(rows(schema, """
					SELECT CONCAT(version, '|', script, '|', checksum)
					FROM flyway_schema_history
					WHERE success = 1 AND ? = DATABASE()
					ORDER BY installed_rank
					"""));
			signatures.put(schema, signature);

			assertThat(rows(schema, """
					SELECT delete_rule
					FROM information_schema.referential_constraints
					WHERE constraint_schema = ? AND LOWER(table_name) NOT LIKE 'batch\\_%'
					""")).containsOnly("RESTRICT");
			assertThat(rows(schema, """
					SELECT LOWER(index_name)
					FROM information_schema.statistics
					WHERE table_schema = ?
					""")).contains("idx_staging_execution_key", "idx_staging_execution_doi_indexed",
					"idx_sync_error_replay", "uk_sync_execution_request", "uk_work_doi");
			assertThat(rows(schema, """
					SELECT LOWER(table_name)
					FROM information_schema.tables
					WHERE table_schema = ?
					""")).noneMatch(name -> name.equals("work_author") || name.contains("cleanup"));
		}

		assertThat(signatures.values()).allMatch(signatures.values().iterator().next()::equals);
	}

	@Test
	void onlyAllowlistedProfilesCanStartTheApplication() throws SQLException {
		for (Map.Entry<String, String> entry : PROFILE_SCHEMAS.entrySet()) {
			migrate(entry.getValue());
			try (ConfigurableApplicationContext context = application(entry.getKey())) {
				assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
						.endsWith("/" + entry.getValue());
			}
		}

		assertThatThrownBy(() -> application("not-allowlisted")).isInstanceOf(RuntimeException.class);
	}

	private static ConfigurableApplicationContext application(String profile) {
		return new SpringApplicationBuilder(OpenMetadataSyncApplication.class)
				.profiles(profile)
				.properties("spring.main.banner-mode=off")
				.run(
						"--DB_HOST=" + MYSQL.getHost(),
						"--DB_PORT=" + MYSQL.getMappedPort(3306),
						"--DB_USERNAME=root",
						"--DB_PASSWORD=" + MYSQL.getPassword()
				);
	}

	private static Flyway migrate(String schema) {
		Flyway flyway = Flyway.configure()
				.dataSource(rootUrl(schema), "root", MYSQL.getPassword())
				.locations("classpath:db/migration")
				.load();
		flyway.migrate();
		return flyway;
	}

	private static List<String> rows(String schema, String sql) throws SQLException {
		List<String> rows = new ArrayList<>();
		try (Connection connection = DriverManager.getConnection(rootUrl(schema), "root", MYSQL.getPassword());
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, schema);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					rows.add(resultSet.getString(1));
				}
			}
		}
		return rows;
	}

	private static String rootUrl(String schema) {
		return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema;
	}
}
