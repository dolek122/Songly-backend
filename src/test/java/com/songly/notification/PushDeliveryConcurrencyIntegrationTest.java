package com.songly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Wolny serwer pushy nie może trzymać połączeń z bazą (ADR 0051). Push wychodzi
 * z listenera {@code AFTER_COMMIT}, zanim żądanie odda swoje połączenia, więc
 * przy wysyłce w wątku żądania każde polubienie trzymało dwa połączenia przez
 * cały czas rozmowy z FCM. Pula ma tu 4 połączenia, a czekanie na nie jest
 * skrócone do 1 s, żeby wyczerpanie puli kończyło się błędem, a nie tylko
 * wolnym testem.
 *
 * <p>
 * Polubień naraz jest mniej niż połączeń w puli, i to celowo: przy większej
 * liczbie żądania zakleszczają się na zapisie do skrzynki ({@code REQUIRES_NEW}
 * z {@code AFTER_COMMIT}) bez względu na pushe. Przed tym chroni rozmiar puli
 * względem liczby wątków, nie ta klasa ({@code ConnectionPoolSizingTest}).
 */
@TestPropertySource(properties = {"songly.notification.push.async-delivery=true",
		"spring.datasource.hikari.connection-timeout=1000", "songly.security.rate-limit.enabled=false"})
class PushDeliveryConcurrencyIntegrationTest extends AbstractIntegrationTest {

	private static final int LIKERS = 3;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@AfterEach
	void restoreFastPushes() {
		pushes.delayEachSendBy(Duration.ZERO);
	}

	@Test
	void slowPushServer_doesNotExhaustConnectionPool() throws Exception {
		String author = token("push-author");
		String phone = "phone-" + UUID.randomUUID();
		mockMvc.perform(put("/api/v1/notifications/push-devices").header("Authorization", "Bearer " + author)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"token":"%s","platform":"ANDROID"}""".formatted(phone))).andExpect(status().isNoContent());
		String postId = objectMapper.readTree(mockMvc
				.perform(post("/api/v1/posts").header("Authorization", "Bearer " + author)
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"post pod wolne pushe"}"""))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
		List<String> likers = new ArrayList<>();
		for (int i = 0; i < LIKERS; i++) {
			likers.add(token("push-liker"));
		}

		pushes.delayEachSendBy(Duration.ofMillis(1500));
		List<Integer> statuses = likeInParallel(postId, likers);

		assertThat(statuses).as("statusy polubień").containsOnly(204);
		// Pushe wychodzą w tle, ale wychodzą wszystkie.
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (pushes.sentTo(phone).size() < LIKERS && System.nanoTime() < deadline) {
			Thread.sleep(100);
		}
		assertThat(pushes.sentTo(phone)).hasSize(LIKERS);
	}

	private List<Integer> likeInParallel(String postId, List<String> likers) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(likers.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> results = new ArrayList<>();
			for (String liker : likers) {
				results.add(pool.submit(() -> {
					start.await();
					return mockMvc.perform(
							put("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + liker))
							.andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
			return statuses;
		} finally {
			pool.shutdownNow();
		}
	}

	private String token(String prefix) throws Exception {
		return objectMapper.readTree(registerAndVerify(prefix + "-" + UUID.randomUUID() + "@push.test", "password123")
				.getResponse().getContentAsString()).get("accessToken").asText();
	}
}
