package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Limit prób logowania na adres (ADR 0051). Limit per IP jest tu wyłączony, bo
 * ten test udaje atak rozproszony, przed którym tamten nie chroni.
 */
@TestPropertySource(properties = {"songly.auth.login.account-limit=3", "songly.security.rate-limit.enabled=false"})
class LoginAttemptLimitIntegrationTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "password123";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void attemptsBeyondLimit_areRejectedEvenWithCorrectPassword() throws Exception {
		String email = address("victim");
		registerAndVerify(email, PASSWORD);
		for (int i = 0; i < 3; i++) {
			login(email, "zle-haslo").andExpect(status().isUnauthorized());
		}

		// Poprawne hasło też nie przechodzi: inaczej limit byłby wyrocznią na
		// trafienie, a atak szedłby dalej po cichu.
		login(email, PASSWORD).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMIT_EXCEEDED"))
				.andExpect(header().exists(HttpHeaders.RETRY_AFTER));
	}

	/** Odmowa nie może mówić, czy pod adresem jest konto. */
	@Test
	void addressWithoutAccount_isLimitedExactlyLikeExistingOne() throws Exception {
		String existing = address("existing");
		registerAndVerify(existing, PASSWORD);
		String missing = address("missing");

		assertThat(statusesOfFourFailedLogins(missing)).isEqualTo(statusesOfFourFailedLogins(existing))
				.containsExactly(401, 401, 401, 429);
	}

	@Test
	void successfulLogin_resetsCounter() throws Exception {
		String email = address("forgetful");
		registerAndVerify(email, PASSWORD);
		login(email, "zle-haslo").andExpect(status().isUnauthorized());
		login(email, "zle-haslo").andExpect(status().isUnauthorized());
		login(email, PASSWORD).andExpect(status().isOk());

		for (int i = 0; i < 3; i++) {
			login(email, "zle-haslo").andExpect(status().isUnauthorized());
		}
	}

	@Test
	void limitIsPerAddress() throws Exception {
		String attacked = address("attacked");
		String bystander = address("bystander");
		registerAndVerify(bystander, PASSWORD);
		statusesOfFourFailedLogins(attacked);

		login(bystander, PASSWORD).andExpect(status().isOk());
	}

	private List<Integer> statusesOfFourFailedLogins(String email) throws Exception {
		List<Integer> statuses = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			statuses.add(login(email, "zle-haslo").andReturn().getResponse().getStatus());
		}
		return statuses;
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"%s"}""".formatted(email, password)));
	}

	private static String address(String prefix) {
		return prefix + "-" + UUID.randomUUID() + "@login-limit.test";
	}
}
