package com.heojungseok.openmetadatasync.batch.sync;

public enum SyncDecision {
	INSERTED,
	SUPERSEDED,
	NO_OP,
	CONFLICT,
	INDEX_ADVANCED,
	UPDATED
}
