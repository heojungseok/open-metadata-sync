package com.heojungseok.openmetadatasync.batch.job;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.SessionEventSettings;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.Chunk;
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
		return properties -> {
			properties.put("hibernate.generate_statistics", true);
			properties.putIfAbsent(
					SessionEventSettings.AUTO_SESSION_EVENTS_LISTENER,
					BenchmarkMetrics.SessionListener.class.getName()
			);
		};
	}

	@Bean
	Job dataPlaneBenchmarkJob(
			JobRepository jobRepository,
			@Qualifier("benchmarkPreloadStep") Step preload,
			@Qualifier("beginBenchmarkMetricsStep") Step metrics,
			@Qualifier("beginSyncStep") Step beginSync,
			@Qualifier("syncWorkStep") Step sync,
			@Qualifier("beginVerifyStep") Step beginVerify,
			@Qualifier("verifyExecutionStep") Step verify,
			@Qualifier("writeBenchmarkEvidenceStep") Step evidence
	) {
		return new JobBuilder("dataPlaneBenchmarkJob", jobRepository)
				.start(preload)
				.next(metrics)
				.next(beginSync)
				.next(sync)
				.next(beginVerify)
				.next(verify)
				.next(evidence)
				.build();
	}

	@Bean
	Step benchmarkPreloadStep(
			JobRepository jobRepository,
			PlatformTransactionManager transactionManager,
			EntityManager entityManager
	) {
		return new StepBuilder("syntheticPreload", jobRepository)
				.tasklet((contribution, context) -> {
					JobExecution job = contribution.getStepExecution().getJobExecution();
					validateBenchmark(job);
					String requestId = required(job, "requestId");
					UUID executionId = executionId(requestId);
					long started = System.nanoTime();
					TransactionTemplate noOuterTransaction = new TransactionTemplate(transactionManager);
					noOuterTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
					JpaBenchmarkPreloader.Frozen frozen = noOuterTransaction.execute(status ->
							new JpaBenchmarkPreloader(entityManager, transactionManager).preload(
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
	Step beginBenchmarkMetricsStep(
			JobRepository jobRepository,
			PlatformTransactionManager transactionManager,
			EntityManager entityManager,
			EntityManagerFactory entityManagerFactory
	) {
		return new StepBuilder("beginBenchmarkMetrics", jobRepository)
				.allowStartIfComplete(true)
				.tasklet((contribution, context) -> {
					JobExecution job = contribution.getStepExecution().getJobExecution();
					UUID executionId = UUID.fromString(job.getExecutionContext().getString("syncExecutionId"));
					SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
					String updatedAtBefore = new JpaBenchmarkEvidenceCollector(entityManager, sessionFactory)
							.targetUpdatedAt(executionId);
					job.getExecutionContext().putString("targetUpdatedAtBefore", updatedAtBefore);
					sessionFactory.getStatistics().clear();
					BenchmarkMetrics.begin(executionId);
					return RepeatStatus.FINISHED;
				}, transactionManager)
				.build();
	}

	@Bean
	@Scope(value = "step", proxyMode = ScopedProxyMode.INTERFACES)
	ItemWriteListener<SyncWorkDto> benchmarkWriteListener(
			JobRepository jobRepository,
			@Value("#{stepExecution}") StepExecution stepExecution,
			@Value("#{jobParameters['mode']}") String mode,
			@Value("#{jobParameters['failFirstExecution'] ?: 0}") Long failFirstExecution
	) {
		return new ItemWriteListener<>() {
			@Override
			public void afterWrite(Chunk<? extends SyncWorkDto> items) {
				if (!"BENCHMARK".equals(mode)) {
					return;
				}
				BenchmarkMetrics.sampleHeap();
				if (failFirstExecution == 1 && jobRepository.getJobExecutions(
						stepExecution.getJobExecution().getJobInstance()
				).size() == 1) {
					throw new IllegalStateException("Injected benchmark restart gate failure");
				}
			}
		};
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
					BenchmarkMetrics.Snapshot metrics = BenchmarkMetrics.finish(executionId);
					int executions = jobRepository.getJobExecutions(job.getJobInstance()).size();
					BenchmarkEvidence evidence = new JpaBenchmarkEvidenceCollector(
							entityManager, entityManagerFactory.unwrap(SessionFactory.class)
					).collect(
							executionId,
							required(job, "scenario"),
							requiredLong(job, "rowCount"),
							requiredLong(job, "seed"),
							required(job, "generatorVersion"),
							job.getExecutionContext().getString("verifyBatchStatus"),
							job.getExecutionContext().getString("verifyExitStatus"),
							job.getExecutionContext().getString("targetUpdatedAtBefore", ""),
							executions > 1,
							executions > 1 && "COMPLETED".equals(job.getExecutionContext().getString("verifyBatchStatus")),
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
