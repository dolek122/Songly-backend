package com.songly;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Kontenery uruchamiane raz na cały przebieg testów (singleton container
 * pattern) — celowo bez {@code @Testcontainers}/{@code @Container}, bo ten cykl
 * życia zatrzymywałby je po pierwszej klasie testowej, która z nich skorzysta,
 * psując kontekst Springa buforowany dla kolejnych klas. Zamiast tego start
 * ręczny raz w bloku statycznym; sprzątanie robi Ryuk przy zakończeniu JVM.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@ServiceConnection
	static final GenericContainer redis = new GenericContainer("redis:7-alpine").withExposedPorts(6379);

	static {
		postgres.start();
		redis.start();
	}
}
