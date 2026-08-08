package com.heojungseok.openmetadatasync.batch.job;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.heojungseok.openmetadatasync.batch.benchmark.BenchmarkEvidence;
import com.heojungseok.openmetadatasync.batch.benchmark.BenchmarkMetrics;
import com.heojungseok.openmetadatasync.batch.benchmark.JpaBenchmarkEvidenceCollector;
import com.heojungseok.openmetadatasync.batch.benchmark.JpaBenchmarkPreloader;
import com.heojungseok.openmetadatasync.batch.parameter.SyncContract;
import com.heojungseok.openmetadatasync.batch.parameter.Tuning;
import com.heojungseok.openmetadatasync.batch.sync.SyncWorkDto;

@Configuration(proxyBeanMethods = false)
public class DataPlaneBenchmarkJobConfig {

	public static JobParameters parameters(
			String requestId,
			long rowCount,
			long seed,
			String generatorVersion,
			String scenario,
			Tuning tuning,
			Path evidenceDirectory,
			boolean failFirstExecution
	) {
		if (requestId == null || requestId.isBlank() || rowCount <= 0
				|| !("initial".equals(scenario) || "no-op".equals(scenario))) {
			throw new IllegalArgumentException("Invalid benchmark request");
		}
		if (rowCount == 1_000_000) {
			BenchmarkEvidence.requireMillionGate(
					evidenceDirectory, seed, generatorVersion, SyncContract.hash(),
					tuning.chunkSize(), tuning.hibernateBatchSize()
			);
		}
		return new JobParametersBuilder()
				.addString("requestId", requestId, true)
				.addString("syncContractHash", SyncContract.hash(), true)
				.addString("mode", "BENCHMARK", true)
				.addLong("rowCount", rowCount, true)
				.addLong("seed", seed, true)
				.addString("generatorVersion", generatorVersion, true)
				.addString("scenario", scenario, true)
				.addLong("chunkSize", (long) tuning.chunkSize(), false)
				.addLong("hibernateBatchSize", (long) tuning.hibernateBatchSize(), false)
				.addString("evidenceDirectory", evidenceDirectory.toString(), false)
				.addLong("failFirstExecution", failFirstExecution ? 1L : 0L, false)
				.toJobParameters();
	}

	@Bean
	static HibernatePropertiesCustomizer benchmarkSessionMetrics() {
		return properties -> properties.put("hibernate.generate_statistics", true);
	}

	@Bean
	ReentrantLock dataPlaneRunLock() {
		return new ReentrantLock();
	}

	@Bean
	JobExecutionListener dataPlaneRunFence(
			@Qualifier("dataPlaneRunLock") ReentrantLock lock
	) {
		return new JobExecutionListener() {
			@Override
			public void beforeJob(JobExecution jobExecution) {
				if (!lock.tryLock()) {
					throw new IllegalStateException("Concurrent data-plane job is not supported");
				}
			}

			@Override
			public void afterJob(JobExecution jobExecution) {
				if (lock.isHeldByCurrentThread()) {
					lock.unlock();
				}
			}
		};
	}

	@Bean
	Job dataPlaneBenchmarkJob(
			JobRepository jobRepository,
			@Qualifier("dataPlaneRunFence") JobExecutionListener dataPlaneRunFence,
			@Qualifier("benchmarkPreloadStep") Step preload,
			@Qualifier("beginSyncStep") Step beginSync,
			@Qualifier("syncWorkStep") Step sync,
			@Qualifier("beginVerifyStep") Step beginVerify,
			@Qualifier("verifyExecutionStep") Step verify,
			@Qualifier("writeBenchmarkEvidenceStep") Step evidence
	) {
		return new JobBuilder("dataPlaneBenchmarkJob", jobRepository)
				.validator(DataPlaneBenchmarkJobConfig::validateMillionGate)
				.listener(dataPlaneRunFence)
				.start(preload)
				.next(beginSync)
				.next(sync)
				.next(beginVerify)
				.next(verify)
				.next(evidence)
				.build();
	}

	private static void validateMillionGate(JobParameters parameters) {
		if (Long.valueOf(1_000_000).equals(parameters.getLong("rowCount"))) {
			BenchmarkEvidence.requireMillionGate(
					Path.of(parameters.getString("evidenceDirectory")),
					parameters.getLong("seed"), parameters.getString("generatorVersion"),
					parameters.getString("syncContractHash"), parameters.getLong("chunkSize").intValue(),
					parameters.getLong("hibernateBatchSize").intValue()
			);
		}
	}

	@Bean
	Step benchmarkPreloadStep(
			JobRepository jobRepository,
			PlatformTransactionManager transactionManager,
			EntityManager entityManager,
			EntityManagerFactory entityManagerFactory
	) {
		return new StepBuilder("syntheticPreload", jobRepository)
				.tasklet((contribution, context) -> {
					JobExecution job = contribution.getStepExecution().getJobExecution();
					String requestId = required(job, "requestId");
					UUID executionId = executionId(requestId);
					JpaBenchmarkPreloader preloader = new JpaBenchmarkPreloader(
							entityManager, transactionManager, jobRepository
					);
					preloader.validateRestartContract(executionId, job.getJobParameters());
					validateBenchmark(job);
					int actualBatchSize = entityManagerFactory.unwrap(
							org.hibernate.engine.spi.SessionFactoryImplementor.class
					).getSessionFactoryOptions().getJdbcBatchSize();
					if (job.getJobParameters().getLong("hibernateBatchSize", 1000L) != actualBatchSize) {
						throw new IllegalArgumentException("Requested batch size does not match actual Hibernate batch size");
					}
					long started = System.nanoTime();
					TransactionTemplate noOuterTransaction = new TransactionTemplate(transactionManager);
					noOuterTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
					JpaBenchmarkPreloader.Frozen frozen = noOuterTransaction.execute(status ->
							preloader.preload(
									executionId, requestId, job.getId(), requiredLong(job, "rowCount"),
									requiredLong(job, "seed"), required(job, "generatorVersion")
							)
					);
					job.getExecutionContext().putString("syncExecutionId", executionId.toString());
					job.getExecutionContext().putLong("expectedCount", frozen.expectedCount());
					job.getExecutionContext().putLong("stagingUpperBound", frozen.stagingUpperBound());
					job.getExecutionContext().putLong("preloadMillis", (System.nanoTime() - started) / 1_000_000);
					return RepeatStatus.FINISHED;
				}, transactionManager)
				.build();
	}

	@Bean
	@Scope(value = "step", proxyMode = ScopedProxyMode.TARGET_CLASS)
	BenchmarkMetrics benchmarkWriteListener(
			EntityManager entityManager,
			EntityManagerFactory entityManagerFactory,
			JobRepository jobRepository
	) {
		return new BenchmarkMetrics(
				entityManager, entityManagerFactory.unwrap(SessionFactory.class), jobRepository
		);
	}

	@Bean
	Step writeBenchmarkEvidenceStep(
			JobRepository jobRepository,
			PlatformTransactionManager transactionManager,
			EntityManager entityManager,
			EntityManagerFactory entityManagerFactory
	) {
		return new StepBuilder("benchmarkEvidence", jobRepository)
				.tasklet((contribution, context) -> {
					JobExecution job = contribution.getStepExecution().getJobExecution();
					UUID executionId = UUID.fromString(job.getExecutionContext().getString("syncExecutionId"));
					java.util.List<JobExecution> executions = jobRepository.getJobExecutions(job.getJobInstance());
					java.util.Map<Long, JobExecution> measuredExecutions = new java.util.LinkedHashMap<>();
					executions.stream()
							.filter(execution -> execution.getStatus() == org.springframework.batch.core.BatchStatus.FAILED)
							.forEach(execution -> measuredExecutions.put(execution.getId(), execution));
					measuredExecutions.put(job.getId(), job);
					java.util.function.ToLongFunction<String> total = key -> measuredExecutions.values().stream()
							.filter(execution -> execution.getExecutionContext().containsKey(key))
							.mapToLong(execution -> execution.getExecutionContext().getLong(key))
							.sum();
					long tailMin = job.getExecutionContext().getLong("heapTailMin", 0);
					long tailMax = job.getExecutionContext().getLong("heapTailMax", 0);
					long baseline = job.getExecutionContext().getLong("heapBaseline", 0);
					int samples = job.getExecutionContext().getInt("heapSamples", 0);
					BenchmarkMetrics.Snapshot metrics = new BenchmarkMetrics.Snapshot(
							baseline, job.getExecutionContext().getLong("heapPeak", baseline), samples,
							samples >= 4 && tailMax - tailMin <= Math.max(8L * 1024 * 1024, baseline / 10),
							total.applyAsLong("syncJdbcBatches"),
							total.applyAsLong("syncQueries"),
							total.applyAsLong("syncPreparedStatements"),
							total.applyAsLong("syncTargetInserts"),
							total.applyAsLong("syncTargetUpdates"),
							total.applyAsLong("syncMillis"),
							job.getExecutionContext().getLong("verifyMillis", 0)
					);
					boolean previousFailed = executions.stream().anyMatch(execution ->
							execution.getId() != job.getId() && execution.getStatus() == org.springframework.batch.core.BatchStatus.FAILED
					);
					boolean durableCheckpoint = executions.stream()
							.filter(execution -> execution.getId() != job.getId()
									&& execution.getStatus() == org.springframework.batch.core.BatchStatus.FAILED)
							.flatMap(execution -> execution.getStepExecutions().stream())
							.filter(step -> step.getStepName().equals("sync") && step.getCommitCount() > 0)
							.flatMap(step -> step.getExecutionContext().entrySet().stream())
							.anyMatch(entry -> entry.getKey().endsWith(".lastCommittedKey")
									&& entry.getValue() instanceof Long value && value > 0);
					BenchmarkEvidence evidence = new JpaBenchmarkEvidenceCollector(
							entityManager, entityManagerFactory.unwrap(SessionFactory.class)
					).collect(
							executionId,
							required(job, "scenario"),
							requiredLong(job, "rowCount"),
							requiredLong(job, "seed"),
							required(job, "generatorVersion"),
							required(job, "syncContractHash"),
							job.getJobParameters().getLong("chunkSize", 1000L).intValue(),
							job.getExecutionContext().getString("verifyBatchStatus"),
							job.getExecutionContext().getString("verifyExitStatus"),
							job.getExecutionContext().getString("targetUpdatedAtBefore", ""),
							previousFailed,
							previousFailed && durableCheckpoint
									&& "COMPLETED".equals(job.getExecutionContext().getString("verifyBatchStatus")),
							job.getExecutionContext().getLong("preloadMillis", 0),
							job.getJobParameters().getLong("hibernateBatchSize", 1000L).intValue(),
							metrics
					);
					evidence.write(Path.of(required(job, "evidenceDirectory")));
					return RepeatStatus.FINISHED;
				}, transactionManager)
				.build();
	}

	static UUID executionId(String requestId) {
		try {
			return UUID.fromString(requestId);
		} catch (IllegalArgumentException ignored) {
			return UUID.nameUUIDFromBytes(requestId.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void validateBenchmark(JobExecution job) {
		if (!"BENCHMARK".equals(required(job, "mode"))) {
			throw new IllegalArgumentException("dataPlaneBenchmarkJob requires BENCHMARK mode");
		}
		if (!SyncContract.hash().equals(required(job, "syncContractHash"))) {
			throw new IllegalArgumentException("syncContractHash does not match this build");
		}
		String scenario = required(job, "scenario");
		if (!("initial".equals(scenario) || "no-op".equals(scenario))) {
			throw new IllegalArgumentException("scenario must be initial or no-op");
		}
		if (job.getJobParameters().getLong("chunkSize", 1000L) <= 0
				|| job.getJobParameters().getLong("hibernateBatchSize", 1000L) <= 0) {
			throw new IllegalArgumentException("Benchmark tuning must be positive");
		}
	}

	private static String required(JobExecution job, String name) {
		String value = job.getJobParameters().getString(name);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " is required");
		}
		return value;
	}

	private static long requiredLong(JobExecution job, String name) {
		Long value = job.getJobParameters().getLong(name);
		if (value == null) {
			throw new IllegalArgumentException(name + " is required");
		}
		return value;
	}
}
