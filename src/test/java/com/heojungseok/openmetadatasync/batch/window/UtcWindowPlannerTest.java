package com.heojungseok.openmetadatasync.batch.window;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UtcWindowPlannerTest {

	@Test
	void splitsAFixedIncrementalRangeAtUtcMidnightWithoutGaps() {
		var windows = UtcWindowPlanner.plan(
				Instant.parse("2026-08-01T18:00:00Z"),
				Instant.parse("2026-08-03T06:00:00Z")
		);

		assertThat(windows).containsExactly(
				new UtcWindowPlanner.Window(0, Instant.parse("2026-08-01T18:00:00Z"), Instant.parse("2026-08-02T00:00:00Z")),
				new UtcWindowPlanner.Window(1, Instant.parse("2026-08-02T00:00:00Z"), Instant.parse("2026-08-03T00:00:00Z")),
				new UtcWindowPlanner.Window(2, Instant.parse("2026-08-03T00:00:00Z"), Instant.parse("2026-08-03T06:00:00Z"))
		);
		assertThat(UtcWindowPlanner.pending(windows, Set.of(0, 2))).containsExactly(windows.get(1));
	}

	@Test
	void advancesWatermarkOnlyAfterConflictFreeFullRangeVerification() {
		Instant current = Instant.parse("2026-08-01T00:00:00Z");
		Instant requestedUntil = Instant.parse("2026-08-02T00:00:00Z");

		assertThat(WatermarkPolicy.advance(current, requestedUntil, true, false)).isEqualTo(requestedUntil);
		assertThatThrownBy(() -> WatermarkPolicy.advance(current, requestedUntil, false, false))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> WatermarkPolicy.advance(current, requestedUntil, true, true))
				.isInstanceOf(IllegalStateException.class);
	}
}
