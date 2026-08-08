package com.heojungseok.openmetadatasync.batch.replay;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
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

public final class JpaErrorReplayPreparer {

	private static final int COPY_PAGE_SIZE = 1000;

	private final EntityManager entityManager;
	private final Runnable afterSnapshot;

	public JpaErrorReplayPreparer(EntityManager entityManager) {
		this(entityManager, () -> { });
	}

	JpaErrorReplayPreparer(EntityManager entityManager, Runnable afterSnapshot) {
		this.entityManager = Objects.requireNonNull(entityManager);
		this.afterSnapshot = Objects.requireNonNull(afterSnapshot);
	}

	public Prepared prepare(UUID replayExecutionId, UUID sourceExecutionId) {
		ReplayExecution execution = entityManager.find(
				ReplayExecution.class, Objects.requireNonNull(replayExecutionId), LockModeType.PESSIMISTIC_WRITE
		);
		if (execution == null
				|| !"REPLAY_ERRORS".equals(execution.mode)
				|| !"PREPARING".equals(execution.businessStatus)
				|| !Objects.equals(execution.sourceExecutionId, sourceExecutionId)) {
			throw new IllegalStateException("Replay execution contract does not match the source");
		}
		if (execution.expectedCount != null || execution.stagingUpperBound != null
				|| stagingCount(replayExecutionId) != 0) {
			throw new IllegalStateException("Replay execution is already prepared");
		}
		long errorUpperBound = entityManager.createQuery("""
				select coalesce(max(error.errorKey), 0) from SyncErrorRow error
				where error.executionId = :sourceExecutionId and error.status = 'OPEN'
				""", Long.class)
				.setParameter("sourceExecutionId", sourceExecutionId)
				.getSingleResult();
		afterSnapshot.run();

		long lastErrorKey = 0;
		long executionSequence = 0;
		while (true) {
			List<Object[]> page = entityManager.createQuery("""
					select error.errorKey, staging.sourceJson, staging.doi, staging.title,
					       staging.publisher, staging.workType, staging.issuedDate,
					       staging.issuedDatePrecision, staging.url, staging.authorsJson,
					       staging.canonicalVersion, staging.contentHash, staging.authorHash,
					       staging.indexedAt, staging.sourceCreatedAt, staging.collectedAt
					from SyncErrorRow error, CollectStagingWork staging
					where error.executionId = :sourceExecutionId
					  and error.status = 'OPEN'
					  and error.errorKey > :lastErrorKey
					  and error.errorKey <= :errorUpperBound
					  and staging.executionId = error.executionId
					  and staging.stagingKey = error.stagingKey
					order by error.errorKey asc
					""", Object[].class)
					.setParameter("sourceExecutionId", sourceExecutionId)
					.setParameter("lastErrorKey", lastErrorKey)
					.setParameter("errorUpperBound", errorUpperBound)
					.setMaxResults(COPY_PAGE_SIZE)
					.getResultList();
			if (page.isEmpty()) {
				break;
			}
			for (Object[] row : page) {
				lastErrorKey = ((Number) row[0]).longValue();
				entityManager.persist(new ReplayStagingWork(
						replayExecutionId, ++executionSequence, row
				));
			}
			entityManager.flush();
			entityManager.clear();
		}
		long stagingUpperBound = entityManager.createQuery("""
				select coalesce(max(staging.stagingKey), 0) from ReplayStagingWork staging
				where staging.executionId = :executionId
				""", Long.class)
				.setParameter("executionId", replayExecutionId)
				.getSingleResult();
		execution = entityManager.find(ReplayExecution.class, replayExecutionId, LockModeType.PESSIMISTIC_WRITE);
		execution.expectedCount = executionSequence;
		execution.stagingUpperBound = stagingUpperBound;
		execution.updatedAt = Instant.now();
		return new Prepared(executionSequence, stagingUpperBound, errorUpperBound);
	}

	private long stagingCount(UUID executionId) {
		return entityManager.createQuery("""
				select count(staging) from ReplayStagingWork staging
				where staging.executionId = :executionId
				""", Long.class).setParameter("executionId", executionId).getSingleResult();
	}

	public record Prepared(long expectedCount, long stagingUpperBound, long errorUpperBound) {
	}
}

@Entity(name = "ReplayExecution")
@Table(name = "sync_execution")
class ReplayExecution {

	@Id
	@Column(columnDefinition = "BINARY(16)")
	UUID id;

	@Column(nullable = false)
	String mode;

	@Column(name = "business_status", nullable = false)
	String businessStatus;

	@Column(name = "source_execution_id", columnDefinition = "BINARY(16)")
	UUID sourceExecutionId;

	@Column(name = "expected_count")
	Long expectedCount;

	@Column(name = "staging_upper_bound")
	Long stagingUpperBound;

	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;

	protected ReplayExecution() {
	}
}

@Entity(name = "ReplayStagingWork")
@Table(name = "staging_work")
class ReplayStagingWork {

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

	@Column(name = "issued_date_precision", columnDefinition = "TINYINT UNSIGNED")
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

	protected ReplayStagingWork() {
	}

	ReplayStagingWork(UUID executionId, long executionSequence, Object[] source) {
		this.executionId = executionId;
		this.executionSequence = executionSequence;
		this.sourceJson = (String) source[1];
		this.doi = (String) source[2];
		this.title = (String) source[3];
		this.publisher = (String) source[4];
		this.workType = (String) source[5];
		this.issuedDate = (String) source[6];
		this.issuedDatePrecision = (Byte) source[7];
		this.url = (String) source[8];
		this.authorsJson = (String) source[9];
		this.canonicalVersion = ((Number) source[10]).intValue();
		this.contentHash = (byte[]) source[11];
		this.authorHash = (byte[]) source[12];
		this.indexedAt = (Instant) source[13];
		this.sourceCreatedAt = (Instant) source[14];
		this.collectedAt = (Instant) source[15];
	}
}
