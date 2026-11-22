package com.songly.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class PasswordChangeIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private record Tokens(String accessToken, String refreshToken) {
	}

	private Tokens register(String email, String password) throws Exception {
		MvcResult result = registerAndVerify(email, password);
		return toTokens(result);
	}

	private Tokens login(String email, String password) throws Exception {
		MvcResult result = mockMvc
				.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"%s","password":"%s"}""".formatted(email, password))).andExpect(status().isOk())
				.andReturn();
		return toTokens(result);
	}

	private Tokens toTokens(MvcResult result) throws Exception {
		var json = objectMapper.readTree(result.getResponse().getContentAsString());
		return new Tokens(json.get("accessToken").asText(), json.get("refreshToken").asText());
	}

	private void changePassword(String accessToken, String current, String replacement, int expectedStatus)
			throws Exception {
		mockMvc.perform(put("/api/v1/auth/password").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"currentPassword":"%s","newPassword":"%s"}""".formatted(current, replacement)))
				.andExpect(status().is(expectedStatus));
	}

	private void expectRefreshRejected(String refreshToken) throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"%s"}""".formatted(refreshToken))).andExpect(status().isUnauthorized());
	}

	@Test
	void changingPassword_killsSessionsFromEveryDevice() throws Exception {
		Tokens phone = register("pwd-change@songly.io", "password123");
		Tokens laptop = login("pwd-change@songly.io", "password123");

		changePassword(phone.accessToken(), "password123", "brand-new-password", 204);

		expectRefreshRejected(phone.refreshToken());
		expectRefreshRejected(laptop.refreshToken());
	}

	@Test
	void afterChange_onlyTheNewPasswordLogsIn() throws Exception {
		Tokens tokens = register("pwd-newonly@songly.io", "password123");

		changePassword(tokens.accessToken(), "password123", "brand-new-password", 204);

		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"pwd-newonly@songly.io","password":"password123"}""")).andExpect(status().isUnauthorized());
		login("pwd-newonly@songly.io", "brand-new-password");
	}

	@Test
	void wrongCurrentPassword_changesNothingAndLeavesSessionsAlive() throws Exception {
		Tokens tokens = register("pwd-wrong@songly.io", "password123");

		changePassword(tokens.accessToken(), "not-my-password", "brand-new-password", 401);

		mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"%s"}""".formatted(tokens.refreshToken()))).andExpect(status().isOk());
		login("pwd-wrong@songly.io", "password123");
	}

	@Test
	void changingToTheSamePassword_isRejectedAndKeepsSessions() throws Exception {
		Tokens tokens = register("pwd-same@songly.io", "password123");

		mockMvc.perform(put("/api/v1/auth/password").header("Authorization", "Bearer " + tokens.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"currentPassword":"password123","newPassword":"password123"}"""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NEW_PASSWORD_SAME_AS_CURRENT"));

		mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"%s"}""".formatted(tokens.refreshToken()))).andExpect(status().isOk());
	}

	@Test
	void shortNewPassword_isRejectedByValidation() throws Exception {
		Tokens tokens = register("pwd-short@songly.io", "password123");

		changePassword(tokens.accessToken(), "password123", "krotkie", 400);
	}

	@Test
	void endpointRequiresAuthentication() throws Exception {
		mockMvc.perform(put("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON).content("""
				{"currentPassword":"password123","newPassword":"brand-new-password"}"""))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void accessTokenStaysValidUntilExpiry_byDesign() throws Exception {
		Tokens tokens = register("pwd-access@songly.io", "password123");

		changePassword(tokens.accessToken(), "password123", "brand-new-password", 204);

		mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + tokens.accessToken()))
				.andExpect(status().isOk());
	}
}
