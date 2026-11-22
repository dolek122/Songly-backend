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
		"songly.security.registration.resend-interval=PT10M"})
class RegistrationThrottleIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void repeatedRegistrationsWithinTheWindow_sendOnlyOneMessage() throws Exception {
		String email = "registration-throttle@songly.io";
		mails.clearFor(email);

		for (int attempt = 0; attempt < 3; attempt++) {
			mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
					{"email":"%s","password":"password123"}""".formatted(email))).andExpect(status().isAccepted());
		}

		assertThat(mails.sentTo(email)).hasSize(1);
	}

	@Test
	void repeatedRegistrationsOnAnExistingAccount_sendOnlyOneWarning() throws Exception {
		String email = "registration-throttle-existing@songly.io";
		registerAndVerify(email, "password123");
		mails.clearFor(email);

		for (int attempt = 0; attempt < 3; attempt++) {
			mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
					{"email":"%s","password":"inne-haslo-123"}""".formatted(email))).andExpect(status().isAccepted());
		}

		assertThat(mails.sentTo(email)).hasSize(1);
	}
}
