package com.heojungseok.openmetadatasync.canonical;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import com.heojungseok.openmetadatasync.crossref.CrossrefPage;
import tools.jackson.databind.ObjectMapper;

public final class Canonicalizer {

	public static final int CANONICAL_VERSION = 1;

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private final ObjectMapper objectMapper;

	public Canonicalizer(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public CanonicalWork canonicalize(CrossrefPage.Work source) {
		String doi = text(source.doi());
		if (doi == null) {
			throw new IllegalArgumentException("DOI is required");
		}

		List<Integer> issuedParts = firstDateParts(source.issued());
		List<CanonicalWork.Author> authors = source.authors() == null
				? List.of()
				: source.authors().stream()
						.filter(Objects::nonNull)
						.map(author -> new CanonicalWork.Author(
								text(author.given()),
								text(author.family()),
								text(author.orcid())
						))
						.toList();

		return new CanonicalWork(
				CANONICAL_VERSION,
				doi.toLowerCase(Locale.ROOT),
				firstTitle(source.titles()),
				text(source.publisher()),
				lowerText(source.type()),
				formatDate(issuedParts),
				issuedParts == null ? null : issuedParts.size(),
				text(source.url()),
				authors
		);
	}

	public String serialize(CanonicalWork work) {
		return objectMapper.writeValueAsString(work);
	}

	public byte[] contentHash(CanonicalWork work) {
		return sha256(objectMapper.writeValueAsBytes(work));
	}

	public byte[] authorHash(CanonicalWork work) {
		return sha256(objectMapper.writeValueAsBytes(work.authors()));
	}

	private static String firstTitle(List<String> titles) {
		if (titles == null) {
			return null;
		}
		return titles.stream().map(Canonicalizer::text).filter(Objects::nonNull).findFirst().orElse(null);
	}

	private static List<Integer> firstDateParts(CrossrefPage.DateParts issued) {
		if (issued == null || issued.dateParts() == null || issued.dateParts().isEmpty()) {
			return null;
		}
		List<Integer> parts = issued.dateParts().getFirst();
		if (parts == null || parts.isEmpty() || parts.size() > 3) {
			return null;
		}
		return parts;
	}

	private static String formatDate(List<Integer> parts) {
		if (parts == null) {
			return null;
		}
		return switch (parts.size()) {
			case 1 -> "%04d".formatted(parts.get(0));
			case 2 -> "%04d-%02d".formatted(parts.get(0), parts.get(1));
			case 3 -> "%04d-%02d-%02d".formatted(parts.get(0), parts.get(1), parts.get(2));
			default -> throw new IllegalStateException("Unexpected date precision");
		};
	}

	private static String lowerText(String value) {
		String normalized = text(value);
		return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
	}

	private static String text(String value) {
		if (value == null) {
			return null;
		}
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
		normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
		return normalized.isEmpty() ? null : normalized;
	}

	private static byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
