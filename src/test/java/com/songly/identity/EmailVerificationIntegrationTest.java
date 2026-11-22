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

		mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk());
	}

	@Test
	void registrationOnTakenAddress_isIndistinguishable_butWarnsTheOwner() throws Exception {
		String email = "verify-taken@songly.io";
		registerAndVerify(email, "password123");
		mails.clearFor(email);

		register(email, "inne-haslo-123");

		assertThat(mails.sentTo(email)).hasSize(1);
		assertThat(mails.lastTo(email).subject()).contains("ktoś próbował założyć konto");
		assertThat(mails.lastTo(email).body()).doesNotContain("token=");
		assertThat(pendingRepository.findByEmail(email)).isEmpty();
		expectLogin(email, "inne-haslo-123", 401);
		expectLogin(email, "password123", 200);
	}

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

	@Test
	void invalidPayload_isRejectedByValidation() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"to-nie-jest-email","password":"krotkie"}""")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}
}
