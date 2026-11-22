package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = {"songly.security.rate-limit.enabled=false",
		"songly.security.password-reset.resend-interval=PT0S"})
class PasswordResetIntegrationTest extends AbstractIntegrationTest {

	private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PasswordResetTokenRepository tokenRepository;

	@BeforeEach
	void clearMailbox() {
		mails.clear();
	}

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

	private void requestReset(String email) throws Exception {
		mockMvc.perform(post("/api/v1/auth/password/reset-request").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s"}""".formatted(email))).andExpect(status().isAccepted());
	}

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

	@Test
	void fullFlow_letsUserBackInWithTheNewPassword() throws Exception {
		register("reset-happy@songly.io", "password123");

		requestReset("reset-happy@songly.io");
		resetPassword(tokenFromMailTo("reset-happy@songly.io"), "brand-new-password", 204);

		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"reset-happy@songly.io","password":"password123"}""")).andExpect(status().isUnauthorized());
		login("reset-happy@songly.io", "brand-new-password");
	}

	@Test
	void reset_killsSessionsFromEveryDevice() throws Exception {
		Tokens phone = register("reset-sessions@songly.io", "password123");
		Tokens laptop = login("reset-sessions@songly.io", "password123");

		requestReset("reset-sessions@songly.io");
		resetPassword(tokenFromMailTo("reset-sessions@songly.io"), "brand-new-password", 204);

		expectRefreshRejected(phone.refreshToken());
		expectRefreshRejected(laptop.refreshToken());
	}

	@Test
	void unknownEmail_looksIdenticalAndSendsNothing() throws Exception {
		requestReset("nie-ma-takiego-konta@songly.io");

		assertThat(mails.sent()).isEmpty();
	}

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

	@Test
	void shortNewPassword_isRejectedByValidation() throws Exception {
		register("reset-short@songly.io", "password123");
		requestReset("reset-short@songly.io");

		resetPassword(tokenFromMailTo("reset-short@songly.io"), "krotkie", 400);
		login("reset-short@songly.io", "password123");
	}

	@Test
	void bothEndpointsAreReachableWithoutAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/auth/password/reset-request").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"kto-kolwiek@songly.io"}""")).andExpect(status().isAccepted());
		mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content("""
				{"token":"cokolwiek","newPassword":"brand-new-password"}""")).andExpect(status().isBadRequest());
	}
}
