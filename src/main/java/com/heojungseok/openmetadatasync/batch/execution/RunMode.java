package com.heojungseok.openmetadatasync.batch.execution;

public enum RunMode {
	BACKFILL,
	INCREMENTAL,
	REPLAY_ERRORS,
	BENCHMARK
}
