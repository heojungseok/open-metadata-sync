package com.heojungseok.openmetadatasync.batch.launch;

import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.batch.autoconfigure.BatchProperties;
import org.springframework.boot.batch.autoconfigure.JobLauncherApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
class BatchLaunchConfiguration {

	@Bean
	@ConditionalOnBooleanProperty(name = "spring.batch.job.enabled")
	JobLauncherApplicationRunner jobLauncherApplicationRunner(
			JobOperator jobOperator,
			JobRepository jobRepository,
			BatchProperties properties,
			BatchProcessOutcome outcome
	) {
		AlreadyCompletedAwareJobLauncherApplicationRunner runner =
				new AlreadyCompletedAwareJobLauncherApplicationRunner(jobOperator, jobRepository, outcome);
		String jobName = properties.getJob().getName();
		if (StringUtils.hasText(jobName)) {
			runner.setJobName(jobName);
		}
		return runner;
	}
}
