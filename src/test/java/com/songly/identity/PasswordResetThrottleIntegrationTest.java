package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(properties = {"songly.security.rate-limit.enabled=false",
		"songly.security.password-reset.resend-interval=PT10M"})
class PasswordResetThrottleIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	private void requestReset(String email) throws Exception {
		mockMvc.perform(post("/api/v1/auth/password/reset-request").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s"}""".formatted(email))).andExpect(status().isAccepted());
	}

	@Test
	void secondRequestWithinTheWindow_isSilentlyIgnored() throws Exception {
		registerAndVerify("reset-throttle@songly.io", "password123");
		mails.clear();

		requestReset("reset-throttle@songly.io");
		requestReset("reset-throttle@songly.io");
		requestReset("reset-throttle@songly.io");

		assertThat(mails.sent()).hasSize(1);
	}
}
