package com.heojungseok.openmetadatasync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class OpenMetadataSyncApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(OpenMetadataSyncApplication.class, args);
		System.exit(SpringApplication.exit(context));
	}
}
