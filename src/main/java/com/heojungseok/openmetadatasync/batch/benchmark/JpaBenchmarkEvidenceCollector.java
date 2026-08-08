package com.heojungseok.openmetadatasync.batch.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;

public final class JpaBenchmarkEvidenceCollector {

	private static final int CHECKSUM_PAGE_SIZE = 1000;

	private final EntityManager entityManager;
	private final SessionFactory sessionFactory;

	public JpaBenchmarkEvidenceCollector(EntityManager entityManager, SessionFactory sessionFactory) {
		this.entityManager = entityManager;
		this.sessionFactory = sessionFactory;
	}

	public String targetUpdatedAt(UUID executionId) {
		Instant updated = entityManager.createQuery("""
				select max(target.updatedAt)
				from BenchmarkTargetEvidence target
				where exists (
				  select staging.stagingKey from BenchmarkStagingWork staging
				  where staging.executionId = :executionId and staging.doi = target.doi
				)
				""", Instant.class).setParameter("executionId", executionId).getSingleResult();
		return updated == null ? "" : updated.toString();
	}

	public BenchmarkEvidence collect(
			UUID executionId,
			String scenario,
			long rowCount,
			long seed,
			String generatorVersion,
			String batchStatus,
			String exitStatus,
			String updatedAtBefore,
			boolean restartAttempted,
			boolean restartPassed,
			long preloadMillis,
			int configuredBatchSize,
			BenchmarkMetrics.Snapshot metrics
	) {
		Object[] sums = entityManager.createQuery("""
				select coalesce(sum(result.insertedCount), 0),
				       coalesce(sum(result.supersededCount), 0),
				       coalesce(sum(result.noOpCount), 0),
				       coalesce(sum(result.conflictCount), 0),
				       coalesce(sum(result.indexAdvancedCount), 0),
				       coalesce(sum(result.updatedCount), 0),
				       coalesce(sum(result.validationErrorCount), 0)
				from BenchmarkChunkEvidence result where result.executionId = :executionId
				""", Object[].class).setParameter("executionId", executionId).getSingleResult();
		BenchmarkEvidence.Outcomes outcomes = new BenchmarkEvidence.Outcomes(
				number(sums[0]), number(sums[1]), number(sums[2]), number(sums[3]),
				number(sums[4]), number(sums[5]), number(sums[6])
		);
		BenchmarkEvidence.Checksums checksums = checksums(executionId);
		Statistics statistics = sessionFactory.getStatistics();
		String updatedAtAfter = targetUpdatedAt(executionId);
		SessionFactoryImplementor factory = sessionFactory.unwrap(SessionFactoryImplementor.class);
		org.hibernate.dialect.Dialect dialect = factory.getJdbcServices().getDialect();
		Runtime runtime = Runtime.getRuntime();
		return new BenchmarkEvidence(
				"v1", scenario, rowCount, seed, generatorVersion, batchStatus, exitStatus, outcomes, checksums,
				new BenchmarkEvidence.Dml(
						outcomes.inserted(), outcomes.updated() + outcomes.indexAdvanced(),
						updatedAtBefore, updatedAtAfter
				),
				new BenchmarkEvidence.Persistence(
						statistics.getQueryExecutionCount(), statistics.getPrepareStatementCount(),
						metrics.jdbcBatches(), configuredBatchSize
				),
				new BenchmarkEvidence.Heap(
						metrics.baselineHeap(), metrics.peakHeap(), metrics.heapSamples(), metrics.heapPlateau()
				),
				new BenchmarkEvidence.Restart(restartAttempted, restartPassed),
				new BenchmarkEvidence.Timing(preloadMillis, metrics.syncMillis(), metrics.verifyMillis()),
				new BenchmarkEvidence.Environment(
						System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
						runtime.availableProcessors(), runtime.maxMemory(),
						dialect.getClass().getSimpleName(), dialect.getVersion().toString()
				)
		);
	}

	private BenchmarkEvidence.Checksums checksums(UUID executionId) {
		MessageDigest staging = sha256();
		MessageDigest target = sha256();
		long lastKey = 0;
		while (true) {
			List<Object[]> page = entityManager.createQuery("""
					select source.stagingKey, source.doi, source.contentHash, destination.contentHash
					from BenchmarkStagingWork source, BenchmarkTargetEvidence destination
					where source.executionId = :executionId
					  and source.stagingKey > :lastKey
					  and destination.doi = source.doi
					order by source.stagingKey
					""", Object[].class)
					.setParameter("executionId", executionId)
					.setParameter("lastKey", lastKey)
					.setMaxResults(CHECKSUM_PAGE_SIZE)
					.getResultList();
			if (page.isEmpty()) {
				break;
			}
			for (Object[] row : page) {
				lastKey = number(row[0]);
				byte[] doi = ((String) row[1]).getBytes(StandardCharsets.UTF_8);
				staging.update(doi);
				staging.update((byte[]) row[2]);
				target.update(doi);
				target.update((byte[]) row[3]);
			}
		}
		return new BenchmarkEvidence.Checksums(
				HexFormat.of().formatHex(staging.digest()), HexFormat.of().formatHex(target.digest())
		);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}
}

@Entity(name = "BenchmarkTargetEvidence")
@Table(name = "work")
class BenchmarkTargetEvidence {

	@Id
	@Column(columnDefinition = "BINARY(16)")
	UUID id;

	@Column(nullable = false)
	String doi;

	@Column(name = "content_hash", nullable = false, length = 32, columnDefinition = "BINARY(32)")
	byte[] contentHash;

	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;

	protected BenchmarkTargetEvidence() {
	}
}

@Entity(name = "BenchmarkChunkEvidence")
@Table(name = "sync_chunk_result")
class BenchmarkChunkEvidence {

	@Id
	@Column(columnDefinition = "BINARY(16)")
	UUID id;

	@Column(name = "execution_id", nullable = false, columnDefinition = "BINARY(16)")
	UUID executionId;

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

	protected BenchmarkChunkEvidence() {
	}
}
