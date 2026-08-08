package com.heojungseok.openmetadatasync.batch.collect;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.heojungseok.openmetadatasync.batch.execution.ExecutionStatus;
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
	public void validate(UUID executionId, long maxItems, List<UUID> windowIds) {
		CollectExecution execution = entityManager.find(CollectExecution.class, executionId);
		if (execution == null) {
			throw new IllegalArgumentException("Collection execution does not exist");
		}
		ExecutionStatus status;
		try {
			status = ExecutionStatus.valueOf(execution.businessStatus);
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("Unknown execution status: " + execution.businessStatus, exception);
		}
		if (status != ExecutionStatus.COLLECTING) {
			throw new IllegalStateException("Execution must be COLLECTING, but was " + status);
		}
		if (execution.maxItems != null && execution.maxItems != maxItems) {
			throw new IllegalArgumentException("Frozen maxItems does not match collection request");
		}
		List<UUID> pendingWindowIds = entityManager.createQuery("""
				select window.id from CollectWindow window
				where window.executionId = :executionId and window.status <> 'COLLECTED'
				order by window.windowSequence
				""", UUID.class)
				.setParameter("executionId", executionId)
				.getResultList();
		if (!pendingWindowIds.equals(windowIds)) {
			throw new IllegalArgumentException("Request must contain the complete ordered pending windows");
		}
	}

	@Override
	public CrossrefCollector.WindowProgress progress(UUID executionId, UUID windowId) {
		CollectWindow window = entityManager.find(CollectWindow.class, windowId);
		if (window == null || !window.executionId.equals(executionId)) {
			throw new IllegalArgumentException("Collection window does not belong to execution");
		}
		long sequenceBefore = entityManager.createQuery("""
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
		return new CrossrefCollector.WindowProgress(sequenceBefore, window.collectedCount);
	}

	@Override
	public CrossrefCollector.PageCommit persist(
			CrossrefCollector.PageWrite page,
			CrossrefCollector.Completion completion
	) {
		return transaction.execute(status -> {
			CollectExecution execution = requireCollectingExecution(page.executionId());
			CollectWindow window = entityManager.find(
					CollectWindow.class, page.windowId(), LockModeType.PESSIMISTIC_WRITE
			);
			if (window == null || !window.executionId.equals(page.executionId())) {
				throw new IllegalArgumentException("Collection window does not belong to execution");
			}
			if (completion != CrossrefCollector.Completion.PAGE
					&& page.windowCollectedCount() < window.collectedCount) {
				throw new IllegalStateException("Completion is below the durable replay frontier");
			}
			Set<Long> existingSequences = existingSequences(page);
			int insertedCount = 0;
			for (int index = 0; index < page.items().size(); index++) {
				long sequence = page.startSequence() + index;
				if (!existingSequences.contains(sequence)) {
					entityManager.persist(toEntity(page, sequence, page.items().get(index)));
					insertedCount++;
				}
			}
			boolean zeroNewPastFrontier = !page.items().isEmpty() && insertedCount == 0
					&& page.windowCollectedCount() > page.replayFrontier();
			CrossrefCollector.Completion appliedCompletion = zeroNewPastFrontier
					? CrossrefCollector.Completion.PAGE
					: completion;
			window.cursorValue = page.cursor();
			window.nextCursorValue = page.nextCursor();
			if (!zeroNewPastFrontier) {
				window.collectedCount = Math.max(window.collectedCount, page.windowCollectedCount());
			}
			window.status = appliedCompletion == CrossrefCollector.Completion.PAGE ? "COLLECTING" : "COLLECTED";
			window.updatedAt = page.collectedAt();
			CrossrefCollector.Frozen frozen = appliedCompletion == CrossrefCollector.Completion.EXECUTION
					? freeze(execution)
					: null;
			return new CrossrefCollector.PageCommit(insertedCount, frozen);
		});
	}

	private Set<Long> existingSequences(CrossrefCollector.PageWrite page) {
		if (page.items().isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(entityManager.createQuery("""
				select staging.executionSequence from CollectStagingWork staging
				where staging.executionId = :executionId
				  and staging.executionSequence between :first and :last
				""", Long.class)
				.setParameter("executionId", page.executionId())
				.setParameter("first", page.startSequence())
				.setParameter("last", page.startSequence() + page.items().size() - 1)
				.getResultList());
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
	public CrossrefCollector.Frozen complete(UUID executionId) {
		return transaction.execute(status -> freeze(requireCollectingExecution(executionId)));
	}

	private CollectExecution requireCollectingExecution(UUID executionId) {
		CollectExecution execution = entityManager.find(
				CollectExecution.class, executionId, LockModeType.PESSIMISTIC_WRITE
		);
		if (execution == null) {
			throw new IllegalArgumentException("Collection execution does not exist");
		}
		if (!ExecutionStatus.COLLECTING.name().equals(execution.businessStatus)) {
			throw new IllegalStateException("Execution must be COLLECTING, but was " + execution.businessStatus);
		}
		return execution;
	}

	private CrossrefCollector.Frozen freeze(CollectExecution execution) {
		long pendingWindows = entityManager.createQuery("""
				select count(window) from CollectWindow window
				where window.executionId = :executionId and window.status <> 'COLLECTED'
				""", Long.class)
				.setParameter("executionId", execution.id)
				.getSingleResult();
		if (pendingWindows != 0) {
			throw new IllegalStateException("Execution still has pending windows");
		}
		Object[] aggregate = entityManager.createQuery("""
				select count(staging), coalesce(max(staging.stagingKey), 0)
				from CollectStagingWork staging where staging.executionId = :executionId
				""", Object[].class)
				.setParameter("executionId", execution.id)
				.getSingleResult();
		long expectedCount = ((Number) aggregate[0]).longValue();
		long stagingUpperBound = ((Number) aggregate[1]).longValue();
		execution.expectedCount = expectedCount;
		execution.stagingUpperBound = stagingUpperBound;
		execution.businessStatus = "COLLECTED";
		execution.updatedAt = Instant.now();
		return new CrossrefCollector.Frozen(expectedCount, stagingUpperBound);
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

	@Column(name = "max_items")
	Long maxItems;

	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;

	protected CollectExecution() {
	}
}
