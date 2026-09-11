package com.songly.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.HashMap;
import java.util.Map;
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
 * Analityka moderacji (ADR 0019). Podsystem z ADR 0011–0018 nie zostawiał dotąd
 * w analityce ani jednego śladu, więc decyzje odłożone „do czasu, aż dane
 * pokażą" nie miały skąd wziąć tych danych.
 *
 * <p>
 * Liczniki porównujemy deltą przed/po, bo baza jest współdzielona między
 * klasami testowymi — dokładnie jak w {@code AnalyticsFlowIntegrationTest}.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class ModerationAnalyticsIntegrationTest extends AbstractIntegrationTest {

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

	private Map<String, Long> counts(TestUser adminUser) throws Exception {
		JsonNode body = objectMapper.readTree(
				mockMvc.perform(get("/api/v1/analytics/summary").header("Authorization", "Bearer " + adminUser.token()))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		Map<String, Long> counts = new HashMap<>();
		for (JsonNode entry : body.get("counts")) {
			counts.put(entry.get("type").asText(), entry.get("count").asLong());
		}
		return counts;
	}

	private long delta(Map<String, Long> before, Map<String, Long> after, String type) {
		return after.getOrDefault(type, 0L) - before.getOrDefault(type, 0L);
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

	private void join(TestUser member, String communityId) throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + member.token())).andExpect(status().isNoContent());
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

	private void ban(TestUser moderator, String communityId, TestUser target, Integer durationDays) throws Exception {
		String body = durationDays == null ? """
				{"reason":"spam"}""" : """
				{"reason":"spam","durationDays":%d}""".formatted(durationDays);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + target.userId())
				.header("Authorization", "Bearer " + moderator.token()).contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isNoContent());
	}

	private void appeal(TestUser banned, String communityId) throws Exception {
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/ban-appeal")
				.header("Authorization", "Bearer " + banned.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"odwoluje sie"}""")).andExpect(status().isNoContent());
	}

	/**
	 * Zgłoszenia i ich rozstrzygnięcia. {@code POST_FIRST_REPORTED} liczy
	 * <b>posty</b>, nie kliknięcia „zgłoś" — drugie zgłoszenie tego samego posta
	 * nie publikuje zdarzenia (bramka z ADR 0012 siedzi u publikującego), więc
	 * licznik z założenia nie jest wolumenem zgłoszeń.
	 */
	@Test
	void reportsAndTheirOutcomesAreCounted() throws Exception {
		TestUser adminUser = admin("modanalytics-rep-admin@songly.io");
		TestUser owner = user("modanalytics-rep-owner@songly.io");
		TestUser author = user("modanalytics-rep-author@songly.io");
		TestUser reporter = user("modanalytics-rep-reporter@songly.io");
		TestUser secondReporter = user("modanalytics-rep-reporter2@songly.io");
		String communityId = createCommunity(owner, "Modanalytics-zgloszenia");
		join(author, communityId);
		String contested = createPost(author, communityId, "sporny post");
		String removed = createPost(author, communityId, "post do usuniecia");
		Map<String, Long> before = counts(adminUser);

		report(reporter, contested);
		report(secondReporter, contested);
		report(reporter, removed);

		String reportId = objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/post-reports").header("Authorization",
						"Bearer " + owner.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content").get(0)
				.get("id").asText();
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/post-reports/" + reportId + "/dismissal")
				.header("Authorization", "Bearer " + owner.token())).andExpect(status().isNoContent());
		mockMvc.perform(delete("/api/v1/posts/" + removed).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isNoContent());

		Map<String, Long> after = counts(adminUser);
		assertThat(delta(before, after, "POST_FIRST_REPORTED")).isEqualTo(2);
		assertThat(delta(before, after, "POST_REPORT_DISMISSED")).isEqualTo(1);
		assertThat(delta(before, after, "COMMUNITY_POST_REMOVED")).isEqualTo(1);
	}

	/**
	 * Bany rozdzielone po terminie — bez tego nie da się sprawdzić, czy bany
	 * czasowe (ADR 0014) w ogóle się przyjęły.
	 */
	@Test
	void permanentAndTemporaryBansAreCountedSeparately() throws Exception {
		TestUser adminUser = admin("modanalytics-ban-admin@songly.io");
		TestUser owner = user("modanalytics-ban-owner@songly.io");
		TestUser firstTroll = user("modanalytics-ban-troll1@songly.io");
		TestUser secondTroll = user("modanalytics-ban-troll2@songly.io");
		String communityId = createCommunity(owner, "Modanalytics-bany");
		join(firstTroll, communityId);
		join(secondTroll, communityId);
		Map<String, Long> before = counts(adminUser);

		ban(owner, communityId, firstTroll, null);
		ban(owner, communityId, secondTroll, 7);
		// Powtórzony ban aktywnego nie jest nową decyzją (ADR 0014), więc nie liczy się
		// drugi raz.
		ban(owner, communityId, secondTroll, 7);

		Map<String, Long> after = counts(adminUser);
		assertThat(delta(before, after, "COMMUNITY_BAN_ISSUED_PERMANENT")).isEqualTo(1);
		assertThat(delta(before, after, "COMMUNITY_BAN_ISSUED_TEMPORARY")).isEqualTo(1);
	}

	/**
	 * Odwołania: złożone, uznane (przez unban) i odrzucone. To z tych trzech serii
	 * wychodzi odpowiedź na pytanie odłożone w ADR 0017 — jak często moderacja sama
	 * uznaje, że się pomyliła.
	 */
	@Test
	void appealsAndTheirOutcomesAreCounted() throws Exception {
		TestUser adminUser = admin("modanalytics-app-admin@songly.io");
		TestUser owner = user("modanalytics-app-owner@songly.io");
		TestUser accepted = user("modanalytics-app-accepted@songly.io");
		TestUser rejected = user("modanalytics-app-rejected@songly.io");
		String communityId = createCommunity(owner, "Modanalytics-odwolania");
		join(accepted, communityId);
		join(rejected, communityId);
		ban(owner, communityId, accepted, null);
		ban(owner, communityId, rejected, null);
		Map<String, Long> before = counts(adminUser);

		appeal(accepted, communityId);
		appeal(rejected, communityId);
		mockMvc.perform(delete("/api/v1/communities/" + communityId + "/bans/" + accepted.userId())
				.header("Authorization", "Bearer " + owner.token())).andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/bans/" + rejected.userId() + "/appeal/rejection")
				.header("Authorization", "Bearer " + owner.token())).andExpect(status().isNoContent());

		Map<String, Long> after = counts(adminUser);
		assertThat(delta(before, after, "BAN_APPEAL_SUBMITTED")).isEqualTo(2);
		assertThat(delta(before, after, "BAN_APPEAL_ACCEPTED")).isEqualTo(1);
		assertThat(delta(before, after, "BAN_APPEAL_REJECTED")).isEqualTo(1);
	}
}
