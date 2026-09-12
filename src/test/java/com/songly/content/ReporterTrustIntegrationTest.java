package com.songly.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Waga zgłoszeń zależna od historii zgłaszającego (ADR 0025).
 *
 * <p>
 * ADR 0018 nazwał to jedynym prawdziwym lekarstwem na zasypywanie kolejki i
 * odłożył „do czasu, aż będą dane o tym, ile zgłoszeń bywa trafnych". Limit
 * 20/h dla każdego traktował tak samo człowieka, któremu odrzucono dwadzieścia
 * zgłoszeń, jak kogoś, kto zgłasza pierwszy raz.
 *
 * <p>
 * Limit ustawiony tu nisko (4, kara 2, podłoga 1), żeby testy nie musiały
 * wykonywać dwudziestu żądań na przypadek.
 */
@TestPropertySource(properties = {"songly.security.rate-limit.enabled=false", "songly.content.report.limit=4",
		"songly.content.report.dismissal-penalty=2", "songly.content.report.min-limit=1",
		"songly.content.report.history-window=PT1H"})
class ReporterTrustIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	private record TestUser(String token, String userId) {
	}

	private TestUser user(String email) throws Exception {
		String token = objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
		String userId = objectMapper
				.readTree(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("userId").asText();
		return new TestUser(token, userId);
	}

	private String createCommunity(TestUser owner, String name) throws Exception {
		return objectMapper
				.readTree(mockMvc
						.perform(post("/api/v1/communities").header("Authorization", "Bearer " + owner.token())
								.contentType(MediaType.APPLICATION_JSON).content("""
										{"name":"%s","description":"opis"}""".formatted(name)))
						.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
				.get("id").asText();
	}

	private void join(TestUser user, String communityId) throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + user.token())).andExpect(status().isNoContent());
	}

	private String createPost(TestUser author, String communityId, String content) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(post("/api/v1/communities/" + communityId + "/posts")
						.header("Authorization", "Bearer " + author.token()).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"content":"%s"}""".formatted(content)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
	}

	private void report(TestUser reporter, String postId) throws Exception {
		mockMvc.perform(
				post("/api/v1/posts/" + postId + "/reports").header("Authorization", "Bearer " + reporter.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"reason":"SPAM"}"""))
				.andExpect(status().isAccepted());
	}

	private JsonNode queue(TestUser moderator, String communityId) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/post-reports").header("Authorization",
						"Bearer " + moderator.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
	}

	/**
	 * Przesuwa okno: zgłoszenia tej osoby stają się starsze niż okno limitu, więc
	 * przestają zajmować przydział, ale ich <b>status</b> zostaje.
	 *
	 * <p>
	 * Bez tego nie da się odróżnić dwóch różnych powodów odmowy — „zgłosiłeś już
	 * tyle w tej godzinie" od „twój przydział jest mniejszy, bo masz odrzucenia".
	 * Odrzucone zgłoszenie liczy się do okna jak każde inne (ADR 0025), więc
	 * podłoga przydziału ujawnia się dopiero po przesunięciu okna.
	 */
	private void slideWindow(TestUser reporter) {
		jdbcTemplate.update("UPDATE post_reports SET created_at = now() - interval '2 hours' WHERE reporter_id = ?",
				java.util.UUID.fromString(reporter.userId()));
	}

	/**
	 * Starzeje rozstrzygnięcia tej osoby poza okno historii (ADR 0030) — przy
	 * {@code history-window=PT1H} dwie godziny wystarczą.
	 *
	 * <p>
	 * To jedyny sposób, żeby przetestować wygaszanie kary bez czekania: daty
	 * zapadły razem z decyzjami, więc nie ma ich jak podać z zewnątrz. Ta sama
	 * sztuczka co w {@link #slideWindow}, tylko na innej tabeli i w innym celu —
	 * tam chodzi o okno natężenia, tu o okno pamięci.
	 */
	private void ageDecisions(TestUser reporter) {
		jdbcTemplate.update("UPDATE reporter_decisions SET decided_at = now() - interval '2 hours' WHERE user_id = ?",
				java.util.UUID.fromString(reporter.userId()));
	}

	/** Odrzuca wszystkie oczekujące zgłoszenia w kolejce tej społeczności. */
	private void dismissAll(TestUser moderator, String communityId) throws Exception {
		List<String> reportIds = new ArrayList<>();
		for (JsonNode entry : queue(moderator, communityId)) {
			reportIds.add(entry.get("id").asText());
		}
		for (String reportId : reportIds) {
			mockMvc.perform(post("/api/v1/communities/" + communityId + "/post-reports/" + reportId + "/dismissal")
					.header("Authorization", "Bearer " + moderator.token())).andExpect(status().isNoContent());
		}
	}

	/**
	 * Sedno ADR 0025: odrzucenia zjadają przydział. Przy limicie 4 i karze 2 dwa
	 * odrzucenia zostawiają jedno zgłoszenie na okno — przydział zwykłego
	 * użytkownika zostaje przy tym nietknięty.
	 */
	@Test
	void dismissalsShrinkTheReportingAllowance() throws Exception {
		TestUser owner = user("trust-shrink-owner@songly.io");
		TestUser author = user("trust-shrink-author@songly.io");
		TestUser troll = user("trust-shrink-troll@songly.io");
		TestUser honest = user("trust-shrink-honest@songly.io");
		String communityId = createCommunity(owner, "Trust-przydzial");
		join(author, communityId);
		List<String> posts = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			posts.add(createPost(author, communityId, "post " + i));
		}

		// Dwa zgłoszenia, oba odrzucone — kara 2 × 2 = 4, czyli przydział spada z 4 do
		// podłogi. Okno przesuwamy, żeby odmowa wynikała z przydziału, a nie z tego,
		// że te dwa zgłoszenia same w nim siedzą.
		report(troll, posts.get(0));
		report(troll, posts.get(1));
		dismissAll(owner, communityId);
		slideWindow(troll);

		// Jedno jeszcze przechodzi (podłoga), drugie już nie.
		report(troll, posts.get(2));
		mockMvc.perform(post("/api/v1/posts/" + posts.get(3) + "/reports")
				.header("Authorization", "Bearer " + troll.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"SPAM"}""")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("REPORT_RATE_LIMIT_EXCEEDED"));

		// Kto nie ma odrzuceń, ma pełny przydział: cztery zgłoszenia przechodzą.
		for (int i = 0; i < 4; i++) {
			report(honest, posts.get(i));
		}
	}

	/**
	 * Podłoga nigdy nie schodzi do zera — inaczej kilka odrzuceń odbierałoby
	 * narzędzie, którym da się zgłosić nadużycie samego moderatora.
	 */
	@Test
	void reportingIsNeverFullySilenced() throws Exception {
		TestUser owner = user("trust-floor-owner@songly.io");
		TestUser author = user("trust-floor-author@songly.io");
		TestUser troll = user("trust-floor-troll@songly.io");
		String communityId = createCommunity(owner, "Trust-podloga");
		join(author, communityId);
		List<String> posts = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			posts.add(createPost(author, communityId, "post " + i));
		}

		// Cztery odrzucenia to kara 8 przy limicie 4 — przydział wyszedłby ujemny.
		for (int i = 0; i < 4; i++) {
			report(troll, posts.get(i));
			dismissAll(owner, communityId);
			slideWindow(troll);
		}

		// Podłoga działa: w każdym kolejnym oknie jedno zgłoszenie przechodzi dalej.
		report(troll, posts.get(4));
	}

	/**
	 * Kolejka pokazuje moderatorowi historię zgłaszającego — wagę do oceny, nie
	 * odjętą za niego.
	 */
	@Test
	void queueShowsTheReportersDismissalCount() throws Exception {
		TestUser owner = user("trust-queue-owner@songly.io");
		TestUser author = user("trust-queue-author@songly.io");
		TestUser troll = user("trust-queue-troll@songly.io");
		String communityId = createCommunity(owner, "Trust-kolejka");
		join(author, communityId);
		String first = createPost(author, communityId, "post pierwszy");
		String second = createPost(author, communityId, "post drugi");
		report(troll, first);
		dismissAll(owner, communityId);

		report(troll, second);

		JsonNode entry = queue(owner, communityId).get(0);
		assertThat(entry.get("reporterId").asText()).isEqualTo(troll.userId());
		assertThat(entry.get("reporterDismissedCount").asInt()).isEqualTo(1);
	}

	/**
	 * Najciekawszy przypadek: zasięgi są <b>różne celowo</b>. Odrzucenie w jednej
	 * społeczności zjada przydział globalnie (inaczej zasypywanie rozłożone na
	 * dziesięć społeczności wymykałoby się limitowi), ale moderator drugiej
	 * społeczności tego nie widzi — moderacja jest społecznościowa, nie globalna
	 * (§8).
	 */
	@Test
	void dismissalsCountGloballyForTheLimitButLocallyForTheQueue() throws Exception {
		TestUser firstOwner = user("trust-scope-owner1@songly.io");
		TestUser secondOwner = user("trust-scope-owner2@songly.io");
		TestUser author = user("trust-scope-author@songly.io");
		TestUser troll = user("trust-scope-troll@songly.io");
		String firstCommunity = createCommunity(firstOwner, "Trust-zasieg-pierwsza");
		String secondCommunity = createCommunity(secondOwner, "Trust-zasieg-druga");
		join(author, firstCommunity);
		join(author, secondCommunity);
		String postInFirst = createPost(author, firstCommunity, "post w pierwszej");
		List<String> postsInSecond = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			postsInSecond.add(createPost(author, secondCommunity, "post w drugiej " + i));
		}

		// Dwa odrzucenia w PIERWSZEJ społeczności.
		report(troll, postInFirst);
		dismissAll(firstOwner, firstCommunity);
		String anotherInFirst = createPost(author, firstCommunity, "drugi post w pierwszej");
		report(troll, anotherInFirst);
		dismissAll(firstOwner, firstCommunity);
		slideWindow(troll);

		// Przydział jest zjedzony także w DRUGIEJ społeczności: jedno przechodzi
		// (podłoga), następne już nie.
		report(troll, postsInSecond.get(0));
		mockMvc.perform(post("/api/v1/posts/" + postsInSecond.get(1) + "/reports")
				.header("Authorization", "Bearer " + troll.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"SPAM"}""")).andExpect(status().isTooManyRequests());

		// ...ale moderator drugiej społeczności nie widzi tamtych odrzuceń.
		JsonNode entry = queue(secondOwner, secondCommunity).get(0);
		assertThat(entry.get("reporterId").asText()).isEqualTo(troll.userId());
		assertThat(entry.get("reporterDismissedCount").asInt()).isZero();
	}

	/**
	 * Sedno ADR 0030: kara wygasa. Odrzucenia starsze niż okno historii przestają
	 * zjadać przydział, więc pomyłka nie zostaje z człowiekiem na zawsze — a nikt
	 * nie musi o tym pamiętać, bo nic się nie budzi w chwili wygaśnięcia (§8).
	 */
	@Test
	void dismissalsStopCountingOnceTheyLeaveTheHistoryWindow() throws Exception {
		TestUser owner = user("trust-decay-owner@songly.io");
		TestUser author = user("trust-decay-author@songly.io");
		TestUser troll = user("trust-decay-troll@songly.io");
		String communityId = createCommunity(owner, "Trust-wygasanie");
		join(author, communityId);
		List<String> posts = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			posts.add(createPost(author, communityId, "post " + i));
		}

		// Dwa odrzucenia zjadają przydział do podłogi — stan sprzed ADR 0030.
		report(troll, posts.get(0));
		report(troll, posts.get(1));
		dismissAll(owner, communityId);
		slideWindow(troll);
		report(troll, posts.get(2));
		mockMvc.perform(post("/api/v1/posts/" + posts.get(3) + "/reports")
				.header("Authorization", "Bearer " + troll.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"SPAM"}""")).andExpect(status().isTooManyRequests());

		// Te same odrzucenia, tylko starsze niż okno historii: przydział wraca do
		// bazowego. Okno limitu przesuwamy osobno — inaczej odmowę dawałyby zgłoszenia
		// siedzące w tej godzinie, a nie kara, i test nie dowodziłby niczego.
		ageDecisions(troll);
		slideWindow(troll);
		for (int i = 3; i < 7; i++) {
			report(troll, posts.get(i));
		}

		// Przydział wrócił do bazowego, a nie „do nieskończoności": piąte w tym oknie
		// już nie przechodzi.
		mockMvc.perform(post("/api/v1/posts/" + posts.get(7) + "/reports")
				.header("Authorization", "Bearer " + troll.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"SPAM"}""")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("REPORT_RATE_LIMIT_EXCEEDED"));
	}

	/**
	 * Kolejka liczy w tym samym oknie co przydział (ADR 0030). Inaczej moderator
	 * czytałby „3 odrzucenia" bez wiedzy, czy były w tym tygodniu, czy dwa lata
	 * temu — a liczba bez skali czasu nie jest wagą, którą da się rozważyć.
	 */
	@Test
	void queueForgetsDismissalsOlderThanTheWindow() throws Exception {
		TestUser owner = user("trust-decayqueue-owner@songly.io");
		TestUser author = user("trust-decayqueue-author@songly.io");
		TestUser troll = user("trust-decayqueue-troll@songly.io");
		String communityId = createCommunity(owner, "Trust-kolejka-wygasanie");
		join(author, communityId);
		String first = createPost(author, communityId, "post pierwszy");
		String second = createPost(author, communityId, "post drugi");
		report(troll, first);
		dismissAll(owner, communityId);
		report(troll, second);

		assertThat(queue(owner, communityId).get(0).get("reporterDismissedCount").asInt()).isEqualTo(1);

		ageDecisions(troll);

		JsonNode entry = queue(owner, communityId).get(0);
		// Samo zgłoszenie zostaje w kolejce — starzeje się historia zgłaszającego, nie
		// sprawa do rozpatrzenia.
		assertThat(entry.get("reporterId").asText()).isEqualTo(troll.userId());
		assertThat(entry.get("reporterDismissedCount").asInt()).isZero();
	}
}
