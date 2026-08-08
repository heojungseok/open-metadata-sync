package com.heojungseok.openmetadatasync.config;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableBatchProcessing(transactionManagerRef = "transactionManager")
@EnableJdbcJobRepository(
		dataSourceRef = "dataSource",
		transactionManagerRef = "transactionManager"
)
public class BatchTransactionConfig {

	@Bean
	PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
		return new JpaTransactionManager(entityManagerFactory);
	}
}
