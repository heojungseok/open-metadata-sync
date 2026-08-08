package com.heojungseok.openmetadatasync.batch.benchmark;

import java.util.ArrayDeque;

import jakarta.persistence.EntityManager;

import org.hibernate.SessionEventListener;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.stat.Statistics;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;

import com.heojungseok.openmetadatasync.batch.sync.SyncWorkDto;

public class BenchmarkMetrics implements ItemWriteListener<SyncWorkDto>, StepExecutionListener {

	private static final int TAIL_SAMPLES = 16;
	private static final String TARGET_ENTITY =
			"com.heojungseok.openmetadatasync.batch.sync.SyncTargetWork";

	private final EntityManager entityManager;
	private final SessionFactory sessionFactory;
	private final JobRepository jobRepository;
	private final ArrayDeque<Long> heapTail = new ArrayDeque<>();
	private StepExecution stepExecution;
	private boolean benchmark;
	private long queryBefore;
	private long statementsBefore;
	private long insertsBefore;
	private long updatesBefore;
	private long jdbcBatches;
	private long baselineHeap;
	private long peakHeap;
	private int heapSamples;
	private long started;

	public BenchmarkMetrics(
			EntityManager entityManager,
			SessionFactory sessionFactory,
			JobRepository jobRepository
	) {
		this.entityManager = entityManager;
		this.sessionFactory = sessionFactory;
		this.jobRepository = jobRepository;
	}

	@Override
	public void beforeStep(StepExecution stepExecution) {
		this.stepExecution = stepExecution;
		benchmark = "BENCHMARK".equals(stepExecution.getJobParameters().getString("mode"));
		if (!benchmark) {
			return;
		}
		Statistics statistics = sessionFactory.getStatistics();
		queryBefore = statistics.getQueryExecutionCount();
		statementsBefore = statistics.getPrepareStatementCount();
		insertsBefore = statistics.getEntityStatistics(TARGET_ENTITY).getInsertCount();
		updatesBefore = statistics.getEntityStatistics(TARGET_ENTITY).getUpdateCount();
		baselineHeap = usedHeap();
		peakHeap = baselineHeap;
		started = System.nanoTime();
	}

	@Override
	public void beforeWrite(Chunk<? extends SyncWorkDto> items) {
		if (!benchmark) {
			return;
		}
		entityManager.unwrap(SharedSessionContractImplementor.class).getEventListenerManager()
				.addListener(new SessionEventListener() {
					@Override
					public void jdbcExecuteBatchEnd() {
						jdbcBatches++;
					}
				});
		if (stepExecution.getJobParameters().getLong("failFirstExecution", 0L) == 1
				&& stepExecution.getWriteCount() > 0
				&& jobRepository.getJobExecutions(stepExecution.getJobExecution().getJobInstance()).size() == 1) {
			throw new IllegalStateException("Injected benchmark restart gate failure");
		}
	}

	@Override
	public void afterWrite(Chunk<? extends SyncWorkDto> items) {
		if (!benchmark) {
			return;
		}
		long used = usedHeap();
		peakHeap = Math.max(peakHeap, used);
		heapSamples++;
		heapTail.addLast(used);
		if (heapTail.size() > TAIL_SAMPLES) {
			heapTail.removeFirst();
		}
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		if (!benchmark) {
			return null;
		}
		Statistics statistics = sessionFactory.getStatistics();
		JobExecution job = stepExecution.getJobExecution();
		job.getExecutionContext().putLong("syncQueries", statistics.getQueryExecutionCount() - queryBefore);
		job.getExecutionContext().putLong(
				"syncPreparedStatements", statistics.getPrepareStatementCount() - statementsBefore
		);
		job.getExecutionContext().putLong(
				"syncTargetInserts",
				statistics.getEntityStatistics(TARGET_ENTITY).getInsertCount() - insertsBefore
		);
		job.getExecutionContext().putLong(
				"syncTargetUpdates",
				statistics.getEntityStatistics(TARGET_ENTITY).getUpdateCount() - updatesBefore
		);
		job.getExecutionContext().putLong("syncJdbcBatches", jdbcBatches);
		job.getExecutionContext().putLong("heapBaseline", baselineHeap);
		job.getExecutionContext().putLong("heapPeak", peakHeap);
		job.getExecutionContext().putInt("heapSamples", heapSamples);
		job.getExecutionContext().putLong("heapTailMin", heapTail.stream().mapToLong(Long::longValue).min().orElse(baselineHeap));
		job.getExecutionContext().putLong("heapTailMax", heapTail.stream().mapToLong(Long::longValue).max().orElse(baselineHeap));
		job.getExecutionContext().putLong("syncMillis", (System.nanoTime() - started) / 1_000_000);
		return null;
	}

	private static long usedHeap() {
		Runtime runtime = Runtime.getRuntime();
		return runtime.totalMemory() - runtime.freeMemory();
	}

	public record Snapshot(
			long baselineHeap,
			long peakHeap,
			int heapSamples,
			boolean heapPlateau,
			long jdbcBatches,
			long queries,
			long preparedStatements,
			long targetInserts,
			long targetUpdates,
			long syncMillis,
			long verifyMillis
	) {
	}
}
