package com.heojungseok.openmetadatasync.batch.benchmark;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyntheticWorkGeneratorTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void sameSeedVersionAndSequenceProduceTheSameFixedStagingRow() {
		SyntheticWorkGenerator first = new SyntheticWorkGenerator(42, "v1");
		SyntheticWorkGenerator second = new SyntheticWorkGenerator(42, "v1");

		SyntheticWorkGenerator.Row row = first.row(7);

		assertThat(row).usingRecursiveComparison().isEqualTo(second.row(7));
		assertThat(row.executionSequence()).isEqualTo(7);
		assertThat(row.doi()).isEqualTo("10.5555/benchmark-v1-42-7");
		assertThat(row.title()).isEqualTo("Synthetic work 7");
		assertThat(row.publisher()).isEqualTo("Open Metadata Benchmark");
		assertThat(row.workType()).isEqualTo("journal-article");
		assertThat(row.issuedDate()).matches("\\d{4}-\\d{2}-\\d{2}");
		assertThat(row.issuedDatePrecision()).isEqualTo((byte) 3);
		assertThat(row.url()).isEqualTo("https://doi.org/" + row.doi());
		assertThat(objectMapper.readTree(row.sourceJson()).get("DOI").asString()).isEqualTo(row.doi());
		assertThat(objectMapper.readTree(row.authorsJson()).isArray()).isTrue();
		assertThat(row.canonicalVersion()).isEqualTo(1);
		assertThat(row.contentHash()).hasSize(32);
		assertThat(row.authorHash()).hasSize(32);
		assertThat(row.indexedAt()).isNotNull();
		assertThat(row.sourceCreatedAt()).isBefore(row.indexedAt());
		assertThat(row.collectedAt()).isAfterOrEqualTo(row.indexedAt());
	}

	@Test
	void seedVersionAndSequenceAllParticipateInDeterministicIdentityAndChecksum() {
		SyntheticWorkGenerator.Row baseline = new SyntheticWorkGenerator(42, "v1").row(1);

		assertThat(new SyntheticWorkGenerator(43, "v1").row(1).doi()).isNotEqualTo(baseline.doi());
		assertThat(new SyntheticWorkGenerator(42, "v1").row(2).contentHash())
				.isNotEqualTo(baseline.contentHash());
	}

	@Test
	void rejectsUnknownGeneratorVersionAndNonPositiveSequence() {
		assertThatThrownBy(() -> new SyntheticWorkGenerator(1, "v2"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("generatorVersion");
		assertThatThrownBy(() -> new SyntheticWorkGenerator(1, "v1").row(0))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
