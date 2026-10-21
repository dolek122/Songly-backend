package com.songly.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * Limit per użytkownik (ADR 0034) na małych liczbach: budżet zapisów 6, budżet
 * interakcji 2. Każdy test zakłada własne konta, więc liczniki w Redisie
 * (kluczowane identyfikatorem użytkownika) nie przeciekają między testami.
 */
@TestPropertySource(properties = {"songly.security.rate-limit.enabled=false",
		"songly.security.user-rate-limit.enabled=true", "songly.security.user-rate-limit.rules[0].name=writes",
		"songly.security.user-rate-limit.rules[0].paths[0]=/api/**", "songly.security.user-rate-limit.rules[0].limit=6",
		"songly.security.user-rate-limit.rules[0].window=PT1M",
		"songly.security.user-rate-limit.rules[1].name=interactions",
		"songly.security.user-rate-limit.rules[1].methods=PUT,POST",
		"songly.security.user-rate-limit.rules[1].paths[0]=/api/v1/posts/*/likes",
		"songly.security.user-rate-limit.rules[1].paths[1]=/api/v1/posts/*/comments",
		"songly.security.user-rate-limit.rules[1].limit=2", "songly.security.user-rate-limit.rules[1].window=PT10M"})
class UserRateLimitingIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String accessToken(String email) throws Exception {
		MvcResult result = registerAndVerify(email, "password123");
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
	}

	private String createPost(String token) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"post pod limit"}""")).andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private ResultActions like(String token, String postId) throws Exception {
		return mockMvc.perform(put("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + token));
	}

	private ResultActions comment(String token, String postId) throws Exception {
		return mockMvc.perform(post("/api/v1/posts/" + postId + "/comments").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"zaczepka"}"""));
	}

	/**
	 * Lajk i komentarz dzielą jeden budżet — inaczej rozłożenie zasypywania na dwa
	 * endpointy podwajałoby limit, a u ofiary kończy się to tak samo:
	 * powiadomieniem.
	 */
	@Test
	void interactionsShareOneBudget_acrossEndpoints() throws Exception {
		String author = accessToken("user-limit-author-1@songly.io");
		String spammer = accessToken("user-limit-spammer-1@songly.io");
		String postId = createPost(author);

		like(spammer, postId).andExpect(status().isNoContent());
		comment(spammer, postId).andExpect(status().isCreated());

		comment(spammer, postId).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
				.andExpect(header().exists(HttpHeaders.RETRY_AFTER));
	}

	/**
	 * Licznik należy do osoby, nie do posta ani do adresu IP (MockMvc ma jeden).
	 */
	@Test
	void limitIsPerUser() throws Exception {
		String author = accessToken("user-limit-author-2@songly.io");
		String spammer = accessToken("user-limit-spammer-2@songly.io");
		String bystander = accessToken("user-limit-bystander-2@songly.io");
		String postId = createPost(author);

		comment(spammer, postId);
		comment(spammer, postId);
		comment(spammer, postId).andExpect(status().isTooManyRequests());

		like(bystander, postId).andExpect(status().isNoContent());
	}

	/**
	 * Cofnięcie własnego śladu nie jest interakcją i nie może zależeć od limitu —
	 * ta sama zasada, z której cofnięcie lajka nie sprawdza blokady (§9). Odczyty
	 * nie są limitowane wcale.
	 */
	@Test
	void withdrawingAndReading_stillWork_afterInteractionBudgetIsSpent() throws Exception {
		String author = accessToken("user-limit-author-3@songly.io");
		String spammer = accessToken("user-limit-spammer-3@songly.io");
		String postId = createPost(author);

		like(spammer, postId).andExpect(status().isNoContent());
		comment(spammer, postId).andExpect(status().isCreated());
		comment(spammer, postId).andExpect(status().isTooManyRequests());

		mockMvc.perform(delete("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + spammer))
				.andExpect(status().isNoContent());
		for (int i = 0; i < 10; i++) {
			mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + spammer))
					.andExpect(status().isOk());
		}
	}

	/**
	 * Budżet zapisów obejmuje cały prefiks, więc endpoint, którego nikt nie wypisał
	 * w żadnej regule, i tak jest limitowany — nowe endpointy dostają ochronę
	 * domyślnie.
	 */
	@Test
	void writeBudget_coversEndpointsNotListedAnywhere() throws Exception {
		String user = accessToken("user-limit-writer-4@songly.io");

		// Budżet to 6, ale jeden zapis zużył już pomocnik rejestracji: nadaje kontu
		// nazwę wyświetlaną przez PUT /profiles/me (AbstractIntegrationTest).
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(post("/api/v1/notifications/read-all").header("Authorization", "Bearer " + user))
					.andExpect(status().isNoContent());
		}
		mockMvc.perform(post("/api/v1/notifications/read-all").header("Authorization", "Bearer " + user))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
	}
}
