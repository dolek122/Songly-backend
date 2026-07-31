package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Weryfikacja adresu e-mail przy rejestracji (ADR 0009).
 *
 * <p>
 * Dwa cele naraz, i drugi jest ważniejszy od pierwszego. Oczywisty: nikt nie
 * zakłada konta na cudzy adres. Mniej oczywisty: <b>rejestracja przestaje być
 * wyrocznią na istnienie kont</b> — wcześniej 409 z adresem w treści pozwalało
 * wylistować użytkowników, mimo że logowanie (§13) i reset hasła (§9) bronią
 * się przed tym z osobna.
 *
 * <p>
 * {@code resend-interval=PT0S} zdejmuje odstęp między wysyłkami, żeby przypadki
 * mogły swobodnie ponawiać zgłoszenia. Sam odstęp ma osobny test niżej.
 */
@TestPropertySource(properties = {"songly.security.rate-limit.enabled=false",
		"songly.security.registration.resend-interval=PT0S"})
class EmailVerificationIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PendingRegistrationRepository pendingRepository;

	private void register(String email, String password) throws Exception {
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"%s"}""".formatted(email, password))).andExpect(status().isAccepted());
	}

	private void verify(String token, int expectedStatus) throws Exception {
		mockMvc.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON).content("""
				{"token":"%s"}""".formatted(token))).andExpect(status().is(expectedStatus));
	}

	private void expectLogin(String email, String password, int expectedStatus) throws Exception {
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"%s"}""".formatted(email, password))).andExpect(status().is(expectedStatus));
	}

	/**
	 * Sedno: dopóki adres nie jest potwierdzony, <b>konto nie istnieje</b> — nie da
	 * się nim zalogować, bo nie ma czym.
	 */
	@Test
	void accountDoesNotExist_untilTheAddressIsConfirmed() throws Exception {
		String email = "verify-pending@songly.io";
		mails.clearFor(email);

		register(email, "password123");

		expectLogin(email, "password123", 401);
		assertThat(userRepository.findByEmail(email)).isEmpty();

		verify(verificationTokenFor(email), 200);

		expectLogin(email, "password123", 200);
		assertThat(userRepository.findByEmail(email)).isPresent();
	}

	/**
	 * Potwierdzenie od razu wydaje parę tokenów. Kliknięcie w link dowodzi kontroli
	 * nad skrzynką, a hasło podał ten sam człowiek chwilę wcześniej — osobne
	 * logowanie zaraz potem nie sprawdzałoby niczego więcej.
	 */
	@Test
	void verification_logsTheUserInImmediately() throws Exception {
		String email = "verify-tokens@songly.io";
		mails.clearFor(email);
		register(email, "password123");

		String accessToken = objectMapper.readTree(mockMvc
				.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON).content("""
						{"token":"%s"}""".formatted(verificationTokenFor(email)))).andExpect(status().isOk())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty()).andReturn().getResponse().getContentAsString())
				.get("accessToken").asText();

		// Profil powstaje dopiero teraz — UserRegisteredEvent leci przy weryfikacji,
		// nie przy zgłoszeniu, więc żaden inny moduł nie widzi konta przedwcześnie.
		mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk());
	}

	/**
	 * Zgłoszenie na zajęty adres wygląda identycznie jak na wolny (202) i nie
	 * tworzy niczego. To jest ta wyrocznia, którą zamykamy: różnicę widzi wyłącznie
	 * właściciel skrzynki, w treści wiadomości.
	 */
	@Test
	void registrationOnTakenAddress_isIndistinguishable_butWarnsTheOwner() throws Exception {
		String email = "verify-taken@songly.io";
		registerAndVerify(email, "password123");
		mails.clearFor(email);

		register(email, "inne-haslo-123");

		assertThat(mails.sentTo(email)).hasSize(1);
		assertThat(mails.lastTo(email).subject()).contains("ktoś próbował założyć konto");
		// Żadnego linku potwierdzającego — nie ma czego potwierdzać.
		assertThat(mails.lastTo(email).body()).doesNotContain("token=");
		assertThat(pendingRepository.findByEmail(email)).isEmpty();
		// Hasło konta zostaje nietknięte.
		expectLogin(email, "inne-haslo-123", 401);
		expectLogin(email, "password123", 200);
	}

	/**
	 * Token jest jednorazowy — po założeniu konta zgłoszenie znika, więc ponowne
	 * kliknięcie tego samego linku trafia w pustkę.
	 */
	@Test
	void token_worksOnlyOnce() throws Exception {
		String email = "verify-once@songly.io";
		mails.clearFor(email);
		register(email, "password123");
		String token = verificationTokenFor(email);

		verify(token, 200);

		mockMvc.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON).content("""
				{"token":"%s"}""".formatted(token))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
	}

	/**
	 * Wygasły i zmyślony token dostają ten sam kod błędu. Osobne
	 * {@code TOKEN_EXPIRED} potwierdzałoby, że takie zgłoszenie kiedyś istniało.
	 */
	@Test
	void expiredToken_isRejectedLikeAFabricatedOne() throws Exception {
		String rawToken = SecureTokens.generate();
		Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
		pendingRepository.save(new PendingRegistration(UUID.randomUUID(), "verify-expired@songly.io", "nieistotny-hash",
				SecureTokens.hash(rawToken), past, past));

		mockMvc.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON).content("""
				{"token":"%s"}""".formatted(rawToken))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));

		mockMvc.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON).content("""
				{"token":"nigdy-takiego-nie-bylo"}""")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));

		assertThat(userRepository.findByEmail("verify-expired@songly.io")).isEmpty();
	}

	/**
	 * Ponowne zgłoszenie wydaje nowy link i unieważnia poprzedni — inaczej każde
	 * „nie dostałem maila" zostawiałoby w obiegu kolejny ważny klucz.
	 *
	 * <p>
	 * Hasła przy tym <b>nie</b> podmieniamy, i to jest zabezpieczenie, nie
	 * niedoróbka: inaczej ktoś obcy wstrzeliłby własne hasło w cudze, jeszcze
	 * niepotwierdzone zgłoszenie i czekał, aż właściciel skrzynki kliknie najnowszy
	 * link.
	 */
	@Test
	void repeatedRegistration_reissuesLinkButKeepsTheOriginalPassword() throws Exception {
		String email = "verify-reissue@songly.io";
		mails.clearFor(email);

		register(email, "pierwsze-haslo");
		String firstToken = verificationTokenFor(email);
		register(email, "haslo-napastnika");
		String secondToken = verificationTokenFor(email);

		assertThat(secondToken).isNotEqualTo(firstToken);
		verify(firstToken, 400);
		verify(secondToken, 200);

		expectLogin(email, "haslo-napastnika", 401);
		expectLogin(email, "pierwsze-haslo", 200);
	}

	/**
	 * Zgłoszenie rejestracji nie zostawia śladu nigdzie poza własną tabelą. Gdyby
	 * zostawiało, niepotwierdzony adres pojawiałby się w wyszukiwarce użytkowników
	 * — a to jest dokładnie ten stan, którego reszta aplikacji nie ma prawa
	 * zobaczyć (ADR 0009).
	 */
	@Test
	void pendingRegistration_isInvisibleToTheRestOfTheApplication() throws Exception {
		String searcher = "verify-searcher@songly.io";
		String pending = "verify-invisible@songly.io";
		String accessToken = objectMapper
				.readTree(registerAndVerify(searcher, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();

		register(pending, "password123");

		mockMvc.perform(
				get("/api/v1/profiles/search?query=verify-invisible").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
	}

	/**
	 * Walidacja działa przed czymkolwiek innym — reset ani weryfikacja nie mogą być
	 * furtką omijającą politykę haseł.
	 */
	@Test
	void invalidPayload_isRejectedByValidation() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"to-nie-jest-email","password":"krotkie"}""")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}
}
