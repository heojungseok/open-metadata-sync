package com.heojungseok.openmetadatasync.batch.verify;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;

import com.heojungseok.openmetadatasync.batch.execution.ExecutionStatus;

public record VerificationResult(
		BatchStatus batchStatus,
		ExitStatus exitStatus,
		ExecutionStatus businessStatus,
		long eligibleCount,
		long accountedCount,
		long duplicateDoiCount
) {
}
