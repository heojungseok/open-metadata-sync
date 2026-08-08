package com.heojungseok.openmetadatasync.batch.sync;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import jakarta.persistence.EntityManager;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.heojungseok.openmetadatasync.OpenMetadataSyncApplication;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JpaKeysetWorkReaderTest {

	@Container
	static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10");

	private static final AtomicInteger JOB_SEQUENCE = new AtomicInteger();
	private static final Instant INDEXED_AT = Instant.parse("2026-08-08T00:00:00.123456Z");
	private static final String AUTHORS_JSON = """
			[{"given":"First","family":"Author"},{"given":"Second","family":"Author"}]
			""".strip();

	private static ConfigurableApplicationContext context;
	private static EntityManager entityManager;
	private static JdbcTemplate jdbc;
	private static JobRepository jobRepository;
	private static TransactionTemplate transaction;
	private static Statistics statistics;

	@BeforeAll
	static void startApplication() throws SQLException {
		try (Connection connection = DriverManager.getConnection(
				MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE open_metadata CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
		}
		context = new SpringApplicationBuilder(OpenMetadataSyncApplication.class)
				.profiles("actual")
				.properties("spring.main.banner-mode=off")
				.properties("spring.jpa.properties.hibernate.generate_statistics=true")
				.run(
						"--DB_HOST=" + MYSQL.getHost(),
						"--DB_PORT=" + MYSQL.getMappedPort(3306),
						"--DB_USERNAME=root",
						"--DB_PASSWORD=" + MYSQL.getPassword()
				);
		entityManager = context.getBean(EntityManager.class);
		jdbc = context.getBean(JdbcTemplate.class);
		jobRepository = context.getBean(JobRepository.class);
		transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
		statistics = context.getBean(jakarta.persistence.EntityManagerFactory.class)
				.unwrap(SessionFactory.class).getStatistics();
	}

	@AfterAll
	static void stopApplication() {
		if (context != null) {
			context.close();
		}
	}

	@Test
	void committedKeysetCheckpointRestartsWithoutOmissionDuplicateOrRowsAboveFrozenBound() {
		Fixture fixture = insertFixture(9);
		StepExecution stepExecution = createStepExecution();
		JpaKeysetWorkReader reader = reader(fixture, 5);
		reader.open(stepExecution.getExecutionContext());

		List<Long> committed = new ArrayList<>();
		committed.addAll(readKeys(reader, 2));
		commitCheckpoint(reader, stepExecution);

		jdbc.update("UPDATE sync_execution SET business_status = 'VERIFYING' WHERE id = ?",
				bytes(fixture.executionId()));
		jdbc.update("UPDATE sync_window SET status = 'FAILED' WHERE execution_id = ?",
				bytes(fixture.executionId()));
		insertTarget("10.1000/work-5");
		long keyAboveFrozenBound = insertStaging(fixture.executionId(), 10, "10.1000/work-10");

		committed.addAll(readKeys(reader, 2));
		commitCheckpoint(reader, stepExecution);
		List<Long> rolledBack = transaction.execute(status -> {
			List<Long> keys = readKeys(reader, 2);
			reader.update(stepExecution.getExecutionContext());
			jobRepository.updateExecutionContext(stepExecution);
			status.setRollbackOnly();
			return keys;
		});

		StepExecution durableStep = jobRepository.getStepExecution(stepExecution.getId());
		reader.close();
		statistics.clear();
		reader.open(durableStep.getExecutionContext());
		List<Long> afterRestart = readToEnd(reader);
		List<Long> outcomes = new ArrayList<>(committed);
		outcomes.addAll(afterRestart);

		assertThat(rolledBack).containsExactly(fixture.keys().get(4), fixture.keys().get(5));
		assertThat(afterRestart).containsExactlyElementsOf(fixture.keys().subList(4, 9));
		assertThat(outcomes).containsExactlyElementsOf(fixture.keys()).doesNotHaveDuplicates();
		assertThat(outcomes).doesNotContain(keyAboveFrozenBound);
		assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);

		reader.close();
		reader.open(durableStep.getExecutionContext());
		assertThat(read(reader).stagingKey()).isEqualTo(fixture.keys().get(4));
	}

	@Test
	void constructorProjectionReadsEveryTypedCanonicalFieldWithoutHydratingTheStagingEntity() {
		Fixture fixture = insertFixture(1);
		statistics.clear();

		SyncWorkDto work = read(reader(fixture, 10));

		assertThat(work.stagingKey()).isEqualTo(fixture.keys().getFirst());
		assertThat(work.doi()).isEqualTo("10.1000/work-1");
		assertThat(work.title()).isEqualTo("Title 1");
		assertThat(work.publisher()).isEqualTo("Publisher 1");
		assertThat(work.workType()).isEqualTo("journal-article");
		assertThat(work.issuedDate()).isEqualTo("2026-08-08");
		assertThat(work.issuedDatePrecision()).isEqualTo((byte) 3);
		assertThat(work.url()).isEqualTo("https://doi.org/10.1000/work-1");
		ObjectMapper objectMapper = new ObjectMapper();
		assertThat(objectMapper.readTree(work.authorsJson())).isEqualTo(objectMapper.readTree(AUTHORS_JSON));
		assertThat(work.authorsJson()).containsSubsequence("First", "Second");
		assertThat(work.canonicalVersion()).isEqualTo(1);
		assertThat(work.contentHash()).containsOnly((byte) 1);
		assertThat(work.authorHash()).containsOnly((byte) 11);
		assertThat(work.indexedAt()).isEqualTo(INDEXED_AT);
		assertThat(statistics.getEntityLoadCount()).isZero();
	}

	@Test
	void hashArraysCannotMutateTheDtoThroughConstructorOrAccessors() {
		byte[] contentHash = {(byte) 1};
		byte[] authorHash = {(byte) 2};
		SyncWorkDto work = new SyncWorkDto(
				1, "10.1000/test", null, null, null, null, null, null, "[]", 1,
				contentHash, authorHash, INDEXED_AT
		);

		contentHash[0] = 11;
		authorHash[0] = 12;
		work.contentHash()[0] = 21;
		work.authorHash()[0] = 22;

		assertThat(work.contentHash()).containsExactly((byte) 1);
		assertThat(work.authorHash()).containsExactly((byte) 2);
	}

	@Test
	void readsOnlyTheRequestedExecutionWhenForeignKeysAreInsideTheFrozenRange() {
		Fixture foreign = insertFixture(3);
		Fixture target = insertFixture(4);
		assertThat(foreign.keys()).allMatch(key -> key <= target.frozenUpperBound());
		JpaKeysetWorkReader reader = reader(target, 2);
		reader.open(new ExecutionContext());

		List<Long> keys = readToEnd(reader);

		assertThat(keys).containsExactlyElementsOf(target.keys()).doesNotHaveDuplicates();
	}

	@Test
	void sharedStepContextDoesNotLeakCheckpointAcrossExecutions() {
		Fixture lowerKeys = insertFixture(2);
		Fixture higherKeys = insertFixture(1);
		ExecutionContext shared = new ExecutionContext();
		JpaKeysetWorkReader first = reader(higherKeys, 2);
		first.open(shared);
		read(first);
		first.update(shared);
		JpaKeysetWorkReader second = reader(lowerKeys, 2);

		second.open(shared);

		assertThat(readToEnd(second)).containsExactlyElementsOf(lowerKeys.keys());
	}

	@Test
	void rejectsCheckpointOutsideTheFrozenStagingRange() {
		Fixture fixture = insertFixture(1);
		String checkpointKey = JpaKeysetWorkReader.class.getName()
				+ "." + fixture.executionId() + ".lastCommittedKey";
		for (long invalid : List.of(-1L, fixture.frozenUpperBound() + 1)) {
			ExecutionContext checkpoint = new ExecutionContext();
			checkpoint.putLong(checkpointKey, invalid);

			assertThatThrownBy(() -> reader(fixture, 10).open(checkpoint))
					.isInstanceOf(ItemStreamException.class)
					.hasMessageContaining("frozen staging range");
		}
		ExecutionContext wrongType = new ExecutionContext();
		wrongType.putString(checkpointKey, "not-a-long");
		assertThatThrownBy(() -> reader(fixture, 10).open(wrongType))
				.isInstanceOf(ClassCastException.class);
	}

	private static JpaKeysetWorkReader reader(Fixture fixture, int pageSize) {
		return new JpaKeysetWorkReader(
				entityManager, fixture.executionId(), fixture.frozenUpperBound(), pageSize
		);
	}

	private static void commitCheckpoint(JpaKeysetWorkReader reader, StepExecution stepExecution) {
		reader.update(stepExecution.getExecutionContext());
		jobRepository.updateExecutionContext(stepExecution);
	}

	private static List<Long> readKeys(JpaKeysetWorkReader reader, int count) {
		return IntStream.range(0, count).mapToObj(ignored -> read(reader).stagingKey()).toList();
	}

	private static List<Long> readToEnd(JpaKeysetWorkReader reader) {
		List<Long> keys = new ArrayList<>();
		SyncWorkDto work;
		while ((work = read(reader)) != null) {
			keys.add(work.stagingKey());
		}
		return keys;
	}

	private static SyncWorkDto read(JpaKeysetWorkReader reader) {
		try {
			return reader.read();
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static StepExecution createStepExecution() {
		String name = "keyset-reader-" + JOB_SEQUENCE.incrementAndGet();
		JobParameters parameters = new JobParameters();
		JobInstance instance = jobRepository.createJobInstance(name, parameters);
		JobExecution execution = jobRepository.createJobExecution(instance, parameters, new ExecutionContext());
		return jobRepository.createStepExecution("sync", execution);
	}

	private static Fixture insertFixture(int count) {
		UUID executionId = UUID.randomUUID();
		UUID windowId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO sync_execution (
				  id, request_id, mode, sync_contract_hash, canonical_version, business_status,
				  indexed_from_utc, indexed_until_utc, started_at, created_at, updated_at
				) VALUES (?, ?, 'INCREMENTAL', ?, 1, 'COLLECTED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
				          UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", bytes(executionId), executionId.toString(), "a".repeat(64));
		jdbc.update("""
				INSERT INTO sync_window (
				  id, execution_id, window_sequence, cursor_value, next_cursor_value,
				  collected_count, status, created_at, updated_at
				) VALUES (?, ?, 0, '*', NULL, ?, 'COLLECTED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", bytes(windowId), bytes(executionId), count);
		List<Long> keys = IntStream.rangeClosed(1, count)
				.mapToObj(sequence -> insertStaging(executionId, sequence, "10.1000/work-" + sequence))
				.toList();
		long upperBound = keys.getLast();
		jdbc.update("""
				UPDATE sync_execution SET expected_count = ?, staging_upper_bound = ? WHERE id = ?
				""", count, upperBound, bytes(executionId));
		return new Fixture(executionId, upperBound, keys);
	}

	private static long insertStaging(UUID executionId, int sequence, String doi) {
		byte[] contentHash = new byte[32];
		byte[] authorHash = new byte[32];
		Arrays.fill(contentHash, (byte) sequence);
		Arrays.fill(authorHash, (byte) (sequence + 10));
		jdbc.update("""
				INSERT INTO staging_work (
				  execution_id, execution_sequence, source_json, doi, title, publisher, work_type,
				  issued_date, issued_date_precision, url, authors_json, canonical_version,
				  content_hash, author_hash, indexed_at, source_created_at, collected_at
				) VALUES (?, ?, JSON_OBJECT('ignored', ?), ?, ?, ?, 'journal-article',
				          '2026-08-08', 3, ?, CAST(? AS JSON), 1, ?, ?, ?, ?, ?)
				""",
				bytes(executionId), sequence, "source-" + sequence, doi, "Title " + sequence,
				"Publisher " + sequence, "https://doi.org/" + doi, AUTHORS_JSON,
				contentHash, authorHash, utc(INDEXED_AT), utc(INDEXED_AT.minusSeconds(1)),
				utc(INDEXED_AT.plusSeconds(1))
		);
		return jdbc.queryForObject(
				"SELECT staging_key FROM staging_work WHERE execution_id = ? AND execution_sequence = ?",
				Long.class, bytes(executionId), sequence
		);
	}

	private static void insertTarget(String doi) {
		byte[] hash = new byte[32];
		jdbc.update("""
				INSERT INTO work (
				  id, doi, title, publisher, work_type, issued_date, issued_date_precision, url,
				  authors_json, canonical_version, content_hash, author_hash, source_indexed_at,
				  created_at, updated_at
				) VALUES (?, ?, 'Existing', NULL, NULL, NULL, NULL, NULL,
				          JSON_ARRAY(), 1, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", bytes(UUID.randomUUID()), doi, hash, hash);
	}

	private static byte[] bytes(UUID id) {
		return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
	}

	private static LocalDateTime utc(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private record Fixture(UUID executionId, long frozenUpperBound, List<Long> keys) {
	}
}
