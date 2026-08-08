package com.heojungseok.openmetadatasync.batch.sync;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

public final class ChunkAwareJpaWorkWriter implements ItemWriter<SyncWorkDto> {

	static final String CONFLICT_CODE = "SAME_TIMESTAMP_DIFFERENT_CONTENT";

	private final EntityManager entityManager;
	private final UUID executionId;
	private final long stepExecutionId;
	private final Runnable beforeFlush;

	public ChunkAwareJpaWorkWriter(EntityManager entityManager, UUID executionId, long stepExecutionId) {
		this(entityManager, executionId, stepExecutionId, () -> { });
	}

	ChunkAwareJpaWorkWriter(
			EntityManager entityManager,
			UUID executionId,
			long stepExecutionId,
			Runnable beforeFlush
	) {
		this.entityManager = Objects.requireNonNull(entityManager);
		this.executionId = Objects.requireNonNull(executionId);
		this.stepExecutionId = stepExecutionId;
		this.beforeFlush = Objects.requireNonNull(beforeFlush);
	}

	@Override
	public void write(Chunk<? extends SyncWorkDto> chunk) {
		if (chunk.isEmpty()) {
			return;
		}
		Map<String, List<SyncWorkDto>> groups = new LinkedHashMap<>();
		for (SyncWorkDto work : chunk) {
			groups.computeIfAbsent(normalizeDoi(work.doi()), ignored -> new ArrayList<>()).add(work);
		}
		Map<String, SyncTargetWork> targets = new LinkedHashMap<>();
		for (SyncTargetWork target : entityManager.createQuery("""
				select target from SyncTargetWork target where target.doi in :dois
				""", SyncTargetWork.class).setParameter("dois", groups.keySet()).getResultList()) {
			targets.put(target.doi, target);
		}
		long chunkSequence = entityManager.createQuery("""
				select coalesce(max(result.chunkSequence), 0)
				from SyncChunkResult result where result.executionId = :executionId
				""", Long.class).setParameter("executionId", executionId).getSingleResult() + 1;
		EnumMap<SyncDecision, Long> counts = new EnumMap<>(SyncDecision.class);
		for (SyncDecision decision : SyncDecision.values()) {
			counts.put(decision, 0L);
		}
		Instant now = Instant.now();
		for (Map.Entry<String, List<SyncWorkDto>> entry : groups.entrySet()) {
			processGroup(entry.getKey(), entry.getValue(), targets.get(entry.getKey()), counts, now);
		}
		long firstKey = chunk.getItems().stream().mapToLong(SyncWorkDto::stagingKey).min().orElseThrow();
		long lastKey = chunk.getItems().stream().mapToLong(SyncWorkDto::stagingKey).max().orElseThrow();
		entityManager.persist(new SyncChunkResult(
				executionId, stepExecutionId, chunkSequence, firstKey, lastKey, counts, now
		));
		beforeFlush.run();
		entityManager.flush();
		entityManager.clear();
	}

	private void processGroup(
			String doi,
			List<SyncWorkDto> group,
			SyncTargetWork target,
			EnumMap<SyncDecision, Long> counts,
			Instant now
	) {
		Instant latest = group.stream().map(SyncWorkDto::indexedAt).max(Instant::compareTo).orElseThrow();
		List<SyncWorkDto> latestItems = group.stream()
				.filter(work -> work.indexedAt().equals(latest))
				.toList();
		byte[] latestHash = latestItems.getFirst().contentHash();
		if (latestItems.stream().anyMatch(work -> !Arrays.equals(latestHash, work.contentHash()))) {
			for (SyncWorkDto work : group) {
				conflict(work, counts, now);
			}
			return;
		}
		SyncWorkDto winner = latestItems.stream()
				.min(java.util.Comparator.comparingLong(SyncWorkDto::stagingKey))
				.orElseThrow();
		for (SyncWorkDto work : group) {
			if (work.stagingKey() != winner.stagingKey()) {
				increment(counts, work.indexedAt().isBefore(latest)
						? SyncDecision.SUPERSEDED
						: SyncDecision.NO_OP);
			}
		}
		if (target == null) {
			entityManager.persist(new SyncTargetWork(doi, winner, now));
			increment(counts, SyncDecision.INSERTED);
			return;
		}
		int timestampOrder = winner.indexedAt().compareTo(target.sourceIndexedAt);
		if (timestampOrder < 0) {
			increment(counts, SyncDecision.SUPERSEDED);
		} else if (timestampOrder == 0 && Arrays.equals(winner.contentHash(), target.contentHash)) {
			increment(counts, SyncDecision.NO_OP);
		} else if (timestampOrder == 0) {
			conflict(winner, counts, now);
		} else if (Arrays.equals(winner.contentHash(), target.contentHash)) {
			target.sourceIndexedAt = winner.indexedAt();
			increment(counts, SyncDecision.INDEX_ADVANCED);
		} else {
			target.update(winner, now);
			increment(counts, SyncDecision.UPDATED);
		}
	}

	private void conflict(SyncWorkDto work, EnumMap<SyncDecision, Long> counts, Instant now) {
		entityManager.persist(new SyncErrorRow(executionId, work.stagingKey(), now));
		increment(counts, SyncDecision.CONFLICT);
	}

	private static void increment(EnumMap<SyncDecision, Long> counts, SyncDecision decision) {
		counts.put(decision, counts.get(decision) + 1);
	}

	private static String normalizeDoi(String doi) {
		String normalized = Normalizer.normalize(Objects.requireNonNull(doi), Normalizer.Form.NFC)
				.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("DOI is required");
		}
		return normalized;
	}

}

@Entity(name = "SyncTargetWork")
@Table(name = "work")
@DynamicUpdate
class SyncTargetWork {

	@Id
	@Column(columnDefinition = "BINARY(16)")
	UUID id;

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

	@Column(name = "source_indexed_at", nullable = false)
	Instant sourceIndexedAt;

	@Column(name = "created_at", nullable = false)
	Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;

	protected SyncTargetWork() {
	}

	SyncTargetWork(String doi, SyncWorkDto work, Instant now) {
		this.id = UUID.randomUUID();
		this.doi = doi;
		copyCanonical(work);
		this.sourceIndexedAt = work.indexedAt();
		this.createdAt = now;
		this.updatedAt = now;
	}

	void update(SyncWorkDto work, Instant now) {
		copyCanonical(work);
		this.sourceIndexedAt = work.indexedAt();
		this.updatedAt = now;
	}

	private void copyCanonical(SyncWorkDto work) {
		this.title = work.title();
		this.publisher = work.publisher();
		this.workType = work.workType();
		this.issuedDate = work.issuedDate();
		this.issuedDatePrecision = work.issuedDatePrecision();
		this.url = work.url();
		this.authorsJson = work.authorsJson();
		this.canonicalVersion = work.canonicalVersion();
		this.contentHash = work.contentHash();
		this.authorHash = work.authorHash();
	}
}

@Entity(name = "SyncErrorRow")
@Table(name = "sync_error")
class SyncErrorRow {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "error_key")
	Long errorKey;

	@Column(name = "execution_id", nullable = false, columnDefinition = "BINARY(16)")
	UUID executionId;

	@Column(name = "staging_key", nullable = false)
	long stagingKey;

	@Column(name = "error_type", nullable = false)
	String errorType;

	@Column(name = "error_code", nullable = false)
	String errorCode;

	@Column(nullable = false)
	String message;

	@Column(nullable = false)
	String status;

	@Column(name = "replay_count", nullable = false)
	int replayCount;

	@Column(name = "created_at", nullable = false)
	Instant createdAt;

	@Column(name = "resolved_at")
	Instant resolvedAt;

	protected SyncErrorRow() {
	}

	SyncErrorRow(UUID executionId, long stagingKey, Instant createdAt) {
		this.executionId = executionId;
		this.stagingKey = stagingKey;
		this.errorType = SyncDecision.CONFLICT.name();
		this.errorCode = ChunkAwareJpaWorkWriter.CONFLICT_CODE;
		this.message = "Same DOI and indexed timestamp have different canonical content";
		this.status = "OPEN";
		this.createdAt = createdAt;
	}
}

@Entity(name = "SyncChunkResult")
@Table(name = "sync_chunk_result")
class SyncChunkResult {

	@Id
	@Column(columnDefinition = "BINARY(16)")
	UUID id;

	@Column(name = "execution_id", nullable = false, columnDefinition = "BINARY(16)")
	UUID executionId;

	@Column(name = "step_execution_id", nullable = false)
	long stepExecutionId;

	@Column(name = "chunk_sequence", nullable = false)
	long chunkSequence;

	@Column(name = "first_staging_key", nullable = false)
	long firstStagingKey;

	@Column(name = "last_staging_key", nullable = false)
	long lastStagingKey;

	@Column(name = "inserted_count", nullable = false)
	long insertedCount;

	@Column(name = "superseded_count", nullable = false)
	long supersededCount;

	@Column(name = "no_op_count", nullable = false)
	long noOpCount;

	@Column(name = "conflict_count", nullable = false)
	long conflictCount;

	@Column(name = "index_advanced_count", nullable = false)
	long indexAdvancedCount;

	@Column(name = "updated_count", nullable = false)
	long updatedCount;

	@Column(name = "validation_error_count", nullable = false)
	long validationErrorCount;

	@Column(name = "created_at", nullable = false)
	Instant createdAt;

	protected SyncChunkResult() {
	}

	SyncChunkResult(
			UUID executionId,
			long stepExecutionId,
			long chunkSequence,
			long firstStagingKey,
			long lastStagingKey,
			EnumMap<SyncDecision, Long> counts,
			Instant createdAt
	) {
		this.id = UUID.randomUUID();
		this.executionId = executionId;
		this.stepExecutionId = stepExecutionId;
		this.chunkSequence = chunkSequence;
		this.firstStagingKey = firstStagingKey;
		this.lastStagingKey = lastStagingKey;
		this.insertedCount = counts.get(SyncDecision.INSERTED);
		this.supersededCount = counts.get(SyncDecision.SUPERSEDED);
		this.noOpCount = counts.get(SyncDecision.NO_OP);
		this.conflictCount = counts.get(SyncDecision.CONFLICT);
		this.indexAdvancedCount = counts.get(SyncDecision.INDEX_ADVANCED);
		this.updatedCount = counts.get(SyncDecision.UPDATED);
		this.createdAt = createdAt;
	}
}
