package com.heojungseok.openmetadatasync.canonical;

import java.io.IOException;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.heojungseok.openmetadatasync.crossref.CrossrefPage;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalWorkTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final Canonicalizer canonicalizer = new Canonicalizer(objectMapper);

	@Test
	void normalizesTheActualCrossrefProjectionIntoStableCanonicalJson() throws IOException {
		CrossrefPage page = objectMapper.readValue(
				getClass().getResourceAsStream("/crossref/works-page.json"),
				CrossrefPage.class
		);

		CanonicalWork work = canonicalizer.canonicalize(page.message().items().get(1));
		String json = canonicalizer.serialize(work);

		assertThat(json).isEqualTo("""
				{"canonicalVersion":1,"doi":"10.1016/j.lanwpc.2026.101948","title":"Childhood obesity in Pacific Island countries and Territories: from fragmented evidence to coordinated action","publisher":"Elsevier BV","type":"journal-article","issuedDate":"2026-07","issuedDatePrecision":2,"url":"https://doi.org/10.1016/j.lanwpc.2026.101948","authors":[{"given":"Nicola L.","family":"Hawley","orcid":"https://orcid.org/0000-0002-2601-3454"}]}
				""".strip());
		assertThat(HexFormat.of().formatHex(canonicalizer.contentHash(work)))
				.isEqualTo("e8f0fd5a06a602e4500383bec56fc8fe9129ea066123daf8cb36689251d6d330");
		assertThat(HexFormat.of().formatHex(canonicalizer.authorHash(work)))
				.isEqualTo("5027dfdfca59434bf95f9297242c4d147d8f8a732f8147702a6354984563218a");
	}

	@Test
	void normalizesUnicodeWhitespaceDoiAndMissingAuthors() {
		CrossrefPage.Work source = work(
				" 10.1000/ABC ", List.of("  Cafe\u0301\n  Society "), null,
				new CrossrefPage.DateParts(List.of(List.of(2026)))
		);

		CanonicalWork canonical = canonicalizer.canonicalize(source);

		assertThat(canonical.doi()).isEqualTo("10.1000/abc");
		assertThat(canonical.title()).isEqualTo("Café Society");
		assertThat(canonical.publisher()).isNull();
		assertThat(canonical.issuedDate()).isEqualTo("2026");
		assertThat(canonical.issuedDatePrecision()).isEqualTo(1);
		assertThat(canonical.authors()).isEmpty();
	}

	@Test
	void sourceTimestampsDoNotAffectContentHashButAuthorOrderDoes() {
		CrossrefPage.Author first = new CrossrefPage.Author("A", "One", null);
		CrossrefPage.Author second = new CrossrefPage.Author("B", "Two", null);
		CrossrefPage.Work earlier = work("10.1000/test", List.of("Title"), List.of(first, second), null);
		CrossrefPage.Work later = new CrossrefPage.Work(
				earlier.doi(), earlier.titles(), earlier.publisher(), earlier.type(), earlier.issued(), earlier.url(),
				earlier.authors(), new CrossrefPage.Timestamp("2026-08-08T00:00:00Z"),
				new CrossrefPage.Timestamp("2026-08-07T00:00:00Z")
		);
		CrossrefPage.Work reordered = work("10.1000/test", List.of("Title"), List.of(second, first), null);

		byte[] earlierHash = canonicalizer.contentHash(canonicalizer.canonicalize(earlier));

		assertThat(canonicalizer.contentHash(canonicalizer.canonicalize(later))).isEqualTo(earlierHash);
		assertThat(canonicalizer.contentHash(canonicalizer.canonicalize(reordered))).isNotEqualTo(earlierHash);
	}

	private static CrossrefPage.Work work(
			String doi,
			List<String> titles,
			List<CrossrefPage.Author> authors,
			CrossrefPage.DateParts issued
	) {
		return new CrossrefPage.Work(
				doi, titles, null, "journal-article", issued, null, authors,
				new CrossrefPage.Timestamp("2026-08-01T00:00:00Z"),
				new CrossrefPage.Timestamp("2026-07-31T00:00:00Z")
		);
	}
}
