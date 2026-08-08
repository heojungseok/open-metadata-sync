package com.heojungseok.openmetadatasync.crossref;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CrossrefPageTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void parsesTheCapturedCrossrefEnvelopeAndOptionalShapes() throws IOException {
		CrossrefPage page = objectMapper.readValue(
				getClass().getResourceAsStream("/crossref/works-page.json"),
				CrossrefPage.class
		);

		assertThat(page.status()).isEqualTo("ok");
		assertThat(page.messageType()).isEqualTo("work-list");
		assertThat(page.messageVersion()).isEqualTo("1.0.0");
		assertThat(page.message().totalResults()).isEqualTo(15_933);
		assertThat(page.message().nextCursor()).isNotBlank();
		assertThat(page.message().items()).hasSize(2);

		CrossrefPage.Work withoutAuthors = page.message().items().getFirst();
		assertThat(withoutAuthors.authors()).isNull();
		assertThat(withoutAuthors.issued().dateParts().getFirst()).containsExactly(2026, 7, 30);
		assertThat(withoutAuthors.indexed().dateTime()).isEqualTo("2026-08-01T01:09:14Z");
		assertThat(withoutAuthors.created().dateTime()).isEqualTo("2026-08-01T00:06:09Z");

		CrossrefPage.Work partialDate = page.message().items().get(1);
		assertThat(partialDate.issued().dateParts().getFirst()).containsExactly(2026, 7);
		assertThat(partialDate.authors()).singleElement()
				.extracting(CrossrefPage.Author::family)
				.isEqualTo("Hawley");
	}
}
