package com.songly.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Access token usuniętego konta przestaje działać od razu (ADR 0052), a nie po
 * wygaśnięciu. Filtr i tak czyta konto z bazy przy każdym żądaniu (ADR 0006), a
 * brak wiersza traktował jak zwykłego użytkownika: feed, wyszukiwarka, ręczny
 * status i strumień SSE działały jeszcze do 15 minut po usunięciu.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class DeletedAccountTokenIntegrationTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "password123";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void tokenOfDeletedAccount_isRejectedEverywhere() throws Exception {
		String token = objectMapper
				.readTree(registerAndVerify("leaving-" + UUID.randomUUID() + "@deleted.test", PASSWORD).getResponse()
						.getContentAsString())
				.get("accessToken").asText();
		mockMvc.perform(get("/api/v1/feed").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

		mockMvc.perform(delete("/api/v1/auth/me").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"password":"%s"}""".formatted(PASSWORD))).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/feed").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(get("/api/v1/profiles/search?query=a").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(put("/api/v1/now-playing/manual").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"title":"Utwór"}""")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/now-playing/stream").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}
}
