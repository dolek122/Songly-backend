package com.songly.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void register_thenLogin_thenRefresh_thenLogout_happyPath() throws Exception {
		String email = "flow-user@songly.io";

		MvcResult registerResult = registerAndVerify(email, "password123");

		String firstRefreshToken = objectMapper.readTree(registerResult.getResponse().getContentAsString())
				.get("refreshToken").asText();

		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"password123"}""".formatted(email))).andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());

		MvcResult refreshResult = mockMvc
				.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
						{"refreshToken":"%s"}""".formatted(firstRefreshToken))).andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty()).andReturn();

		String rotatedRefreshToken = objectMapper.readTree(refreshResult.getResponse().getContentAsString())
				.get("refreshToken").asText();

		mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"%s"}""".formatted(firstRefreshToken))).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"%s"}""".formatted(rotatedRefreshToken))).andExpect(status().isNoContent());

		mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"%s"}""".formatted(rotatedRefreshToken))).andExpect(status().isUnauthorized());
	}

	@Test
	void register_looksIdentical_whenEmailAlreadyUsed() throws Exception {
		String email = "duplicate@songly.io";
		registerAndVerify(email, "password123");

		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"inne-haslo-123"}""".formatted(email))).andExpect(status().isAccepted());

		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"inne-haslo-123"}""".formatted(email))).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"password123"}""".formatted(email))).andExpect(status().isOk());
	}

	@Test
	void login_returnsUnauthorized_whenPasswordWrong() throws Exception {
		String email = "wrong-password@songly.io";
		registerAndVerify(email, "password123");

		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"wrong-password"}""".formatted(email))).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}
}
