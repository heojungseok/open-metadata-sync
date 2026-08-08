package com.heojungseok.openmetadatasync.batch.replay;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

import com.heojungseok.openmetadatasync.batch.sync.SyncWorkDto;

public final class JpaErrorReplayReader implements ItemStreamReader<ReplayWork> {

	private final EntityManager entityManager;
	private final UUID sourceExecutionId;
	private final int pageSize;
	private final String snapshotKey;
	private final String checkpointKey;
	private Iterator<Object[]> page = Collections.emptyIterator();
	private long snapshotUpperBound;
	private long lastReadErrorKey;
	private boolean exhausted;

	public JpaErrorReplayReader(EntityManager entityManager, UUID sourceExecutionId, int pageSize) {
		this.entityManager = Objects.requireNonNull(entityManager);
		this.sourceExecutionId = Objects.requireNonNull(sourceExecutionId);
		if (pageSize <= 0) {
			throw new IllegalArgumentException("Page size must be positive");
		}
		this.pageSize = pageSize;
		String prefix = JpaErrorReplayReader.class.getName() + "." + sourceExecutionId;
		this.snapshotKey = prefix + ".snapshotUpperBound";
		this.checkpointKey = prefix + ".lastCommittedErrorKey";
	}

	@Override
	public void open(ExecutionContext executionContext) {
		lastReadErrorKey = executionContext.getLong(checkpointKey, 0);
		if (executionContext.containsKey(snapshotKey)) {
			snapshotUpperBound = executionContext.getLong(snapshotKey);
		} else {
			snapshotUpperBound = entityManager.createQuery("""
					select coalesce(max(error.errorKey), 0) from SyncErrorRow error
					where error.executionId = :executionId and error.status = 'OPEN'
					""", Long.class)
					.setParameter("executionId", sourceExecutionId)
					.getSingleResult();
			executionContext.putLong(snapshotKey, snapshotUpperBound);
		}
		if (lastReadErrorKey < 0 || lastReadErrorKey > snapshotUpperBound) {
			throw new ItemStreamException("Replay checkpoint is outside the fixed error snapshot");
		}
		page = Collections.emptyIterator();
		exhausted = false;
	}

	@Override
	public ReplayWork read() {
		if (!page.hasNext() && !loadPage()) {
			return null;
		}
		Object[] row = page.next();
		lastReadErrorKey = ((Number) row[0]).longValue();
		return new ReplayWork(lastReadErrorKey, new SyncWorkDto(
				((Number) row[1]).longValue(),
				(String) row[2],
				(String) row[3],
				(String) row[4],
				(String) row[5],
				(String) row[6],
				(Byte) row[7],
				(String) row[8],
				(String) row[9],
				((Number) row[10]).intValue(),
				(byte[]) row[11],
				(byte[]) row[12],
				(java.time.Instant) row[13]
		));
	}

	@Override
	public void update(ExecutionContext executionContext) {
		executionContext.putLong(snapshotKey, snapshotUpperBound);
		executionContext.putLong(checkpointKey, lastReadErrorKey);
	}

	@Override
	public void close() {
		page = Collections.emptyIterator();
		exhausted = true;
	}

	private boolean loadPage() {
		if (exhausted) {
			return false;
		}
		List<Object[]> rows = entityManager.createQuery("""
				select error.errorKey, staging.stagingKey, staging.doi, staging.title,
				       staging.publisher, staging.workType, staging.issuedDate,
				       staging.issuedDatePrecision, staging.url, staging.authorsJson,
				       staging.canonicalVersion, staging.contentHash, staging.authorHash,
				       staging.indexedAt
				from SyncErrorRow error, CollectStagingWork staging
				where error.executionId = :executionId
				  and error.status = 'OPEN'
				  and error.errorKey > :lastErrorKey
				  and error.errorKey <= :snapshotUpperBound
				  and staging.executionId = error.executionId
				  and staging.stagingKey = error.stagingKey
				order by error.errorKey asc
				""", Object[].class)
				.setParameter("executionId", sourceExecutionId)
				.setParameter("lastErrorKey", lastReadErrorKey)
				.setParameter("snapshotUpperBound", snapshotUpperBound)
				.setMaxResults(pageSize)
				.getResultList();
		page = rows.iterator();
		exhausted = rows.isEmpty();
		return !exhausted;
	}
}
