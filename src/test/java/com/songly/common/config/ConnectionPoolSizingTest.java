package com.songly.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Pula połączeń musi być większa niż liczba wątków, które mogą trzymać jedno
 * połączenie i czekać na drugie (ADR 0051). Robią tak listenery
 * {@code AFTER_COMMIT} z {@code REQUIRES_NEW}: żądanie oddaje swoje połączenie
 * dopiero po nich. Gdy wszystkie połączenia trzymają takie wątki, API stoi do
 * {@code connection-timeout} i odpowiada 500 — przy domyślnych 200 wątkach
 * Tomcata i 10 połączeniach wystarczało 10 równoczesnych polubień.
 */
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

	/**
	 * Wartość musi stać w pliku jawnie: domyślne Springa (200 wątków, 10 połączeń)
	 * łamią ten warunek, więc brak wpisu też jest błędem.
	 */
	private static int number(Properties properties, String key) {
		String value = properties.getProperty(key);
		assertThat(value).as("%s musi być ustawione w application.properties", key).isNotNull();
		Matcher placeholder = PLACEHOLDER_DEFAULT.matcher(value.strip());
		return Integer.parseInt(placeholder.matches() ? placeholder.group(1) : value.strip());
	}
}
