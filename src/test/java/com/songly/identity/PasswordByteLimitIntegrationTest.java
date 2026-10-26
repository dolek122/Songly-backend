package com.songly.identity;

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
import tools.jackson.databind.ObjectMapper;

/**
 * Hasło mieszczące się w 72 znakach, ale nie w 72 bajtach (bcrypt), dostaje 400
 * zamiast 500. 40 razy „ą” to 40 znaków i 80 bajtów UTF-8 — do tej pory
 * przechodziło przez {@code @Size} i wywracało się dopiero w bcrypcie.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class PasswordByteLimitIntegrationTest extends AbstractIntegrationTest {

	private static final String TOO_MANY_BYTES = "ą".repeat(40);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void registration_rejectsPasswordOverBcryptByteLimit() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"password-bytes-register@songly.io","password":"%s"}""".formatted(TOO_MANY_BYTES)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void passwordReset_rejectsNewPasswordOverBcryptByteLimit() throws Exception {
		mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content("""
				{"token":"dowolny","newPassword":"%s"}""".formatted(TOO_MANY_BYTES))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void passwordChange_rejectsNewPasswordOverBcryptByteLimit() throws Exception {
		String accessToken = objectMapper.readTree(
				registerAndVerify("password-bytes-change@songly.io", "password123").getResponse().getContentAsString())
				.get("accessToken").asText();

		mockMvc.perform(put("/api/v1/auth/password").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"currentPassword":"password123","newPassword":"%s"}""".formatted(TOO_MANY_BYTES)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}
}
