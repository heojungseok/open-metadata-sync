package com.heojungseok.openmetadatasync;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = OpenMetadataSyncApplication.class)
class ApplicationContextTest {

	@Container
	@ServiceConnection
	static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10");

	@Autowired
	ApplicationContext context;

	@Test
	void usesOneJpaTransactionManagerAndAJdbcJobRepository() {
		Map<String, PlatformTransactionManager> transactionManagers =
				context.getBeansOfType(PlatformTransactionManager.class);

		assertThat(transactionManagers).hasSize(1);
		assertThat(transactionManagers.values().iterator().next())
				.isInstanceOf(JpaTransactionManager.class);
		assertThat(context.getBean(JobRepository.class))
				.isNotInstanceOf(ResourcelessJobRepository.class);
	}
}
