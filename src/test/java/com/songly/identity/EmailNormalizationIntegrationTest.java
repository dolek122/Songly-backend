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

/**
 * Adres e-mail to jedno konto niezależnie od wielkości liter i spacji na
 * brzegach (ADR 0042). Klawiatura w telefonie zaczyna adres wielką literą sama
 * z siebie, a autouzupełnianie dopisuje spację — bez normalizacji kończyło się
 * to drugim kontem albo „błędnym hasłem" przy pierwszym logowaniu.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class EmailNormalizationIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void accountRegisteredWithCapitals_logsInWithAnyCase() throws Exception {
		String normalized = "jan.normalizacja@songly.io";
		mails.clearFor(normalized);

		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"  Jan.Normalizacja@Songly.IO ","password":"password123"}"""))
				.andExpect(status().isAccepted());
		mockMvc.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON).content("""
				{"token":"%s"}""".formatted(verificationTokenFor(normalized)))).andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"password123"}""".formatted(normalized))).andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"JAN.NORMALIZACJA@SONGLY.IO","password":"password123"}""")).andExpect(status().isOk());
	}

	/**
	 * Inna wielkość liter to ten sam adres, więc druga rejestracja idzie gałęzią
	 * „konto już istnieje" (ADR 0009), a nie zakłada bliźniaczego konta.
	 */
	@Test
	void registrationWithDifferentCase_hitsExistingAccount() throws Exception {
		String normalized = "duplikat.liter@songly.io";
		registerAndVerify(normalized, "password123");
		mails.clearFor(normalized);

		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"Duplikat.Liter@songly.io","password":"inne-haslo-123"}""")).andExpect(status().isAccepted());

		assertThat(mails.lastTo(normalized).subject()).contains("ktoś próbował założyć konto");
	}

	@Test
	void passwordResetWithDifferentCase_reachesAccount() throws Exception {
		String normalized = "reset.liter@songly.io";
		registerAndVerify(normalized, "password123");
		mails.clearFor(normalized);

		mockMvc.perform(post("/api/v1/auth/password/reset-request").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"Reset.Liter@Songly.io"}""")).andExpect(status().isAccepted());

		assertThat(mails.lastTo(normalized).subject()).contains("reset hasła");
	}
}
