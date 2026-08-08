package com.heojungseok.openmetadatasync.batch.sync;

import java.time.Instant;

public record SyncWorkDto(
		long stagingKey,
		String doi,
		String title,
		String publisher,
		String workType,
		String issuedDate,
		Byte issuedDatePrecision,
		String url,
		String authorsJson,
		int canonicalVersion,
		byte[] contentHash,
		byte[] authorHash,
		Instant indexedAt
) {
	public SyncWorkDto {
		contentHash = contentHash.clone();
		authorHash = authorHash.clone();
	}

	@Override
	public byte[] contentHash() {
		return contentHash.clone();
	}

	@Override
	public byte[] authorHash() {
		return authorHash.clone();
	}
}
