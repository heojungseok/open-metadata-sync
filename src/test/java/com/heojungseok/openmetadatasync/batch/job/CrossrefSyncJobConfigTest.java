package com.heojungseok.openmetadatasync.batch.job;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.heojungseok.openmetadatasync.batch.collect.CrossrefCollector;

class CrossrefSyncJobConfigTest {

	private static final Instant BOOTSTRAP = Instant.parse("2025-01-01T00:00:00Z");
	private static final Instant WATERMARK = Instant.parse("2026-08-07T00:00:00Z");
	private static final Instant STARTED_AT = Instant.parse("2026-08-08T00:00:00Z");

	@Test
	void incrementalStartsAtTheWatermarkAndFreezesExecutionStartAsUntil() {
		CrossrefSyncJobConfig.IncrementalRange range =
				CrossrefSyncJobConfig.incrementalRange(WATERMARK, BOOTSTRAP, STARTED_AT);

		assertThat(range.from()).isEqualTo(WATERMARK);
		assertThat(range.until()).isEqualTo(STARTED_AT);
	}

	@Test
	void incrementalUsesBootstrapWithoutWatermarkAndFailsFastWhenBothAreMissing() {
		assertThat(CrossrefSyncJobConfig.incrementalRange(null, BOOTSTRAP, STARTED_AT).from())
				.isEqualTo(BOOTSTRAP);
		assertThatThrownBy(() -> CrossrefSyncJobConfig.incrementalRange(null, null, STARTED_AT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("bootstrapIndexedFrom");
	}

	@Test
	void collectionEvidenceIsCopiedToTheDurableExecution() {
		JobControlExecution execution = new JobControlExecution();
		CrossrefCollector.Result result = new CrossrefCollector.Result(
				10_000, 10_000, 42_000, 10, CrossrefCollector.StopReason.MAX_ITEMS,
				List.of(new CrossrefCollector.WindowEvidence(
						UUID.randomUUID(), 42_000, 10, 10, 10, 10_000
				)), 12
		);

		CrossrefSyncJobConfig.applyCollectionEvidence(execution, result);

		assertThat(execution.collectionPagesFetched).isEqualTo(10);
		assertThat(execution.collectionReportedTotal).isEqualTo(42_000);
		assertThat(execution.collectionStopReason).isEqualTo("MAX_ITEMS");
		assertThat(execution.collectionPageSafetyCap).isEqualTo(12);
	}
}
