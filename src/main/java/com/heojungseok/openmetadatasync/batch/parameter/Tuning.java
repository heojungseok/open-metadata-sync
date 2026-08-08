package com.heojungseok.openmetadatasync.batch.parameter;

public record Tuning(int chunkSize, int hibernateBatchSize) {

	public static final int CROSSREF_ROWS = 1_000;

	public Tuning {
		if (chunkSize <= 0 || hibernateBatchSize <= 0) {
			throw new IllegalArgumentException("Tuning sizes must be positive");
		}
	}
}
