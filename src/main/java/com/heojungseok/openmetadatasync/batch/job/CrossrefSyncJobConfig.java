package com.heojungseok.openmetadatasync.batch.job;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.heojungseok.openmetadatasync.batch.collect.CrossrefCollector;
import com.heojungseok.openmetadatasync.batch.collect.HttpCrossrefClient;
import com.heojungseok.openmetadatasync.batch.collect.JpaCollectStore;
import com.heojungseok.openmetadatasync.batch.benchmark.BenchmarkMetrics;
import com.heojungseok.openmetadatasync.batch.execution.RunMode;
import com.heojungseok.openmetadatasync.batch.observability.BatchLifecycleLoggingListener;
import com.heojungseok.openmetadatasync.batch.parameter.SyncContract;
import com.heojungseok.openmetadatasync.batch.replay.JpaErrorReplayPreparer;
import com.heojungseok.openmetadatasync.batch.sync.ChunkAwareJpaWorkWriter;
import com.heojungseok.openmetadatasync.batch.sync.JpaKeysetWorkReader;
import com.heojungseok.openmetadatasync.batch.sync.SyncWorkDto;
import com.heojungseok.openmetadatasync.batch.verify.JpaExecutionVerifier;
import com.heojungseok.openmetadatasync.batch.verify.VerificationResult;
import com.heojungseok.openmetadatasync.batch.window.UtcWindowPlanner;

@Configuration(proxyBeanMethods = false)
public class CrossrefSyncJobConfig {

	static IncrementalRange incrementalRange(Instant watermark, Instant bootstrapIndexedFrom, Instant startedAt) {
		Instant from = watermark == null ? bootstrapIndexedFrom : watermark;
		if (from == null) {
			throw new IllegalArgumentException("bootstrapIndexedFrom is required without a watermark");
		}
		if (!from.isBefore(Objects.requireNonNull(startedAt))) {
			throw new IllegalArgumentException("Incremental range must be increasing");
		}
		return new IncrementalRange(from, startedAt);
	}

	@Bean
	HttpCrossrefClient crossrefHttpClient() {
		return new HttpCrossrefClient(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
				Duration.ofSeconds(30), "open-metadata-sync/1.0", Clock.systemUTC()
		);
	}

	@Bean
	Job crossrefSyncJob(
			JobRepository jobRepository,
			@Qualifier("dataPlaneRunFence") JobExecutionListener dataPlaneRunFence,
			BatchLifecycleLoggingListener batchLifecycle,
			@Qualifier("prepareCrossrefExecutionStep") Step prepare,
			JobExecutionDecider crossrefModeDecider,
			@Qualifier("collectCrossrefStep") Step collect,
			@Qualifier("prepareReplayStep") Step replay,
			@Qualifier("beginSyncStep") Step beginSync,
			@Qualifier("syncWorkStep") Step sync,
			@Qualifier("beginVerifyStep") Step beginVerify,
			@Qualifier("verifyExecutionStep") Step verify
	) {
		return new JobBuilder("crossrefSyncJob", jobRepository)
				.listener(dataPlaneRunFence)
				.listener(batchLifecycle)
				.start(prepare)
				.next(crossrefModeDecider).on("COLLECT").to(collect)
				.from(crossrefModeDecider).on("REPLAY").to(replay)
				.from(collect).on("COMPLETED*").to(beginSync)
				.from(replay).on("COMPLETED*").to(beginSync)
				.from(beginSync).on("COMPLETED*").to(sync)
				.from(sync).on("COMPLETED*").to(beginVerify)
				.from(beginVerify).on("COMPLETED*").to(verify)
				.end()
				.build();
	}

	@Bean
	JobExecutionDecider crossrefModeDecider() {
		return (jobExecution, stepExecution) -> switch (mode(jobExecution)) {
			case BACKFILL, INCREMENTAL -> new FlowExecutionStatus("COLLECT");
			case REPLAY_ERRORS -> new FlowExecutionStatus("REPLAY");
			case BENCHMARK -> throw new IllegalArgumentException("BENCHMARK uses dataPlaneBenchmarkJob");
		};
	}

	@Bean
	Step prepareCrossrefExecutionStep(
			JobRepository jobRepository,
			PlatformTransactionManager transactionManager,
			EntityManager entityManager,
			BatchLifecycleLoggingListener batchLifecycle
	) {
		return new StepBuilder("prepareCrossrefExecution", jobRepository)
				.tasklet((contribution, context) -> {
					prepareCrossrefExecution(
							entityManager, jobRepository, contribution.getStepExecution().getJobExecution()
					);
					return RepeatStatus.FINISHED;
				}, transactionManager)
				.listener((StepExecutionListener) batchLifecycle)
				.build();
	}

	@Bean
	Step collectCrossrefStep(
			JobRepository jobRepository,
			PlatformTransactionManager transactionManager,
			EntityManager entityManager,
			JpaCollectStore store,
			HttpCrossrefClient client,
			BatchLifecycleLoggingListener batchLifecycle,
			@Value("${crossref.base-uri:https://api.crossref.org/works}") String baseUri
	) {
		return new StepBuilder("collect", jobRepository)
				.tasklet((contribution, context) -> {
					JobExecution job = contribution.getStepExecution().getJobExecution();
					UUID executionId = executionId(job);
					TransactionTemplate noOuterTransaction = new TransactionTemplate(transactionManager);
					noOuterTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
					CrossrefCollector.Result result = noOuterTransaction.execute(status -> {
						JobControlExecution execution = entityManager.find(JobControlExecution.class, executionId);
						java.util.List<JobControlWindow> windows = entityManager.createQuery("""
								select window from JobControlWindow window
								where window.executionId = :executionId and window.status <> 'COLLECTED'
								order by window.windowSequence
								""", JobControlWindow.class)
								.setParameter("executionId", executionId)
								.getResultList();
						java.util.List<CrossrefCollector.Window> requests = windows.stream()
								.map(window -> new CrossrefCollector.Window(
										window.id, crossrefUri(baseUri, execution, window)
								))
								.toList();
						long maxItems = execution.maxItems == null ? Long.MAX_VALUE : execution.maxItems;
						CrossrefCollector collector = new CrossrefCollector(
								client, store, Thread::sleep, Clock.systemUTC()
						);
						return collector.collect(new CrossrefCollector.Request(
								executionId, requests, maxItems,
								job.getJobParameters().getLong("pageSafetyCap", 100_000L).intValue(), 2
						));
					});
					job.getExecutionContext().putLong("stagingUpperBound", result.stagingUpperBound());
					job.getExecutionContext().putLong("expectedCount", result.expectedCount());
					return RepeatStatus.FINISHED;
				}, transactionManager)
				.listener((StepExecutionListener) batchLifecycle)
				.build();
	}

	@Bean
	Step prepareReplayStep(
			JobRepository jobRepository,
			PlatformTransactionManager transactionManager,
			EntityManager entityManager,
			BatchLifecycleLoggingListener batchLifecycle
	) {
		org.springframework.transaction.interceptor.DefaultTransactionAttribute definition =
				new org.springframework.transaction.interceptor.DefaultTransactionAttribute();
		definition.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		return new StepBuilder("replayPrepare", jobRepository)
				.tasklet((contribution, context) -> {
					JobExecution job = contribution.getStepExecution().getJobExecution();
					JpaErrorReplayPreparer.Prepared prepared = new JpaErrorReplayPreparer(entityManager).prepare(
							executionId(job), UUID.fromString(job.getJobParameters().getString("sourceExecutionId"))
					);
					job.getExecutionContext().putLong("stagingUpperBound", prepared.stagingUpperBound());
					job.getExecutionContext().putLong("expectedCount", prepared.expectedCount());
					return RepeatStatus.FINISHED;
				}, transactionManager)
				.transactionAttribute(definition)
				.listener((StepExecutionListener) batchLifecycle)
				.build();
	}

	@Bean
	Step beginSyncStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
			EntityManager entityManager, BatchLifecycleLoggingListener batchLifecycle) {
		return statusStep("beginSync", "SYNCING", jobRepository, transactionManager, entityManager, batchLifecycle);
	}

	@Bean
	Step beginVerifyStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
			EntityManager entityManager, BatchLifecycleLoggingListener batchLifecycle) {
		return statusStep("beginVerify", "VERIFYING", jobRepository, transactionManager, entityManager, batchLifecycle);
	}

	@Bean
	@Scope(value = "job", proxyMode = ScopedProxyMode.INTERFACES)
	Step syncWorkStep(
			JobRepository jobRepository,
			PlatformTransactionManager transactionManager,
			ItemStreamReader<SyncWorkDto> syncWorkReader,
			ItemWriter<SyncWorkDto> syncWorkWriter,
			BenchmarkMetrics benchmarkWriteListener,
			BatchLifecycleLoggingListener batchLifecycle,
			@Value("#{jobParameters['chunkSize'] ?: 1000}") Long chunkSize
	) {
		return new StepBuilder("sync", jobRepository)
				.<SyncWorkDto, SyncWorkDto>chunk(chunkSize.intValue())
				.reader(syncWorkReader)
				.writer(syncWorkWriter)
				.listener((ItemWriteListener<SyncWorkDto>) benchmarkWriteListener)
				.listener((StepExecutionListener) benchmarkWriteListener)
				.listener((StepExecutionListener) batchLifecycle)
				.listener((org.springframework.batch.core.listener.ChunkListener<SyncWorkDto, SyncWorkDto>) batchLifecycle)
				.transactionManager(transactionManager)
				.build();
	}

	@Bean
	@Scope(value = "step", proxyMode = ScopedProxyMode.INTERFACES)
	ItemStreamReader<SyncWorkDto> syncWorkReader(
			EntityManager entityManager,
			@Value("#{jobExecutionContext['syncExecutionId']}") String executionId,
			@Value("#{jobExecutionContext['stagingUpperBound']}") Long stagingUpperBound,
			@Value("#{jobParameters['chunkSize'] ?: 1000}") Long chunkSize
	) {
		return new JpaKeysetWorkReader(
				entityManager, UUID.fromString(executionId), stagingUpperBound, chunkSize.intValue()
		);
	}

	@Bean
	@Scope(value = "step", proxyMode = ScopedProxyMode.INTERFACES)
	ItemWriter<SyncWorkDto> syncWorkWriter(
			EntityManager entityManager,
			@Value("#{jobExecutionContext['syncExecutionId']}") String executionId,
			@Value("#{stepExecution.id}") Long stepExecutionId
	) {
		return new ChunkAwareJpaWorkWriter(entityManager, UUID.fromString(executionId), stepExecutionId);
	}

	@Bean
	Step verifyExecutionStep(
			JobRepository jobRepository,
			PlatformTransactionManager transactionManager,
			EntityManager entityManager,
			BatchLifecycleLoggingListener batchLifecycle
	) {
		return new StepBuilder("verify", jobRepository)
				.tasklet((contribution, context) -> {
					JobExecution job = contribution.getStepExecution().getJobExecution();
					long started = System.nanoTime();
					VerificationResult result = new JpaExecutionVerifier(entityManager).verify(
							executionId(job), job.getJobParameters().getString("sourceName", "crossref")
					);
					if (mode(job) == RunMode.BENCHMARK) {
						job.getExecutionContext().putLong(
								"verifyMillis", (System.nanoTime() - started) / 1_000_000
						);
					}
					contribution.setExitStatus(result.exitStatus());
					job.getExecutionContext().putString("verifyBatchStatus", result.batchStatus().name());
					job.getExecutionContext().putString("verifyExitStatus", result.exitStatus().getExitCode());
					if (result.batchStatus() == BatchStatus.FAILED) {
						contribution.getStepExecution().upgradeStatus(BatchStatus.FAILED);
					}
					return RepeatStatus.FINISHED;
				}, transactionManager)
				.listener((StepExecutionListener) batchLifecycle)
				.build();
	}

	private static Step statusStep(
			String name,
			String target,
			JobRepository repository,
			PlatformTransactionManager transactionManager,
			EntityManager entityManager,
			BatchLifecycleLoggingListener batchLifecycle
	) {
		return new StepBuilder(name, repository)
				.tasklet((contribution, context) -> {
					JobExecution job = contribution.getStepExecution().getJobExecution();
					JobControlExecution execution = entityManager.find(
							JobControlExecution.class,
							executionId(job),
							LockModeType.PESSIMISTIC_WRITE
					);
					if ("SYNCING".equals(target)
							&& !("COLLECTED".equals(execution.businessStatus) || "PREPARING".equals(execution.businessStatus))) {
						throw new IllegalStateException("Execution is not ready for sync");
					}
					if ("VERIFYING".equals(target) && !"SYNCING".equals(execution.businessStatus)) {
						throw new IllegalStateException("Execution is not ready for verify");
					}
					if ("SYNCING".equals(target) && mode(job) == RunMode.BENCHMARK) {
						Instant updated = entityManager.createQuery("""
								select max(target.updatedAt) from BenchmarkTargetEvidence target where exists (
								  select staging.stagingKey from BenchmarkStagingWork staging
								  where staging.executionId = :executionId and staging.doi = target.doi
								)
								""", Instant.class).setParameter("executionId", execution.id).getSingleResult();
						job.getExecutionContext().putString(
								"targetUpdatedAtBefore", updated == null ? "" : updated.toString()
						);
					}
					execution.businessStatus = target;
					execution.updatedAt = Instant.now();
					return RepeatStatus.FINISHED;
				}, transactionManager)
				.listener((StepExecutionListener) batchLifecycle)
				.build();
	}

	private static void prepareCrossrefExecution(
			EntityManager entityManager, JobRepository jobRepository, JobExecution job
	) {
		String requestId = required(job, "requestId");
		String contract = required(job, "syncContractHash");
		if (!SyncContract.hash().equals(contract)) {
			throw new IllegalArgumentException("syncContractHash does not match this build");
		}
		java.util.List<JobControlExecution> existing = entityManager.createQuery("""
				select execution from JobControlExecution execution where execution.requestId = :requestId
				""", JobControlExecution.class).setParameter("requestId", requestId).getResultList();
		if (!existing.isEmpty()) {
			JobControlExecution execution = existing.getFirst();
			JobExecution original = execution.batchJobExecutionId == null
					? null : jobRepository.getJobExecution(execution.batchJobExecutionId);
			if (!execution.mode.equals(mode(job).name()) || !execution.syncContractHash.equals(contract)
					|| original == null || frozenParametersChanged(mode(job), original, job)) {
				throw new IllegalStateException("Frozen request contract changed");
			}
			putExecutionContext(job, execution);
			return;
		}
		Instant started = Instant.now();
		RunMode mode = mode(job);
		UUID id = UUID.randomUUID();
		JobControlExecution execution = new JobControlExecution(id, requestId, mode, contract, job.getId(), started);
		entityManager.persist(execution);
		entityManager.flush();
		if (mode == RunMode.BACKFILL) {
			execution.createdFrom = job.getJobParameters().getLocalDate("createdFrom");
			execution.createdUntil = job.getJobParameters().getLocalDate("createdUntil");
			execution.maxItems = job.getJobParameters().getLong("maxItems");
			if (execution.createdFrom == null || execution.createdUntil == null || execution.maxItems == null) {
				throw new IllegalArgumentException("Backfill range and maxItems are required");
			}
			entityManager.persist(new JobControlWindow(id, 0, null, null, started));
		} else if (mode == RunMode.INCREMENTAL) {
			String sourceName = job.getJobParameters().getString("sourceName", "crossref");
			JobWatermark watermark = entityManager.find(JobWatermark.class, sourceName);
			String bootstrap = job.getJobParameters().getString("bootstrapIndexedFrom");
			String requestedFrom = job.getJobParameters().getString("indexedFromUtc");
			String requestedUntil = job.getJobParameters().getString("indexedUntilUtc");
			Instant frozenUntil = requestedUntil == null ? started : Instant.parse(requestedUntil);
			Instant frozenFrom;
			if (watermark == null) {
				frozenFrom = incrementalRange(
						null, bootstrap == null ? null : Instant.parse(bootstrap), frozenUntil
				).from();
				if (requestedFrom != null && !frozenFrom.equals(Instant.parse(requestedFrom))) {
					throw new IllegalArgumentException("indexedFromUtc must match bootstrapIndexedFrom");
				}
			} else {
				frozenFrom = watermark.indexedUntilUtc;
				if (requestedFrom != null && !frozenFrom.equals(Instant.parse(requestedFrom))) {
					throw new IllegalArgumentException("indexedFromUtc must match watermark");
				}
			}
			if (!frozenFrom.isBefore(frozenUntil)) {
				throw new IllegalArgumentException("Incremental range must be increasing");
			}
			execution.indexedFromUtc = frozenFrom;
			execution.indexedUntilUtc = frozenUntil;
			for (UtcWindowPlanner.Window window : UtcWindowPlanner.plan(frozenFrom, frozenUntil)) {
				entityManager.persist(new JobControlWindow(
						id, window.sequence(), window.fromInclusive(), window.untilExclusive(), started
				));
			}
		} else if (mode == RunMode.REPLAY_ERRORS) {
			execution.businessStatus = "PREPARING";
			execution.sourceExecutionId = UUID.fromString(required(job, "sourceExecutionId"));
		} else {
			throw new IllegalArgumentException("BENCHMARK uses dataPlaneBenchmarkJob");
		}
		putExecutionContext(job, execution);
	}

	private static boolean frozenParametersChanged(
			RunMode mode, JobExecution original, JobExecution current
	) {
		return switch (mode) {
			case BACKFILL -> !Objects.equals(
					original.getJobParameters().getLocalDate("createdFrom"),
					current.getJobParameters().getLocalDate("createdFrom")
			) || !Objects.equals(
					original.getJobParameters().getLocalDate("createdUntil"),
					current.getJobParameters().getLocalDate("createdUntil")
			) || !Objects.equals(
					original.getJobParameters().getLong("maxItems"),
					current.getJobParameters().getLong("maxItems")
			);
			case INCREMENTAL -> stringChanged(original, current, "sourceName")
					|| stringChanged(original, current, "bootstrapIndexedFrom")
					|| stringChanged(original, current, "indexedFromUtc")
					|| stringChanged(original, current, "indexedUntilUtc");
			case REPLAY_ERRORS -> stringChanged(original, current, "sourceExecutionId");
			case BENCHMARK -> true;
		};
	}

	private static boolean stringChanged(JobExecution original, JobExecution current, String name) {
		return !Objects.equals(
				original.getJobParameters().getString(name), current.getJobParameters().getString(name)
		);
	}

	private static void putExecutionContext(JobExecution job, JobControlExecution execution) {
		job.getExecutionContext().putString("syncExecutionId", execution.id.toString());
		if (execution.stagingUpperBound != null) {
			job.getExecutionContext().putLong("stagingUpperBound", execution.stagingUpperBound);
			job.getExecutionContext().putLong("expectedCount", execution.expectedCount);
		}
	}

	private static URI crossrefUri(String baseUri, JobControlExecution execution, JobControlWindow window) {
		if (execution.mode.equals(RunMode.BACKFILL.name())) {
			return URI.create(baseUri + "?filter=from-created-date:" + execution.createdFrom
					+ ",until-created-date:" + execution.createdUntil);
		}
		return URI.create(baseUri + "?filter=from-index-date:" + window.indexedFromUtc
				+ ",until-index-date:" + window.indexedUntilUtc);
	}

	private static UUID executionId(JobExecution job) {
		return UUID.fromString(job.getExecutionContext().getString("syncExecutionId"));
	}

	private static RunMode mode(JobExecution job) {
		return RunMode.valueOf(required(job, "mode"));
	}

	private static String required(JobExecution job, String name) {
		String value = job.getJobParameters().getString(name);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " is required");
		}
		return value;
	}

	record IncrementalRange(Instant from, Instant until) {
	}
}

@Entity(name = "JobControlExecution")
@Table(name = "sync_execution")
class JobControlExecution {

	@Id
	@Column(columnDefinition = "BINARY(16)")
	UUID id;
	@Column(name = "request_id", nullable = false)
	String requestId;
	@Column(nullable = false)
	String mode;
	@Column(name = "sync_contract_hash", nullable = false)
	String syncContractHash;
	@Column(name = "canonical_version", nullable = false)
	int canonicalVersion;
	@Column(name = "business_status", nullable = false)
	String businessStatus;
	@Column(name = "batch_job_execution_id")
	Long batchJobExecutionId;
	@Column(name = "source_execution_id", columnDefinition = "BINARY(16)")
	UUID sourceExecutionId;
	@Column(name = "created_from")
	LocalDate createdFrom;
	@Column(name = "created_until")
	LocalDate createdUntil;
	@Column(name = "indexed_from_utc")
	Instant indexedFromUtc;
	@Column(name = "indexed_until_utc")
	Instant indexedUntilUtc;
	@Column(name = "max_items")
	Long maxItems;
	@Column(name = "expected_count")
	Long expectedCount;
	@Column(name = "staging_upper_bound")
	Long stagingUpperBound;
	@Column(name = "started_at", nullable = false)
	Instant startedAt;
	@Column(name = "created_at", nullable = false)
	Instant createdAt;
	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;

	protected JobControlExecution() {
	}

	JobControlExecution(UUID id, String requestId, RunMode mode, String contract, long batchExecutionId, Instant now) {
		this.id = id;
		this.requestId = requestId;
		this.mode = mode.name();
		this.syncContractHash = contract;
		this.canonicalVersion = 1;
		this.businessStatus = "COLLECTING";
		this.batchJobExecutionId = batchExecutionId;
		this.startedAt = now;
		this.createdAt = now;
		this.updatedAt = now;
	}
}

@Entity(name = "JobControlWindow")
@Table(name = "sync_window")
class JobControlWindow {

	@Id
	@Column(columnDefinition = "BINARY(16)")
	UUID id;
	@Column(name = "execution_id", nullable = false, columnDefinition = "BINARY(16)")
	UUID executionId;
	@Column(name = "window_sequence", nullable = false)
	int windowSequence;
	@Column(name = "indexed_from_utc")
	Instant indexedFromUtc;
	@Column(name = "indexed_until_utc")
	Instant indexedUntilUtc;
	@Column(name = "collected_count", nullable = false)
	long collectedCount;
	@Column(nullable = false)
	String status;
	@Column(name = "created_at", nullable = false)
	Instant createdAt;
	@Column(name = "updated_at", nullable = false)
	Instant updatedAt;

	protected JobControlWindow() {
	}

	JobControlWindow(UUID executionId, int sequence, Instant from, Instant until, Instant now) {
		this.id = UUID.randomUUID();
		this.executionId = executionId;
		this.windowSequence = sequence;
		this.indexedFromUtc = from;
		this.indexedUntilUtc = until;
		this.status = "COLLECTING";
		this.createdAt = now;
		this.updatedAt = now;
	}
}

@Entity(name = "JobWatermark")
@Table(name = "sync_watermark")
class JobWatermark {
	@Id
	@Column(name = "source_name")
	String sourceName;
	@Column(name = "indexed_until_utc", nullable = false)
	Instant indexedUntilUtc;
	protected JobWatermark() {
	}
}
