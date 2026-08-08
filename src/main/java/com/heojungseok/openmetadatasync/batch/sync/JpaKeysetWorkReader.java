package com.heojungseok.openmetadatasync.batch.sync;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

public final class JpaKeysetWorkReader implements ItemStreamReader<SyncWorkDto> {

	private static final String LAST_COMMITTED_KEY = JpaKeysetWorkReader.class.getName() + ".lastCommittedKey";

	private final EntityManager entityManager;
	private final UUID executionId;
	private final long frozenUpperBound;
	private final int pageSize;
	private Iterator<SyncWorkDto> page = Collections.emptyIterator();
	private long lastReadKey;
	private boolean exhausted;

	public JpaKeysetWorkReader(
			EntityManager entityManager,
			UUID executionId,
			long frozenUpperBound,
			int pageSize
	) {
		this.entityManager = Objects.requireNonNull(entityManager);
		this.executionId = Objects.requireNonNull(executionId);
		if (frozenUpperBound < 0 || pageSize <= 0) {
			throw new IllegalArgumentException("Invalid keyset reader bounds");
		}
		this.frozenUpperBound = frozenUpperBound;
		this.pageSize = pageSize;
	}

	@Override
	public void open(ExecutionContext executionContext) {
		lastReadKey = executionContext.getLong(LAST_COMMITTED_KEY, 0);
		if (lastReadKey < 0 || lastReadKey > frozenUpperBound) {
			throw new ItemStreamException("Checkpoint is outside the frozen staging range");
		}
		page = Collections.emptyIterator();
		exhausted = false;
	}

	@Override
	public SyncWorkDto read() {
		if (!page.hasNext() && !loadPage()) {
			return null;
		}
		SyncWorkDto work = page.next();
		lastReadKey = work.stagingKey();
		return work;
	}

	@Override
	public void update(ExecutionContext executionContext) {
		executionContext.putLong(LAST_COMMITTED_KEY, lastReadKey);
	}

	private boolean loadPage() {
		if (exhausted) {
			return false;
		}
		List<SyncWorkDto> next = entityManager.createQuery("""
				select new com.heojungseok.openmetadatasync.batch.sync.SyncWorkDto(
				  staging.stagingKey, staging.doi, staging.title, staging.publisher, staging.workType,
				  staging.issuedDate, staging.issuedDatePrecision, staging.url, staging.authorsJson,
				  staging.canonicalVersion, staging.contentHash, staging.authorHash, staging.indexedAt
				)
				from CollectStagingWork staging
				where staging.executionId = :executionId
				  and staging.stagingKey > :lastCommittedKey
				  and staging.stagingKey <= :frozenUpperBound
				order by staging.stagingKey asc
				""", SyncWorkDto.class)
				.setParameter("executionId", executionId)
				.setParameter("lastCommittedKey", lastReadKey)
				.setParameter("frozenUpperBound", frozenUpperBound)
				.setMaxResults(pageSize)
				.getResultList();
		page = next.iterator();
		exhausted = next.isEmpty();
		return !exhausted;
	}
}
