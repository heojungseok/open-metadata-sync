package com.heojungseok.openmetadatasync.batch.verify;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;

import com.heojungseok.openmetadatasync.batch.execution.ExecutionStatus;
import com.heojungseok.openmetadatasync.batch.window.WatermarkPolicy;

public final class JpaExecutionVerifier {

	private final EntityManager entityManager;

	public JpaExecutionVerifier(EntityManager entityManager) {
		this.entityManager = Objects.requireNonNull(entityManager);
	}

	public VerificationResult verify(UUID executionId, String sourceName) {
		VerifyExecution execution = entityManager.find(
				VerifyExecution.class, Objects.requireNonNull(executionId), LockModeType.PESSIMISTIC_WRITE
		);
		if (execution == null) {
			throw new IllegalArgumentException("Execution does not exist");
		}
		if (!ExecutionStatus.VERIFYING.name().equals(execution.businessStatus)) {
			throw new IllegalStateException("Execution must be VERIFYING");
		}
		if (execution.expectedCount == null || execution.stagingUpperBound == null) {
			throw new IllegalStateException("Execution range is not frozen");
		}

		long eligible = entityManager.createQuery("""
				select count(staging) from CollectStagingWork staging
				where staging.executionId = :executionId
				  and staging.stagingKey <= :upperBound
				""", Long.class)
				.setParameter("executionId", executionId)
				.setParameter("upperBound", execution.stagingUpperBound)
				.getSingleResult();
		long distinctDoi = entityManager.createQuery("""
				select count(distinct staging.doi) from CollectStagingWork staging
				where staging.executionId = :executionId
				  and staging.stagingKey <= :upperBound
				""", Long.class)
				.setParameter("executionId", executionId)
				.setParameter("upperBound", execution.stagingUpperBound)
				.getSingleResult();
		Object[] sums = entityManager.createQuery("""
				select coalesce(sum(result.insertedCount), 0),
				       coalesce(sum(result.supersededCount), 0),
				       coalesce(sum(result.noOpCount), 0),
				       coalesce(sum(result.conflictCount), 0),
				       coalesce(sum(result.indexAdvancedCount), 0),
				       coalesce(sum(result.updatedCount), 0),
				       coalesce(sum(result.validationErrorCount), 0)
				from SyncChunkResult result where result.executionId = :executionId
				""", Object[].class)
				.setParameter("executionId", executionId)
				.getSingleResult();
		long accounted = 0;
		for (Object sum : sums) {
			accounted += ((Number) sum).longValue();
		}
		long conflicts = ((Number) sums[3]).longValue();
		long validations = ((Number) sums[6]).longValue();
		List<Object[]> openErrors = entityManager.createQuery("""
				select error.errorType, count(error) from SyncErrorRow error
				where error.executionId = :executionId and error.status = 'OPEN'
				group by error.errorType
				""", Object[].class).setParameter("executionId", executionId).getResultList();
		long openConflicts = errorCount(openErrors, "CONFLICT");
		long openValidations = errorCount(openErrors, "VALIDATION");

		String failure = null;
		if (execution.expectedCount != eligible || eligible != accounted) {
			failure = "Frozen eligible and durable outcome counts differ";
		} else if (conflicts != openConflicts || validations != openValidations
				|| openConflicts + openValidations != openErrors.stream()
						.mapToLong(row -> ((Number) row[1]).longValue()).sum()) {
			failure = "Durable outcome and OPEN error counts differ";
		} else if (missingTargetCount(executionId, execution.stagingUpperBound) != 0) {
			failure = "Eligible DOI is missing from the target";
		} else if (checksumMismatchCount(executionId, execution.stagingUpperBound) != 0) {
			failure = "Target checksum differs from the eligible winner";
		}
		if (failure != null) {
			return fail(execution, eligible, accounted, eligible - distinctDoi, failure);
		}
		if (conflicts > 0) {
			return fail(execution, eligible, accounted, eligible - distinctDoi, "Conflict remains OPEN");
		}

		ExecutionStatus business = validations > 0
				? ExecutionStatus.COMPLETED_WITH_ERRORS
				: ExecutionStatus.COMPLETED;
		complete(execution, business, Objects.requireNonNull(sourceName));
		ExitStatus exit = validations > 0
				? new ExitStatus("COMPLETED_WITH_ERRORS")
				: ExitStatus.COMPLETED;
		return new VerificationResult(
				BatchStatus.COMPLETED, exit, business, eligible, accounted, eligible - distinctDoi
		);
	}

	private long missingTargetCount(UUID executionId, long upperBound) {
		return entityManager.createQuery("""
				select count(distinct staging.doi) from CollectStagingWork staging
				where staging.executionId = :executionId
				  and staging.stagingKey <= :upperBound
				  and not exists (
				    select error.errorKey from SyncErrorRow error
				    where error.executionId = staging.executionId
				      and error.stagingKey = staging.stagingKey
				      and error.status = 'OPEN'
				  )
				  and not exists (
				    select target.id from SyncTargetWork target where target.doi = staging.doi
				  )
				""", Long.class)
				.setParameter("executionId", executionId)
				.setParameter("upperBound", upperBound)
				.getSingleResult();
	}

	private long checksumMismatchCount(UUID executionId, long upperBound) {
		return entityManager.createQuery("""
				select count(staging) from CollectStagingWork staging, SyncTargetWork target
				where staging.executionId = :executionId
				  and staging.stagingKey <= :upperBound
				  and target.doi = staging.doi
				  and not exists (
				    select error.errorKey from SyncErrorRow error
				    where error.executionId = staging.executionId
				      and error.stagingKey = staging.stagingKey
				      and error.status = 'OPEN'
				  )
				  and staging.indexedAt = (
				    select max(latest.indexedAt) from CollectStagingWork latest
				    where latest.executionId = staging.executionId
				      and latest.doi = staging.doi
				      and latest.stagingKey <= :upperBound
				  )
				  and staging.stagingKey = (
				    select min(winner.stagingKey) from CollectStagingWork winner
				    where winner.executionId = staging.executionId
				      and winner.doi = staging.doi
				      and winner.indexedAt = staging.indexedAt
				      and winner.stagingKey <= :upperBound
				  )
				  and (target.sourceIndexedAt < staging.indexedAt
				       or (target.sourceIndexedAt = staging.indexedAt
				           and target.contentHash <> staging.contentHash))
				""", Long.class)
				.setParameter("executionId", executionId)
				.setParameter("upperBound", upperBound)
				.getSingleResult();
	}

	private VerificationResult fail(
			VerifyExecution execution,
			long eligible,
			long accounted,
			long duplicateDoiCount,
			String reason
	) {
		execution.businessStatus = ExecutionStatus.FAILED.name();
		execution.finishedAt = Instant.now();
		execution.updatedAt = execution.finishedAt;
		return new VerificationResult(
				BatchStatus.FAILED, ExitStatus.FAILED.addExitDescription(reason), ExecutionStatus.FAILED,
				eligible, accounted, duplicateDoiCount
		);
	}

	private void complete(VerifyExecution execution, ExecutionStatus status, String sourceName) {
		Instant now = Instant.now();
		VerifyWatermark watermark = entityManager.find(
				VerifyWatermark.class, sourceName, LockModeType.PESSIMISTIC_WRITE
		);
		Instant advanced = WatermarkPolicy.advance(
				watermark == null ? null : watermark.indexedUntilUtc,
				Objects.requireNonNull(execution.indexedUntilUtc), true, false
		);
		if (watermark == null) {
			watermark = new VerifyWatermark(sourceName);
			entityManager.persist(watermark);
		}
		watermark.indexedUntilUtc = advanced;
		watermark.executionId = execution.id;
		watermark.updatedAt = now;
		execution.businessStatus = status.name();
		execution.finishedAt = now;
		execution.updatedAt = now;
	}

	private static long errorCount(List<Object[]> counts, String type) {
		return counts.stream()
				.filter(row -> type.equals(row[0]))
				.mapToLong(row -> ((Number) row[1]).longValue())
				.sum();
	}
}

@Entity(name = "VerifyExecution")
@Table(name = "sync_execution")
class VerifyExecution {

	@Id
	@Column(columnDefinition = "BINARY(16)")
	UUID id;

	@Column(name = "business_status", nullable = false)
	String businessStatus;

	@Column(name = "expected_count")
	Long expectedCount;

	@Column(name = "staging_upper_bound")
	Long stagingUpperBound;

	@Column(name = "indexed_until_utc")
	Instant indexedUntilUtc;

	@Column(name = "finished_at")
	Instant finishedAt;

	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;

	protected VerifyExecution() {
	}
}

@Entity(name = "VerifyWatermark")
@Table(name = "sync_watermark")
class VerifyWatermark {

	@Id
	@Column(name = "source_name")
	String sourceName;

	@Column(name = "indexed_until_utc", nullable = false)
	Instant indexedUntilUtc;

	@Column(name = "execution_id", nullable = false, columnDefinition = "BINARY(16)")
	UUID executionId;

	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;

	protected VerifyWatermark() {
	}

	VerifyWatermark(String sourceName) {
		this.sourceName = sourceName;
	}
}
