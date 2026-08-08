package com.heojungseok.openmetadatasync.batch.job;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.heojungseok.openmetadatasync.batch.execution.RunMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossrefSyncJobConfigTest {

	private static final Instant BOOTSTRAP = Instant.parse("2025-01-01T00:00:00Z");
	private static final Instant WATERMARK = Instant.parse("2026-08-07T00:00:00Z");
	private static final Instant STARTED_AT = Instant.parse("2026-08-08T00:00:00Z");

	@Test
	void modeFlowNeverCallsCrossrefForReplayOrBenchmark() {
		assertThat(CrossrefSyncJobConfig.phases(RunMode.BACKFILL))
				.containsExactly("collect", "sync", "verify");
		assertThat(CrossrefSyncJobConfig.phases(RunMode.INCREMENTAL))
				.containsExactly("collect", "sync", "verify");
		assertThat(CrossrefSyncJobConfig.phases(RunMode.REPLAY_ERRORS))
				.containsExactly("replayPrepare", "sync", "verify");
		assertThat(CrossrefSyncJobConfig.phases(RunMode.BENCHMARK))
				.containsExactly("syntheticPreload", "sync", "verify");
	}

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
}
