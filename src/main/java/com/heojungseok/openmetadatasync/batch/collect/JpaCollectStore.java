package com.heojungseok.openmetadatasync.batch.collect;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.heojungseok.openmetadatasync.canonical.CanonicalWork;
import com.heojungseok.openmetadatasync.canonical.Canonicalizer;
import com.heojungseok.openmetadatasync.crossref.CrossrefPage;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JpaCollectStore implements CrossrefCollector.Store {

	private final EntityManager entityManager;
	private final TransactionTemplate transaction;
	private final Canonicalizer canonicalizer;
	private final ObjectMapper objectMapper;

	public JpaCollectStore(EntityManager entityManager, PlatformTransactionManager transactionManager) {
		this.entityManager = entityManager;
		this.transaction = new TransactionTemplate(transactionManager);
		this.objectMapper = new ObjectMapper();
		this.canonicalizer = new Canonicalizer(objectMapper);
	}

	@Override
	public long sequenceBefore(UUID executionId, UUID windowId) {
		return entityManager.createQuery("""
				select coalesce(sum(previous.collectedCount), 0)
				from CollectWindow previous
				where previous.executionId = :executionId
				  and previous.windowSequence < (
				    select current.windowSequence from CollectWindow current
				    where current.id = :windowId and current.executionId = :executionId
				  )
				""", Long.class)
				.setParameter("executionId", executionId)
				.setParameter("windowId", windowId)
				.getSingleResult();
	}

	@Override
	public void persist(CrossrefCollector.PageWrite page) {
		transaction.executeWithoutResult(status -> {
			for (int index = 0; index < page.items().size(); index++) {
				long sequence = page.startSequence() + index;
				if (!exists(page.executionId(), sequence)) {
					entityManager.persist(toEntity(page, sequence, page.items().get(index)));
				}
			}
			CollectWindow window = entityManager.find(CollectWindow.class, page.windowId());
			if (window == null || !window.executionId.equals(page.executionId())) {
				throw new IllegalArgumentException("Collection window does not belong to execution");
			}
			window.cursorValue = page.cursor();
			window.nextCursorValue = page.nextCursor();
			window.collectedCount = page.startSequence() - 1 + page.items().size();
			window.status = "COLLECTING";
			window.updatedAt = page.collectedAt();
		});
	}

	private boolean exists(UUID executionId, long sequence) {
		return entityManager.createQuery("""
				select count(staging) from CollectStagingWork staging
				where staging.executionId = :executionId and staging.executionSequence = :sequence
				""", Long.class)
				.setParameter("executionId", executionId)
				.setParameter("sequence", sequence)
				.getSingleResult() > 0;
	}

	private CollectStagingWork toEntity(
			CrossrefCollector.PageWrite page,
			long sequence,
			CrossrefPage.Work source
	) {
		CanonicalWork canonical = canonicalizer.canonicalize(source);
		return new CollectStagingWork(
				page.executionId(), sequence, objectMapper.writeValueAsString(source), canonical,
				canonicalizer.contentHash(canonical), canonicalizer.authorHash(canonical),
				timestamp(source.indexed(), "indexed"), nullableTimestamp(source.created()), page.collectedAt(),
				objectMapper.writeValueAsString(canonical.authors())
		);
	}

	@Override
	public void completeWindow(UUID windowId) {
		transaction.executeWithoutResult(status -> {
			CollectWindow window = entityManager.find(CollectWindow.class, windowId);
			if (window == null) {
				throw new IllegalArgumentException("Collection window does not exist");
			}
			window.status = "COLLECTED";
			window.updatedAt = Instant.now();
		});
	}

	@Override
	public CrossrefCollector.Frozen freeze(UUID executionId) {
		return transaction.execute(status -> {
			Object[] aggregate = entityManager.createQuery("""
					select count(staging), coalesce(max(staging.stagingKey), 0)
					from CollectStagingWork staging where staging.executionId = :executionId
					""", Object[].class)
					.setParameter("executionId", executionId)
					.getSingleResult();
			long expectedCount = ((Number) aggregate[0]).longValue();
			long stagingUpperBound = ((Number) aggregate[1]).longValue();
			CollectExecution execution = entityManager.find(CollectExecution.class, executionId);
			if (execution == null) {
				throw new IllegalArgumentException("Collection execution does not exist");
			}
			execution.expectedCount = expectedCount;
			execution.stagingUpperBound = stagingUpperBound;
			execution.businessStatus = "COLLECTED";
			execution.updatedAt = Instant.now();
			return new CrossrefCollector.Frozen(expectedCount, stagingUpperBound);
		});
	}

	private static Instant timestamp(CrossrefPage.Timestamp value, String name) {
		if (value == null || value.dateTime() == null) {
			throw new IllegalArgumentException(name + " timestamp is required");
		}
		return Instant.parse(value.dateTime());
	}

	private static Instant nullableTimestamp(CrossrefPage.Timestamp value) {
		return value == null || value.dateTime() == null ? null : Instant.parse(value.dateTime());
	}
}

@Entity(name = "CollectStagingWork")
@Table(name = "staging_work")
class CollectStagingWork {

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

	@Column(name = "content_hash", nullable = false, length = 32, columnDefinition = "BINARY(32)")
	@JdbcTypeCode(SqlTypes.BINARY)
	byte[] contentHash;

	@Column(name = "author_hash", nullable = false, length = 32, columnDefinition = "BINARY(32)")
	@JdbcTypeCode(SqlTypes.BINARY)
	byte[] authorHash;

	@Column(name = "indexed_at", nullable = false)
	Instant indexedAt;

	@Column(name = "source_created_at")
	Instant sourceCreatedAt;

	@Column(name = "collected_at", nullable = false)
	Instant collectedAt;

	protected CollectStagingWork() {
	}

	CollectStagingWork(
			UUID executionId,
			long executionSequence,
			String sourceJson,
			CanonicalWork canonical,
			byte[] contentHash,
			byte[] authorHash,
			Instant indexedAt,
			Instant sourceCreatedAt,
			Instant collectedAt,
			String authorsJson
	) {
		this.executionId = executionId;
		this.executionSequence = executionSequence;
		this.sourceJson = sourceJson;
		this.doi = canonical.doi();
		this.title = canonical.title();
		this.publisher = canonical.publisher();
		this.workType = canonical.type();
		this.issuedDate = canonical.issuedDate();
		this.issuedDatePrecision = canonical.issuedDatePrecision() == null
				? null
				: canonical.issuedDatePrecision().byteValue();
		this.url = canonical.url();
		this.authorsJson = authorsJson;
		this.canonicalVersion = canonical.canonicalVersion();
		this.contentHash = contentHash;
		this.authorHash = authorHash;
		this.indexedAt = indexedAt;
		this.sourceCreatedAt = sourceCreatedAt;
		this.collectedAt = collectedAt;
	}
}

@Entity(name = "CollectWindow")
@Table(name = "sync_window")
class CollectWindow {

	@Id
	@Column(columnDefinition = "BINARY(16)")
	UUID id;

	@Column(name = "execution_id", nullable = false, columnDefinition = "BINARY(16)")
	UUID executionId;

	@Column(name = "window_sequence", nullable = false)
	int windowSequence;

	@Column(name = "indexed_from_utc")
	Instant indexedFromUtc;

	@Column(name = "indexed_until_utc")
	Instant indexedUntilUtc;

	@Column(name = "cursor_value", columnDefinition = "TEXT")
	String cursorValue;

	@Column(name = "next_cursor_value", columnDefinition = "TEXT")
	String nextCursorValue;

	@Column(name = "collected_count", nullable = false)
	long collectedCount;

	@Column(nullable = false)
	String status;

	@Column(name = "created_at", nullable = false)
	Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;

	protected CollectWindow() {
	}
}

@Entity(name = "CollectExecution")
@Table(name = "sync_execution")
class CollectExecution {

	@Id
	@Column(columnDefinition = "BINARY(16)")
	UUID id;

	@Column(name = "expected_count")
	Long expectedCount;

	@Column(name = "staging_upper_bound")
	Long stagingUpperBound;

	@Column(name = "business_status", nullable = false)
	String businessStatus;

	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;

	protected CollectExecution() {
	}
}
