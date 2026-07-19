package com.songly.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
					.andExpect(status().isCreated());
		}

		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"rate-limit-3@songly.io","password":"password123"}""")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
	}
}
