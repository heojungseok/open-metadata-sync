package com.heojungseok.openmetadatasync.batch.benchmark;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JpaBenchmarkPreloader {

	private static final int PAGE_SIZE = 1000;

	private final EntityManager entityManager;
	private final TransactionTemplate transaction;

	public JpaBenchmarkPreloader(EntityManager entityManager, PlatformTransactionManager transactionManager) {
		this.entityManager = entityManager;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	public Frozen preload(
			UUID executionId,
			String requestId,
			long batchJobExecutionId,
			long rowCount,
			long seed,
			String generatorVersion
	) {
		if (rowCount <= 0) {
			throw new IllegalArgumentException("rowCount must be positive");
		}
		SyntheticWorkGenerator generator = new SyntheticWorkGenerator(seed, generatorVersion);
		transaction.executeWithoutResult(status -> createExecution(
				executionId, requestId, batchJobExecutionId, rowCount
		));
		long existing = transaction.execute(status -> stagingCount(executionId));
		if (existing > rowCount) {
			throw new IllegalStateException("Benchmark staging exceeds the frozen rowCount");
		}
		for (long first = existing + 1; first <= rowCount; first += PAGE_SIZE) {
			long pageFirst = first;
			long pageLast = Math.min(rowCount, first + PAGE_SIZE - 1);
			transaction.executeWithoutResult(status -> {
				for (long sequence = pageFirst; sequence <= pageLast; sequence++) {
					entityManager.persist(new BenchmarkStagingWork(executionId, generator.row(sequence)));
				}
				entityManager.flush();
				entityManager.clear();
			});
		}
		return transaction.execute(status -> freeze(executionId, rowCount));
	}

	private void createExecution(UUID id, String requestId, long batchJobExecutionId, long rowCount) {
		BenchmarkExecution existing = entityManager.find(BenchmarkExecution.class, id, LockModeType.PESSIMISTIC_WRITE);
		if (existing != null) {
			if (!existing.requestId.equals(requestId) || existing.maxItems != rowCount
					|| !"BENCHMARK".equals(existing.mode)) {
				throw new IllegalStateException("Benchmark restart contract changed");
			}
			return;
		}
		Instant now = Instant.now();
		entityManager.persist(new BenchmarkExecution(id, requestId, batchJobExecutionId, rowCount, now));
	}

	private long stagingCount(UUID executionId) {
		return entityManager.createQuery("""
				select count(staging) from BenchmarkStagingWork staging
				where staging.executionId = :executionId
				""", Long.class).setParameter("executionId", executionId).getSingleResult();
	}

	private Frozen freeze(UUID executionId, long rowCount) {
		BenchmarkExecution execution = entityManager.find(
				BenchmarkExecution.class, executionId, LockModeType.PESSIMISTIC_WRITE
		);
		Object[] aggregate = entityManager.createQuery("""
				select count(staging), coalesce(max(staging.stagingKey), 0)
				from BenchmarkStagingWork staging where staging.executionId = :executionId
				""", Object[].class).setParameter("executionId", executionId).getSingleResult();
		long count = ((Number) aggregate[0]).longValue();
		long upperBound = ((Number) aggregate[1]).longValue();
		if (count != rowCount) {
			throw new IllegalStateException("Benchmark preload count mismatch");
		}
		execution.expectedCount = count;
		execution.stagingUpperBound = upperBound;
		execution.businessStatus = "COLLECTED";
		execution.updatedAt = Instant.now();
		return new Frozen(count, upperBound);
	}

	public record Frozen(long expectedCount, long stagingUpperBound) {
	}
}

@Entity(name = "BenchmarkExecution")
@Table(name = "sync_execution")
class BenchmarkExecution {

	@Id
	@Column(columnDefinition = "BINARY(16)")
	UUID id;

	@Column(name = "request_id", nullable = false)
	String requestId;

	@Column(nullable = false)
	String mode;

	@Column(name = "sync_contract_hash", nullable = false)
	String syncContractHash;

	@Column(name = "canonical_version", nullable = false)
	int canonicalVersion;

	@Column(name = "business_status", nullable = false)
	String businessStatus;

	@Column(name = "batch_job_execution_id")
	Long batchJobExecutionId;

	@Column(name = "max_items")
	Long maxItems;

	@Column(name = "expected_count")
	Long expectedCount;

	@Column(name = "staging_upper_bound")
	Long stagingUpperBound;

	@Column(name = "started_at", nullable = false)
	Instant startedAt;

	@Column(name = "created_at", nullable = false)
	Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;

	protected BenchmarkExecution() {
	}

	BenchmarkExecution(UUID id, String requestId, long batchJobExecutionId, long rowCount, Instant now) {
		this.id = id;
		this.requestId = requestId;
		this.mode = "BENCHMARK";
		this.syncContractHash = com.heojungseok.openmetadatasync.batch.parameter.SyncContract.hash();
		this.canonicalVersion = 1;
		this.businessStatus = "PREPARING";
		this.batchJobExecutionId = batchJobExecutionId;
		this.maxItems = rowCount;
		this.startedAt = now;
		this.createdAt = now;
		this.updatedAt = now;
	}
}

@Entity(name = "BenchmarkStagingWork")
@Table(name = "staging_work")
class BenchmarkStagingWork {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "staging_key")
	Long stagingKey;

	@Column(name = "execution_id", nullable = false, columnDefinition = "BINARY(16)")
	UUID executionId;

	@Column(name = "execution_sequence", nullable = false)
	long executionSequence;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "source_json", nullable = false, columnDefinition = "JSON")
	String sourceJson;

	@Column(nullable = false)
	String doi;

	String title;
	String publisher;

	@Column(name = "work_type")
	String workType;

	@Column(name = "issued_date")
	String issuedDate;

	@Column(name = "issued_date_precision")
	Byte issuedDatePrecision;

	String url;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "authors_json", nullable = false, columnDefinition = "JSON")
	String authorsJson;

	@Column(name = "canonical_version", nullable = false)
	int canonicalVersion;

	@JdbcTypeCode(SqlTypes.BINARY)
	@Column(name = "content_hash", nullable = false, length = 32, columnDefinition = "BINARY(32)")
	byte[] contentHash;

	@JdbcTypeCode(SqlTypes.BINARY)
	@Column(name = "author_hash", nullable = false, length = 32, columnDefinition = "BINARY(32)")
	byte[] authorHash;

	@Column(name = "indexed_at", nullable = false)
	Instant indexedAt;

	@Column(name = "source_created_at")
	Instant sourceCreatedAt;

	@Column(name = "collected_at", nullable = false)
	Instant collectedAt;

	protected BenchmarkStagingWork() {
	}

	BenchmarkStagingWork(UUID executionId, SyntheticWorkGenerator.Row row) {
		this.executionId = executionId;
		this.executionSequence = row.executionSequence();
		this.sourceJson = row.sourceJson();
		this.doi = row.doi();
		this.title = row.title();
		this.publisher = row.publisher();
		this.workType = row.workType();
		this.issuedDate = row.issuedDate();
		this.issuedDatePrecision = row.issuedDatePrecision();
		this.url = row.url();
		this.authorsJson = row.authorsJson();
		this.canonicalVersion = row.canonicalVersion();
		this.contentHash = row.contentHash();
		this.authorHash = row.authorHash();
		this.indexedAt = row.indexedAt();
		this.sourceCreatedAt = row.sourceCreatedAt();
		this.collectedAt = row.collectedAt();
	}
}
