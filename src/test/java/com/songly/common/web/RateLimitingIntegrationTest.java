package com.songly.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

@TestPropertySource(properties = {"songly.security.rate-limit.enabled=true", "songly.security.rate-limit.limit=2",
		"songly.security.rate-limit.window=PT1M"})
class RateLimitingIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void register_returnsTooManyRequests_afterLimitExceeded() throws Exception {
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
					{"email":"rate-limit-%d@songly.io","password":"password123"}""".formatted(i)))
					.andExpect(status().isAccepted());
		}

		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"rate-limit-3@songly.io","password":"password123"}""")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
				.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
				.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8"))
				.andExpect(jsonPath("$.message").value("Zbyt wiele żądań, spróbuj ponownie później"));
	}

	@Test
	void refresh_isRateLimitedToo() throws Exception {
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
					{"refreshToken":"nie-istnieje"}""")).andExpect(status().isUnauthorized());
		}

		mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"nie-istnieje"}""")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
	}

	@Test
	void limitOnOnePath_doesNotBlockAnother() throws Exception {
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
					{"email":"nie-ma@songly.io","password":"password123"}"""));
		}

		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"nie-istnieje"}""")).andExpect(status().isNoContent());
	}
}
