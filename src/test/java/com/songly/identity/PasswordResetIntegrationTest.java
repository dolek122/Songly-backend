package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import com.songly.identity.RecordingMailSenderConfig.RecordingMailSender;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Reset hasła — jedyna droga powrotu do konta dla kogoś, kto hasła nie pamięta.
 *
 * <p>
 * Test jest integracyjny, bo sedno tej funkcji leży poza serwisem: token
 * istnieje w postaci jawnej wyłącznie w wysłanej wiadomości (w bazie jest tylko
 * skrót), a skutek resetu — unieważnienie wszystkich sesji — robi masowy UPDATE
 * omijający kontekst persystencji. Ani jednego, ani drugiego nie da się
 * uczciwie sprawdzić bez prawdziwej bazy i pełnej ścieżki HTTP.
 *
 * <p>
 * {@code resend-interval=PT0S} zdejmuje odstęp między wysyłkami, żeby kolejne
 * przypadki mogły swobodnie zamawiać linki. Sam odstęp ma własną klasę testową
 * ({@code PasswordResetThrottleIntegrationTest}) — inaczej wyciszałby wysyłki w
 * testach, które badają coś zupełnie innego.
 */
@Import(RecordingMailSenderConfig.class)
@TestPropertySource(properties = {"songly.security.rate-limit.enabled=false",
		"songly.security.password-reset.resend-interval=PT0S"})
class PasswordResetIntegrationTest extends AbstractIntegrationTest {

	private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private RecordingMailSender mails;

	@Autowired
	private PasswordResetTokenRepository tokenRepository;

	@BeforeEach
	void clearMailbox() {
		mails.clear();
	}

	private record Tokens(String accessToken, String refreshToken) {
	}

	private Tokens register(String email, String password) throws Exception {
		MvcResult result = mockMvc
				.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"%s","password":"%s"}""".formatted(email, password))).andExpect(status().isCreated())
				.andReturn();
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

	private void requestReset(String email) throws Exception {
		mockMvc.perform(post("/api/v1/auth/password/reset-request").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s"}""".formatted(email))).andExpect(status().isAccepted());
	}

	/** Token istnieje w postaci jawnej tylko tutaj — w bazie leży sam skrót. */
	private String tokenFromMailTo(String email) {
		Matcher matcher = TOKEN_IN_LINK.matcher(mails.lastTo(email).body());
		assertThat(matcher.find()).as("Wiadomość powinna zawierać link z tokenem").isTrue();
		return matcher.group(1);
	}

	private void resetPassword(String token, String newPassword, int expectedStatus) throws Exception {
		mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content("""
				{"token":"%s","newPassword":"%s"}""".formatted(token, newPassword)))
				.andExpect(status().is(expectedStatus));
	}

	private String userIdOf(String accessToken) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
	}

	private void expectRefreshRejected(String refreshToken) throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"%s"}""".formatted(refreshToken))).andExpect(status().isUnauthorized());
	}

	/**
	 * Cała ścieżka, którą przechodzi użytkownik: zamawiam link, dostaję maila,
	 * ustawiam nowe hasło i wracam do konta. Bez tego przebiegu reszta asercji nie
	 * ma znaczenia.
	 */
	@Test
	void fullFlow_letsUserBackInWithTheNewPassword() throws Exception {
		register("reset-happy@songly.io", "password123");

		requestReset("reset-happy@songly.io");
		resetPassword(tokenFromMailTo("reset-happy@songly.io"), "brand-new-password", 204);

		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"reset-happy@songly.io","password":"password123"}""")).andExpect(status().isUnauthorized());
		login("reset-happy@songly.io", "brand-new-password");
	}

	/**
	 * Reset robi się po utracie kontroli nad kontem, więc musi mieć ten sam skutek
	 * co zmiana hasła: sesje ze wszystkich urządzeń giną. Zostawienie w mocy sesji
	 * sprzed resetu przekreślałoby całą operację — złodziej dalej miałby ważny
	 * refresh token.
	 */
	@Test
	void reset_killsSessionsFromEveryDevice() throws Exception {
		Tokens phone = register("reset-sessions@songly.io", "password123");
		Tokens laptop = login("reset-sessions@songly.io", "password123");

		requestReset("reset-sessions@songly.io");
		resetPassword(tokenFromMailTo("reset-sessions@songly.io"), "brand-new-password", 204);

		expectRefreshRejected(phone.refreshToken());
		expectRefreshRejected(laptop.refreshToken());
	}

	/**
	 * Nieznany adres wygląda dokładnie tak samo jak znany — inaczej endpoint byłby
	 * darmową listą kont (Claude.md §13). Sprawdzamy też, że nic nie poszło pocztą:
	 * sama odpowiedź 202 nie wyklucza wysyłki, a wysłanie czegokolwiek na
	 * niezarejestrowany adres byłoby i tak niepożądane.
	 */
	@Test
	void unknownEmail_looksIdenticalAndSendsNothing() throws Exception {
		requestReset("nie-ma-takiego-konta@songly.io");

		assertThat(mails.sent()).isEmpty();
	}

	/**
	 * Token jest jednorazowy. Gdyby nie był, link zalegający w skrzynce (albo w
	 * historii przeglądarki) otwierałby konto w kółko przez cały swój czas życia.
	 */
	@Test
	void token_worksOnlyOnce() throws Exception {
		register("reset-once@songly.io", "password123");
		requestReset("reset-once@songly.io");
		String token = tokenFromMailTo("reset-once@songly.io");

		resetPassword(token, "brand-new-password", 204);

		mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content("""
				{"token":"%s","newPassword":"jeszcze-inne-haslo"}""".formatted(token)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PASSWORD_RESET_TOKEN"));
		login("reset-once@songly.io", "brand-new-password");
	}

	/**
	 * Wygasły token dostaje ten sam kod błędu co token zmyślony. Osobne
	 * {@code TOKEN_EXPIRED} byłoby wygodniejsze dla klienta, ale potwierdzałoby, że
	 * taki token kiedyś istniał — czyli że pod danym adresem jest konto.
	 *
	 * <p>
	 * Token wstawiamy wprost przez repozytorium, bo inaczej test musiałby czekać
	 * pół godziny albo podnosić osobny kontekst z {@code ttl} bliskim zeru.
	 */
	@Test
	void expiredToken_isRejectedTheSameWayAsAFabricatedOne() throws Exception {
		Tokens tokens = register("reset-expired@songly.io", "password123");
		UUID userId = UUID.fromString(userIdOf(tokens.accessToken()));
		String rawToken = SecureTokens.generate();
		Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
		tokenRepository
				.save(new PasswordResetToken(UUID.randomUUID(), userId, SecureTokens.hash(rawToken), past, past));

		mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content("""
				{"token":"%s","newPassword":"brand-new-password"}""".formatted(rawToken)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PASSWORD_RESET_TOKEN"));

		mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content("""
				{"token":"token-ktorego-nigdy-nie-bylo","newPassword":"brand-new-password"}"""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PASSWORD_RESET_TOKEN"));

		login("reset-expired@songly.io", "password123");
	}

	/**
	 * Kolejne „nie dostałem maila" nie może zostawiać w obiegu następnego ważnego
	 * klucza do konta — użytkownik nie ma żadnego sposobu, żeby te starsze linki
	 * unieważnić.
	 */
	@Test
	void newRequest_invalidatesThePreviousLink() throws Exception {
		register("reset-relink@songly.io", "password123");

		requestReset("reset-relink@songly.io");
		String firstToken = tokenFromMailTo("reset-relink@songly.io");
		requestReset("reset-relink@songly.io");
		String secondToken = tokenFromMailTo("reset-relink@songly.io");

		assertThat(secondToken).isNotEqualTo(firstToken);
		resetPassword(firstToken, "brand-new-password", 400);
		resetPassword(secondToken, "brand-new-password", 204);
	}

	/**
	 * Zmiana hasła zwykłą drogą unieważnia oczekujący link resetujący. Taki link
	 * najczęściej zamówił ktoś obcy — zostawienie go żywego jeszcze przez pół
	 * godziny znaczyłoby, że reakcja właściciela niczego nie zamknęła.
	 */
	@Test
	void changingPassword_invalidatesAPendingResetLink() throws Exception {
		Tokens tokens = register("reset-vs-change@songly.io", "password123");
		requestReset("reset-vs-change@songly.io");
		String pendingToken = tokenFromMailTo("reset-vs-change@songly.io");

		mockMvc.perform(put("/api/v1/auth/password").header("Authorization", "Bearer " + tokens.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"currentPassword":"password123","newPassword":"wybrane-swiadomie"}"""))
				.andExpect(status().isNoContent());

		resetPassword(pendingToken, "haslo-napastnika", 400);
		login("reset-vs-change@songly.io", "wybrane-swiadomie");
	}

	/**
	 * Reset nie może być obejściem polityki haseł obowiązującej przy rejestracji i
	 * zmianie hasła.
	 */
	@Test
	void shortNewPassword_isRejectedByValidation() throws Exception {
		register("reset-short@songly.io", "password123");
		requestReset("reset-short@songly.io");

		resetPassword(tokenFromMailTo("reset-short@songly.io"), "krotkie", 400);
		login("reset-short@songly.io", "password123");
	}

	/**
	 * Oba endpointy muszą działać bez tokenu — cała ścieżka istnieje właśnie dla
	 * kogoś, kto nie jest w stanie się zalogować. Wpuszczenie ich do
	 * {@code SecurityConfig} łatwo przeoczyć, a objawem byłoby 401 zamiast resetu.
	 */
	@Test
	void bothEndpointsAreReachableWithoutAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/auth/password/reset-request").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"kto-kolwiek@songly.io"}""")).andExpect(status().isAccepted());
		mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content("""
				{"token":"cokolwiek","newPassword":"brand-new-password"}""")).andExpect(status().isBadRequest());
	}
}
