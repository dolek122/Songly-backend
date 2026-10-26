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
 * Odstęp między wysyłkami na ten sam adres przy rejestracji.
 *
 * <p>
 * Bez niego endpoint byłby generatorem poczty na dowolny cudzy adres — i to
 * gorszym niż przy resecie hasła, bo tam wysyłka wymaga istniejącego konta, a
 * tutaj wystarczy dowolny adres. Rate limiting per IP tego nie łapie: ofiara
 * jest jedna, a adresów źródłowych dowolnie wiele. Dlatego test <b>celowo
 * trzyma rate limiting wyłączony</b> — inaczej nie byłoby wiadomo, który
 * mechanizm zatrzymał drugą wysyłkę.
 */
@TestPropertySource(properties = {"songly.security.rate-limit.enabled=false",
		"songly.security.registration.resend-interval=PT10M"})
class RegistrationThrottleIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	/**
	 * Kolejne zgłoszenia w oknie odstępu kończą się <b>tą samą odpowiedzią</b>
	 * (202), ale bez wysyłki. Odróżnialne 429 mówiłoby pytającemu, że pod tym
	 * adresem właśnie zamówiono rejestrację.
	 */
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

	/**
	 * Ten sam odstęp chroni właściciela istniejącego konta. Wcześniej każde
	 * zgłoszenie na jego adres wysyłało mu kolejne ostrzeżenie — zasypywanie cudzej
	 * skrzynki ograniczał tylko limit per IP.
	 */
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
