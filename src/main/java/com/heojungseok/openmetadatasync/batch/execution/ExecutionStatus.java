package com.heojungseok.openmetadatasync.batch.execution;

public enum ExecutionStatus {
	PREPARING,
	COLLECTING,
	COLLECTED,
	SYNCING,
	VERIFYING,
	COMPLETED,
	COMPLETED_WITH_ERRORS,
	FAILED;

	public void requireTransitionTo(ExecutionStatus next) {
		boolean allowed = switch (this) {
			case PREPARING -> next == COLLECTING || next == SYNCING || next == FAILED;
			case COLLECTING -> next == COLLECTED || next == FAILED;
			case COLLECTED -> next == SYNCING || next == FAILED;
			case SYNCING -> next == VERIFYING || next == FAILED;
			case VERIFYING -> next == COMPLETED || next == COMPLETED_WITH_ERRORS || next == FAILED;
			case FAILED -> next == PREPARING;
			case COMPLETED, COMPLETED_WITH_ERRORS -> false;
		};
		if (!allowed) {
			throw new IllegalStateException("Invalid execution transition: " + this + " -> " + next);
		}
	}
}
