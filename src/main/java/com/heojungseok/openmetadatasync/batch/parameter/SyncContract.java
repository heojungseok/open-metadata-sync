package com.heojungseok.openmetadatasync.batch.parameter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.heojungseok.openmetadatasync.canonical.Canonicalizer;

public final class SyncContract {

	private static final String DESCRIPTION = "canonicalVersion=" + Canonicalizer.CANONICAL_VERSION
			+ "|unicode=NFC|whitespace=collapse|doi=trim-lower|type=trim-lower"
			+ "|issued=year-month-day-precision|authors=source-order"
			+ "|content=doi,title,publisher,type,issued,url,authors";
	private static final String HASH = sha256(DESCRIPTION);

	private SyncContract() {
	}

	public static String hash() {
		return HASH;
	}

	private static String sha256(String value) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
