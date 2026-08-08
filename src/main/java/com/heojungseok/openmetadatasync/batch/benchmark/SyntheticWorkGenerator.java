package com.heojungseok.openmetadatasync.batch.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;

public final class SyntheticWorkGenerator {

	public static final String VERSION = "v1";
	private static final String AUTHORS = "[{\"given\":\"Ada\",\"family\":\"Benchmark\"}]";

	private final long seed;

	public SyntheticWorkGenerator(long seed, String generatorVersion) {
		if (!VERSION.equals(generatorVersion)) {
			throw new IllegalArgumentException("Unsupported generatorVersion: " + generatorVersion);
		}
		this.seed = seed;
	}

	public Row row(long executionSequence) {
		if (executionSequence <= 0) {
			throw new IllegalArgumentException("executionSequence must be positive");
		}
		String doi = "10.5555/benchmark-" + VERSION + "-" + seed + "-" + executionSequence;
		String title = "Synthetic work " + executionSequence;
		Instant indexedAt = Instant.ofEpochSecond(
				1_600_000_000L + Math.floorMod(seed, 31_536_000L) + executionSequence
		);
		String issuedDate = indexedAt.atZone(ZoneOffset.UTC).toLocalDate().toString();
		String content = String.join("|", doi, title, "Open Metadata Benchmark", issuedDate, AUTHORS);
		return new Row(
				executionSequence,
				"{\"DOI\":\"" + doi + "\",\"generatorVersion\":\"" + VERSION + "\"}",
				doi,
				title,
				"Open Metadata Benchmark",
				"journal-article",
				issuedDate,
				(byte) 3,
				"https://doi.org/" + doi,
				AUTHORS,
				1,
				hash(content),
				hash(AUTHORS),
				indexedAt,
				indexedAt.minusSeconds(1),
				indexedAt.plusSeconds(1)
		);
	}

	private static byte[] hash(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	public record Row(
			long executionSequence,
			String sourceJson,
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
			Instant indexedAt,
			Instant sourceCreatedAt,
			Instant collectedAt
	) {
		public Row {
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
}
