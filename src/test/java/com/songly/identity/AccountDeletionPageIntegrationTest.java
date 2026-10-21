package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Usuwanie konta przez stronę WWW, bez aplikacji (ADR 0043) — ścieżka, której
 * Google Play wymaga od każdej aplikacji z kontami.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class AccountDeletionPageIntegrationTest extends AbstractIntegrationTest {

	private static final Pattern CONFIRM_LINK = Pattern.compile("/account-deletion/confirm\\?token=([A-Za-z0-9_-]+)");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String requestLinkAndReadToken(String email) throws Exception {
		mails.clearFor(email);
		mockMvc.perform(
				post("/account-deletion").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("email", email))
				.andExpect(status().isOk());
		Matcher matcher = CONFIRM_LINK.matcher(mails.lastTo(email).body());
		assertThat(matcher.find()).as("Wiadomość powinna zawierać link potwierdzenia").isTrue();
		return matcher.group(1);
	}

	private void expectLogin(String email, int expectedStatus) throws Exception {
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"password123"}""".formatted(email))).andExpect(status().is(expectedStatus));
	}

	/**
	 * Strona jest publiczna i nie da się jej osadzić w cudzej ramce ani zmusić do
	 * wysłania formularza gdzie indziej.
	 */
	@Test
	void startPage_isPublicHtmlWithRestrictiveHeaders() throws Exception {
		mockMvc.perform(get("/account-deletion")).andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
				.andExpect(content().string(containsString("Usuwanie konta Songly")))
				.andExpect(content().string(containsString("Co zostaje")))
				.andExpect(header().string("Content-Security-Policy", containsString("form-action 'self'")))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("Referrer-Policy", "no-referrer"));
	}

	@Test
	void linkFromMail_deletesAccountOnlyAfterPressingTheButton() throws Exception {
		String email = "web-deletion-flow@songly.io";
		registerAndVerify(email, "password123");

		String token = requestLinkAndReadToken(email);

		// Otwarcie linku (tak robią też skanery poczty) pokazuje przycisk, nic nie
		// usuwa.
		mockMvc.perform(get("/account-deletion/confirm").param("token", token)).andExpect(status().isOk())
				.andExpect(content().string(containsString("Usuń konto na stałe")));
		expectLogin(email, 200);

		mockMvc.perform(post("/account-deletion/confirm").contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("token", token)).andExpect(status().isOk())
				.andExpect(content().string(containsString("Konto usunięte")));

		expectLogin(email, 401);
		// Link jest jednorazowy — konto i jego tokeny zniknęły razem.
		mockMvc.perform(get("/account-deletion/confirm").param("token", token)).andExpect(status().isNotFound());
	}

	/** Ta sama odpowiedź dla adresu z kontem i bez — strona nie jest listą kont. */
	@Test
	void unknownAddress_getsTheSamePageAndNoMail() throws Exception {
		String existing = "web-deletion-existing@songly.io";
		String unknown = "web-deletion-nobody@songly.io";
		registerAndVerify(existing, "password123");
		mails.clearFor(existing);
		mails.clearFor(unknown);

		String forExisting = mockMvc.perform(
				post("/account-deletion").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("email", existing))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String forUnknown = mockMvc.perform(
				post("/account-deletion").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("email", unknown))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat(forUnknown).isEqualTo(forExisting);
		assertThat(mails.sentTo(unknown)).isEmpty();
		assertThat(mails.sentTo(existing)).hasSize(1);
	}

	@Test
	void invalidAddress_showsTheFormAgain() throws Exception {
		mockMvc.perform(post("/account-deletion").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("email",
				"to-nie-adres")).andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("Podaj poprawny adres e-mail")));
	}

	@Test
	void unknownToken_isRejectedWithoutDeletingAnything() throws Exception {
		mockMvc.perform(post("/account-deletion/confirm").contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("token", "zmyslony-token")).andExpect(status().isNotFound())
				.andExpect(content().string(containsString("Ten link jest nieważny")));
	}

	/**
	 * Niewykorzystany link nie może blokować usunięcia z aplikacji — tokeny mają
	 * klucz obcy do {@code users}, więc kasowanie musi je zabrać.
	 */
	@Test
	void pendingLink_doesNotBlockDeletionFromTheApp() throws Exception {
		String email = "web-deletion-app@songly.io";
		String accessToken = objectMapper
				.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString()).get("accessToken")
				.asText();
		requestLinkAndReadToken(email);

		mockMvc.perform(delete("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"password":"password123"}""")).andExpect(status().isNoContent());

		expectLogin(email, 401);
	}
}
