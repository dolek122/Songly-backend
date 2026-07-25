package com.songly;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} włącza harmonogram dla całej aplikacji — pierwszym
 * użytkownikiem jest serwerowy poll statusów "co teraz gra" (ADR 0007, Etap
 * 1b).
 */
@SpringBootApplication
@EnableScheduling
public class SonglyBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(SonglyBackendApplication.class, args);
	}

}
