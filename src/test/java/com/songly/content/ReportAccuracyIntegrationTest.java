package com.songly.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pełna trafność zgłoszeń (ADR 0027).
 *
 * <p>
 * ADR 0019 zapisał, że zgłoszenie zamknięte usunięciem posta nie zostawia
 * śladu, a ADR 0025 — że sygnał jest jednostronny: liczyliśmy odrzucenia, nie
 * trafienia. Skutek był przewrotny: historia zgłoszenia ginęła dokładnie wtedy,
 * gdy zgłoszenie okazywało się <b>trafne</b>, bo kasowanie posta zabiera
 * kaskadą jego zgłoszenia.
 *
 * <p>
 * Limit ustawiony nisko (4, kara 2, podłoga 1), żeby nie robić dwudziestu żądań
 * na przypadek.
 */
@TestPropertySource(properties = {"songly.security.rate-limit.enabled=false", "songly.content.report.limit=4",
		"songly.content.report.dismissal-penalty=2", "songly.content.report.min-limit=1"})
class ReportAccuracyIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

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

	private TestUser admin(String email) throws Exception {
		TestUser user = user(email);
		jdbcTemplate.update("UPDATE users SET is_admin = TRUE WHERE id = ?", UUID.fromString(user.userId()));
		return user;
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

	private void removePost(TestUser moderator, String postId) throws Exception {
		mockMvc.perform(delete("/api/v1/posts/" + postId).header("Authorization", "Bearer " + moderator.token()))
				.andExpect(status().isNoContent());
	}

	private JsonNode queue(TestUser moderator, String communityId) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/post-reports").header("Authorization",
						"Bearer " + moderator.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
	}

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

	private void slideWindow(TestUser reporter) {
		jdbcTemplate.update("UPDATE post_reports SET created_at = now() - interval '2 hours' WHERE reporter_id = ?",
				UUID.fromString(reporter.userId()));
	}

	private List<String> platformLogActions(TestUser adminUser, TestUser subject) throws Exception {
		JsonNode content = objectMapper.readTree(mockMvc
				.perform(get("/api/v1/admin/moderation-log?userId=" + subject.userId()).header("Authorization",
						"Bearer " + adminUser.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
		List<String> actions = new ArrayList<>();
		for (JsonNode entry : content) {
			actions.add(entry.get("action").asText());
		}
		return actions;
	}

	/**
	 * Sedno ADR 0027: usunięcie posta zapisuje trafienie <b>każdemu</b>
	 * zgłaszającemu — i w kolejce, i w historii moderacji. Przed tą zmianą ślad
	 * ginął razem z postem.
	 */
	@Test
	void removingAPostRecordsAnUpheldReportForEveryReporter() throws Exception {
		TestUser adminUser = admin("accuracy-upheld-admin@songly.io");
		TestUser owner = user("accuracy-upheld-owner@songly.io");
		TestUser author = user("accuracy-upheld-author@songly.io");
		TestUser firstReporter = user("accuracy-upheld-rep1@songly.io");
		TestUser secondReporter = user("accuracy-upheld-rep2@songly.io");
		String communityId = createCommunity(owner, "Accuracy-trafienia");
		join(author, communityId);
		String spam = createPost(author, communityId, "spam do usuniecia");
		report(firstReporter, spam);
		report(secondReporter, spam);

		removePost(owner, spam);

		// Historia moderacji zapisuje trafienie obu zgłaszającym...
		assertThat(platformLogActions(adminUser, firstReporter)).contains("POST_REPORT_UPHELD");
		assertThat(platformLogActions(adminUser, secondReporter)).contains("POST_REPORT_UPHELD");
		// ...a autorowi — usunięcie posta.
		assertThat(platformLogActions(adminUser, author)).contains("COMMUNITY_POST_REMOVED");

		// Kolejka pokazuje moderatorowi trafność przy następnym zgłoszeniu.
		String another = createPost(author, communityId, "kolejny post");
		report(firstReporter, another);
		JsonNode entry = queue(owner, communityId).get(0);
		assertThat(entry.get("reporterUpheldCount").asInt()).isEqualTo(1);
		assertThat(entry.get("reporterDismissedCount").asInt()).isZero();
	}

	/**
	 * Druga połowa ADR 0027: trafienia wybaczają odrzucenia jeden za jeden. Ktoś z
	 * jednym trafnym i dwoma odrzuconymi zgłoszeniami ma przydział jak po jednym
	 * odrzuceniu, nie jak po dwóch.
	 */
	@Test
	void upheldReportsOffsetDismissals() throws Exception {
		TestUser owner = user("accuracy-offset-owner@songly.io");
		TestUser author = user("accuracy-offset-author@songly.io");
		TestUser reporter = user("accuracy-offset-reporter@songly.io");
		String communityId = createCommunity(owner, "Accuracy-kompensacja");
		join(author, communityId);
		List<String> posts = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			posts.add(createPost(author, communityId, "post " + i));
		}

		// Dwa odrzucenia: przydział spadłby z 4 do podłogi (kara 2 × 2). Okno
		// przesuwamy po każdej partii, bo zgłoszenia zajmują je niezależnie od swojego
		// losu (ADR 0025) — inaczej badalibyśmy limit okna, nie przydział.
		report(reporter, posts.get(0));
		report(reporter, posts.get(1));
		dismissAll(owner, communityId);
		slideWindow(reporter);
		// ...ale jedno trafne zgłoszenie odejmuje jedno odrzucenie.
		report(reporter, posts.get(2));
		removePost(owner, posts.get(2));
		slideWindow(reporter);

		// Netto jedno odrzucenie → przydział 4 - 2 = 2, więc dwa zgłoszenia przechodzą,
		// trzecie już nie.
		report(reporter, posts.get(3));
		report(reporter, posts.get(4));
		mockMvc.perform(
				post("/api/v1/posts/" + posts.get(5) + "/reports").header("Authorization", "Bearer " + reporter.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"reason":"SPAM"}"""))
				.andExpect(status().isTooManyRequests());
	}

	/**
	 * Trafienia nie podnoszą przydziału <b>powyżej</b> bazowego — inaczej ktoś, kto
	 * raz zgłaszał dobrze, dostawałby szerszy kanał do zasypywania.
	 */
	@Test
	void upheldReportsNeverRaiseTheAllowanceAboveBase() throws Exception {
		TestUser owner = user("accuracy-cap-owner@songly.io");
		TestUser author = user("accuracy-cap-author@songly.io");
		TestUser reporter = user("accuracy-cap-reporter@songly.io");
		String communityId = createCommunity(owner, "Accuracy-sufit");
		join(author, communityId);
		List<String> posts = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			posts.add(createPost(author, communityId, "post " + i));
		}

		// Trzy trafne zgłoszenia, zero odrzuceń.
		for (int i = 0; i < 3; i++) {
			report(reporter, posts.get(i));
			removePost(owner, posts.get(i));
		}
		slideWindow(reporter);

		// Przydział to nadal 4, nie 4 + premia: cztery przechodzą, piąte nie.
		for (int i = 3; i < 7; i++) {
			report(reporter, posts.get(i));
		}
		mockMvc.perform(
				post("/api/v1/posts/" + posts.get(7) + "/reports").header("Authorization", "Bearer " + reporter.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"reason":"SPAM"}"""))
				.andExpect(status().isTooManyRequests());
	}

	/**
	 * Skasowanie <b>własnego</b> posta nie jest rozstrzygnięciem zgłoszenia —
	 * inaczej autor spamu podnosiłby trafność zgłaszającym jednym kliknięciem, a to
	 * jego decyzja, nie moderacji.
	 */
	@Test
	void authorRemovingTheirOwnPostDoesNotCountAsUpheld() throws Exception {
		TestUser owner = user("accuracy-own-owner@songly.io");
		TestUser author = user("accuracy-own-author@songly.io");
		TestUser reporter = user("accuracy-own-reporter@songly.io");
		String communityId = createCommunity(owner, "Accuracy-wlasny");
		join(author, communityId);
		String first = createPost(author, communityId, "post do samousuniecia");
		String second = createPost(author, communityId, "kolejny post");
		report(reporter, first);

		removePost(author, first);

		report(reporter, second);
		JsonNode entry = queue(owner, communityId).get(0);
		assertThat(entry.get("reporterUpheldCount").asInt()).isZero();
	}

	/**
	 * Jedno zgłoszenie to jedna decyzja. Powtórzone odrzucenie (podwójne
	 * kliknięcie, dwóch moderatorów naraz) zapisywało dotąd kolejną karę w
	 * przydziale, więc jedno zgłoszenie wystarczało, żeby zbić komuś limit do
	 * podłogi.
	 */
	@Test
	void dismissingTheSameReportTwiceRecordsOneDecision() throws Exception {
		TestUser owner = user("accuracy-twice-owner@songly.io");
		TestUser author = user("accuracy-twice-author@songly.io");
		TestUser reporter = user("accuracy-twice-reporter@songly.io");
		String communityId = createCommunity(owner, "Accuracy-dwukrotnie");
		join(author, communityId);
		report(reporter, createPost(author, communityId, "post sporny"));
		String reportId = queue(owner, communityId).get(0).get("id").asText();

		for (int attempt = 0; attempt < 3; attempt++) {
			mockMvc.perform(post("/api/v1/communities/" + communityId + "/post-reports/" + reportId + "/dismissal")
					.header("Authorization", "Bearer " + owner.token())).andExpect(status().isNoContent());
		}

		UUID reporterId = UUID.fromString(reporter.userId());
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM reporter_decisions WHERE user_id = ? AND outcome = 'DISMISSED'", Long.class,
				reporterId)).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'POST_REPORT_DISMISSED'", Long.class,
				reporterId)).isEqualTo(1L);
	}
}
