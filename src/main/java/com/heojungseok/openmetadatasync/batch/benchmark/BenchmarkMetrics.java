package com.heojungseok.openmetadatasync.batch.benchmark;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

import org.hibernate.SessionEventListener;

public final class BenchmarkMetrics {

	private static final int TAIL_SAMPLES = 16;
	private static final LongAdder JDBC_BATCHES = new LongAdder();
	private static final ArrayDeque<Long> HEAP_TAIL = new ArrayDeque<>();
	private static UUID activeExecution;
	private static long baselineHeap;
	private static long peakHeap;
	private static int heapSamples;
	private static long syncStarted;
	private static long syncMillis;
	private static long verifyStarted;
	private static long verifyMillis;

	private BenchmarkMetrics() {
	}

	public static synchronized void begin(UUID executionId) {
		if (activeExecution != null && !activeExecution.equals(executionId)) {
			throw new IllegalStateException("Concurrent benchmark execution is not supported");
		}
		activeExecution = executionId;
		JDBC_BATCHES.reset();
		HEAP_TAIL.clear();
		baselineHeap = usedHeap();
		peakHeap = baselineHeap;
		heapSamples = 0;
		syncStarted = System.nanoTime();
		syncMillis = 0;
		verifyStarted = 0;
		verifyMillis = 0;
	}

	public static synchronized void sampleHeap() {
		long used = usedHeap();
		peakHeap = Math.max(peakHeap, used);
		heapSamples++;
		HEAP_TAIL.addLast(used);
		if (HEAP_TAIL.size() > TAIL_SAMPLES) {
			HEAP_TAIL.removeFirst();
		}
	}

	public static synchronized void endSync() {
		if (syncStarted != 0) {
			syncMillis = elapsedMillis(syncStarted);
			syncStarted = 0;
		}
	}

	public static synchronized void beginVerify() {
		verifyStarted = System.nanoTime();
	}

	public static synchronized void endVerify() {
		if (verifyStarted != 0) {
			verifyMillis = elapsedMillis(verifyStarted);
			verifyStarted = 0;
		}
	}

	public static synchronized Snapshot finish(UUID executionId) {
		if (!executionId.equals(activeExecution)) {
			throw new IllegalStateException("Benchmark metrics execution does not match");
		}
		long tailMin = HEAP_TAIL.stream().mapToLong(Long::longValue).min().orElse(baselineHeap);
		long tailMax = HEAP_TAIL.stream().mapToLong(Long::longValue).max().orElse(baselineHeap);
		long tolerance = Math.max(8L * 1024 * 1024, baselineHeap / 10);
		Snapshot snapshot = new Snapshot(
				baselineHeap, peakHeap, heapSamples, heapSamples >= 4 && tailMax - tailMin <= tolerance,
				JDBC_BATCHES.sum(), syncMillis, verifyMillis
		);
		activeExecution = null;
		return snapshot;
	}

	private static long usedHeap() {
		Runtime runtime = Runtime.getRuntime();
		return runtime.totalMemory() - runtime.freeMemory();
	}

	private static long elapsedMillis(long started) {
		return (System.nanoTime() - started) / 1_000_000;
	}

	public record Snapshot(
			long baselineHeap,
			long peakHeap,
			int heapSamples,
			boolean heapPlateau,
			long jdbcBatches,
			long syncMillis,
			long verifyMillis
	) {
	}

	public static final class SessionListener implements SessionEventListener {

		@Override
		public void jdbcExecuteBatchEnd() {
			JDBC_BATCHES.increment();
		}
	}
}
