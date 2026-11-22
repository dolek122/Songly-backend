package com.songly.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

class ConnectionPoolSizingTest {

	private static final Pattern PLACEHOLDER_DEFAULT = Pattern.compile("\\$\\{[^:}]+:([^}]+)}");

	@Test
	void poolIsLargerThanThreadsThatCanWaitForSecondConnection() throws IOException {
		Properties properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

		int requestThreads = number(properties, "server.tomcat.threads.max");
		int schedulerThreads = number(properties, "spring.task.scheduling.pool.size");
		int pool = number(properties, "spring.datasource.hikari.maximum-pool-size");

		assertThat(pool)
				.as("pula (%d) przy %d wątkach Tomcata i %d wątkach schedulera", pool, requestThreads, schedulerThreads)
				.isGreaterThan(requestThreads + schedulerThreads);
	}

	private static int number(Properties properties, String key) {
		String value = properties.getProperty(key);
		assertThat(value).as("%s musi być ustawione w application.properties", key).isNotNull();
		Matcher placeholder = PLACEHOLDER_DEFAULT.matcher(value.strip());
		return Integer.parseInt(placeholder.matches() ? placeholder.group(1) : value.strip());
	}
}
