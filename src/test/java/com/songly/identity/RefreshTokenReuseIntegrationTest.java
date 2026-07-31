package com.songly.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Wykrywanie ponownego użycia refresh tokenu.
 *
 * <p>
 * Test <b>musi</b> być integracyjny, bo sprawdza semantykę transakcji, a nie
 * przepływ sterowania. Rewokacja wszystkich sesji dzieje się tuż przed
 * rzuceniem wyjątku — bez {@code noRollbackFor} Spring wycofałby ją razem z
 * transakcją i wykrycie kradzieży nie zostawiłoby żadnego skutku. Test na
 * mockach pokazałby wtedy zielone światło, bo widziałby samo wywołanie metody
 * repozytorium; jedyną rzeczą, która odróżnia obie wersje, jest to, czy
 * <b>token wydany po rotacji naprawdę przestaje działać</b>.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class RefreshTokenReuseIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String register(String email) throws Exception {
		MvcResult result = registerAndVerify(email, "password123");
		return refreshTokenOf(result);
	}

	private String refreshTokenOf(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("refreshToken").asText();
	}

	private MvcResult refresh(String refreshToken, int expectedStatus) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"%s"}""".formatted(refreshToken))).andExpect(status().is(expectedStatus)).andReturn();
	}

	/**
	 * Sedno całej zmiany. Bez {@code noRollbackFor} ten test pada na ostatniej
	 * asercji: odtworzenie zużytego tokenu zwróci 401, ale token wydany po rotacji
	 * dalej będzie działał, bo rewokacja zostanie wycofana razem z transakcją.
	 */
	@Test
	void replayingAConsumedToken_killsTheSessionItWasRotatedInto() throws Exception {
		String stolen = register("reuse-core@songly.io");
		String rotated = refreshTokenOf(refresh(stolen, 200));

		// Token po rotacji żyje — dopóki nikt nie odtworzy poprzedniego.
		refresh(stolen, 401);

		refresh(rotated, 401);
	}

	/**
	 * Odpowiedź na wykryte nadużycie jest nieodróżnialna od zwykłego złego tokenu —
	 * inaczej atakujący dowiadywałby się, że jego kopia została wykryta.
	 */
	@Test
	void reuseResponse_looksExactlyLikeAnyOtherBadToken() throws Exception {
		String stolen = register("reuse-opaque@songly.io");
		refresh(stolen, 200);

		MvcResult reuse = refresh(stolen, 401);
		MvcResult garbage = refresh("zupelnie-nieistniejacy-token", 401);

		var reuseBody = objectMapper.readTree(reuse.getResponse().getContentAsString());
		var garbageBody = objectMapper.readTree(garbage.getResponse().getContentAsString());
		Assertions.assertThat(reuseBody.get("code").asText()).isEqualTo(garbageBody.get("code").asText());
		Assertions.assertThat(reuseBody.get("message").asText()).isEqualTo(garbageBody.get("message").asText());
	}

	/**
	 * Wszystkie sesje, nie tylko łańcuch, w którym doszło do nadużycia: nie wiemy,
	 * co jeszcze wyciekło razem z tokenem, więc drugie urządzenie też musi zejść.
	 */
	@Test
	void reuseRevokesSessionsFromEveryDevice() throws Exception {
		String phone = register("reuse-devices@songly.io");
		MvcResult loginResult = mockMvc
				.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"reuse-devices@songly.io","password":"password123"}""")).andExpect(status().isOk())
				.andReturn();
		String laptop = refreshTokenOf(loginResult);

		refresh(phone, 200);
		refresh(phone, 401);

		refresh(laptop, 401);
	}

	/**
	 * Ścieżka wyjścia: po wykryciu nadużycia użytkownik odzyskuje konto hasłem,
	 * którego złodziej nie ma. Bez tego „rewokacja wszystkiego" byłaby ślepą
	 * uliczką.
	 */
	@Test
	void afterReuse_userRecoversByLoggingInAgain() throws Exception {
		String stolen = register("reuse-recovery@songly.io");
		refresh(stolen, 200);
		refresh(stolen, 401);

		MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"reuse-recovery@songly.io","password":"password123"}""")).andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty()).andReturn();

		refresh(refreshTokenOf(login), 200);
	}

	/**
	 * Wylogowanie odwołuje sesję normalnie, ale to nie jest nadużycie. Gdyby
	 * ponowne podanie wylogowanego tokenu liczyło się jako kradzież, każdy klient
	 * ponawiający logout po zerwaniu połączenia kasowałby użytkownikowi sesje na
	 * pozostałych urządzeniach.
	 */
	@Test
	void loggingOutTwice_doesNotTakeDownOtherDevices() throws Exception {
		String phone = register("reuse-logout@songly.io");
		MvcResult loginResult = mockMvc
				.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"reuse-logout@songly.io","password":"password123"}""")).andExpect(status().isOk())
				.andReturn();
		String laptop = refreshTokenOf(loginResult);

		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"%s"}""".formatted(phone))).andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"%s"}""".formatted(phone))).andExpect(status().isNoContent());

		refresh(laptop, 200);
	}
}
