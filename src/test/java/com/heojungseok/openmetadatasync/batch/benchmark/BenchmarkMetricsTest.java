package com.heojungseok.openmetadatasync.batch.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkMetricsTest {

	private static final long MIB = 1024L * 1024;

	@Test
	void comparesEarlyAndLateRetainedFloorsAcrossSixtyFourSamples() {
		BenchmarkMetrics.HeapTrend bounded = BenchmarkMetrics.heapTrend(64, samples(50, 55));
		BenchmarkMetrics.HeapTrend growing = BenchmarkMetrics.heapTrend(64, samples(50, 60));
		BenchmarkMetrics.HeapTrend shrinking = BenchmarkMetrics.heapTrend(64, samples(55, 45));
		BenchmarkMetrics.HeapTrend percentageAllowance = BenchmarkMetrics.heapTrend(64, samples(100, 109));
		BenchmarkMetrics.HeapTrend insufficient = BenchmarkMetrics.heapTrend(63, samples(50, 50));

		assertThat(bounded).isEqualTo(new BenchmarkMetrics.HeapTrend(
				50 * MIB, 55 * MIB, 5 * MIB, 8 * MIB, true
		));
		assertThat(growing.plateau()).isFalse();
		assertThat(growing.retainedGrowthBytes()).isEqualTo(10 * MIB);
		assertThat(shrinking.plateau()).isTrue();
		assertThat(shrinking.retainedGrowthBytes()).isEqualTo(-10 * MIB);
		assertThat(percentageAllowance.allowedGrowthBytes()).isEqualTo(10 * MIB);
		assertThat(percentageAllowance.plateau()).isTrue();
		assertThat(insufficient.plateau()).isFalse();
	}

	private static List<Long> samples(long firstFloorMib, long lastFloorMib) {
		long ceilingMib = Math.max(firstFloorMib, lastFloorMib) + 20;
		List<Long> values = new ArrayList<>(Collections.nCopies(64, ceilingMib * MIB));
		values.set(0, firstFloorMib * MIB);
		values.set(48, lastFloorMib * MIB);
		return values;
	}
}
