package com.songly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Powiadomienia o decyzjach moderacyjnych (ADR 0012).
 *
 * <p>
 * ADR 0011 dał narzędzia, ale zostawił obie strony w ciemności: zbanowany
 * dowiadywał się o banie dopiero przy próbie napisania czegokolwiek (czyli
 * decyzja wyglądała jak awaria aplikacji), a zgłaszający nie dostawał żadnej
 * informacji zwrotnej. Kolejka zgłoszeń działała tylko wtedy, gdy moderator sam
 * do niej zajrzał.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class ModerationNotificationIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

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

	private JsonNode notifications(TestUser user) throws Exception {
		return objectMapper
				.readTree(
						mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + user.token()))
								.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("content");
	}

	private long countOfType(TestUser user, String type) throws Exception {
		JsonNode content = notifications(user);
		long count = 0;
		for (JsonNode node : content) {
			if (type.equals(node.get("type").asText())) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Zbanowany dowiaduje się o banie od razu, a nie przy próbie napisania czegoś.
	 */
	@Test
	void bannedUserIsNotified() throws Exception {
		TestUser owner = user("modnotif-ban-owner@songly.io");
		TestUser troll = user("modnotif-ban-troll@songly.io");
		String communityId = createCommunity(owner, "Notif-ban");
		join(troll, communityId);

		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + troll.userId())
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"spam"}""")).andExpect(status().isNoContent());

		JsonNode first = notifications(troll).get(0);
		assertThat(first.get("type").asText()).isEqualTo("COMMUNITY_BANNED");
		assertThat(first.get("resourceId").asText()).isEqualTo(communityId);
		assertThat(first.get("actorId").asText()).isEqualTo(owner.userId());
	}

	/**
	 * Ban jest idempotentny, więc powtórzone kliknięcie moderatora nie może
	 * dokładać kolejnych powiadomień o tym samym.
	 */
	@Test
	void repeatedBan_doesNotRepeatTheNotification() throws Exception {
		TestUser owner = user("modnotif-reban-owner@songly.io");
		TestUser troll = user("modnotif-reban-troll@songly.io");
		String communityId = createCommunity(owner, "Notif-reban");
		join(troll, communityId);

		for (int attempt = 0; attempt < 3; attempt++) {
			mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + troll.userId())
					.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"reason":"spam"}""")).andExpect(status().isNoContent());
		}

		assertThat(countOfType(troll, "COMMUNITY_BANNED")).isEqualTo(1);
	}

	@Test
	void authorIsNotifiedWhenModeratorRemovesTheirPost_butNotWhenTheyRemoveItThemselves() throws Exception {
		TestUser owner = user("modnotif-rm-owner@songly.io");
		TestUser author = user("modnotif-rm-author@songly.io");
		String communityId = createCommunity(owner, "Notif-usuwanie");
		join(author, communityId);

		String ownPostId = createPost(author, communityId, "sam usuwam");
		mockMvc.perform(delete("/api/v1/posts/" + ownPostId).header("Authorization", "Bearer " + author.token()))
				.andExpect(status().isNoContent());
		assertThat(countOfType(author, "COMMUNITY_POST_REMOVED")).isZero();

		String moderatedPostId = createPost(author, communityId, "usuwa moderator");
		mockMvc.perform(delete("/api/v1/posts/" + moderatedPostId).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isNoContent());

		assertThat(countOfType(author, "COMMUNITY_POST_REMOVED")).isEqualTo(1);
	}

	/**
	 * To jest ta połowa moderacji, której sama kolejka nie załatwia: kolejka
	 * wymaga, żeby ktoś do niej zajrzał, a powiadomienie mówi, że jest po co.
	 * Dostają je <b>wszyscy</b> moderatorzy.
	 */
	@Test
	void allModeratorsAreNotifiedAboutTheFirstReport() throws Exception {
		TestUser owner = user("modnotif-rep-owner@songly.io");
		TestUser moderator = user("modnotif-rep-mod@songly.io");
		TestUser author = user("modnotif-rep-author@songly.io");
		TestUser reporter = user("modnotif-rep-reporter@songly.io");
		TestUser secondReporter = user("modnotif-rep-reporter2@songly.io");
		String communityId = createCommunity(owner, "Notif-zgloszenia");
		join(moderator, communityId);
		join(author, communityId);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/members/" + moderator.userId() + "/role")
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"role":"MODERATOR"}""")).andExpect(status().isNoContent());
		String postId = createPost(author, communityId, "sporny post");

		mockMvc.perform(
				post("/api/v1/posts/" + postId + "/reports").header("Authorization", "Bearer " + reporter.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"reason":"SPAM"}"""))
				.andExpect(status().isAccepted());

		assertThat(countOfType(owner, "COMMUNITY_POST_REPORTED")).isEqualTo(1);
		assertThat(countOfType(moderator, "COMMUNITY_POST_REPORTED")).isEqualTo(1);
		// Autor zgłoszonego posta nie dostaje nic — zgłoszenie nie jest wiadomością
		// do niego.
		assertThat(countOfType(author, "COMMUNITY_POST_REPORTED")).isZero();

		// Drugie zgłoszenie tego samego posta nie zasypuje moderatorów ponownie —
		// niesie je licznik w kolejce, nie kolejne powiadomienie.
		mockMvc.perform(
				post("/api/v1/posts/" + postId + "/reports").header("Authorization", "Bearer " + secondReporter.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"reason":"SPAM"}"""))
				.andExpect(status().isAccepted());
		assertThat(countOfType(owner, "COMMUNITY_POST_REPORTED")).isEqualTo(1);
	}

	@Test
	void reporterIsNotifiedWhenTheirReportIsDismissed() throws Exception {
		TestUser owner = user("modnotif-dis-owner@songly.io");
		TestUser author = user("modnotif-dis-author@songly.io");
		TestUser reporter = user("modnotif-dis-reporter@songly.io");
		String communityId = createCommunity(owner, "Notif-odrzucenia");
		join(author, communityId);
		String postId = createPost(author, communityId, "post do zgloszenia");
		mockMvc.perform(
				post("/api/v1/posts/" + postId + "/reports").header("Authorization", "Bearer " + reporter.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"reason":"SPAM"}"""))
				.andExpect(status().isAccepted());
		String reportId = objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/post-reports").header("Authorization",
						"Bearer " + owner.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content").get(0)
				.get("id").asText();

		mockMvc.perform(post("/api/v1/communities/" + communityId + "/post-reports/" + reportId + "/dismissal")
				.header("Authorization", "Bearer " + owner.token())).andExpect(status().isNoContent());

		JsonNode first = notifications(reporter).get(0);
		assertThat(first.get("type").asText()).isEqualTo("POST_REPORT_DISMISSED");
		assertThat(first.get("resourceId").asText()).isEqualTo(postId);
	}

	/**
	 * Sedno tej zmiany od strony bezpieczeństwa: powiadomienie o decyzji
	 * moderacyjnej <b>przechodzi mimo blokady</b>. Reszta skrzynki znika, gdy
	 * nadawca jest zablokowany (§9) — i tak ma być, bo blokada odcina od zaczepek.
	 * Ale decyzji moderacyjnej nie da się odmówić: gdyby podlegała temu filtrowi,
	 * wystarczyłoby zablokować moderatora, żeby nigdy nie zobaczyć informacji o
	 * własnym banie.
	 */
	@Test
	void moderationNotificationSurvivesBlockingTheModerator() throws Exception {
		TestUser owner = user("modnotif-block-owner@songly.io");
		TestUser troll = user("modnotif-block-troll@songly.io");
		String communityId = createCommunity(owner, "Notif-blokada");
		join(troll, communityId);

		// Zwykłe powiadomienie od tej samej osoby, dla kontrastu: właściciel lajkuje
		// post trolla poza społecznością.
		String plainPostId = objectMapper.readTree(mockMvc
				.perform(post("/api/v1/posts").header("Authorization", "Bearer " + troll.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"zwykly post"}"""))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(
				put("/api/v1/posts/" + plainPostId + "/likes").header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isNoContent());
		assertThat(countOfType(troll, "POST_LIKED")).isEqualTo(1);

		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + troll.userId())
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"spam"}""")).andExpect(status().isNoContent());

		// Troll blokuje moderatora — po tym zwykłe powiadomienia od niego znikają...
		mockMvc.perform(put("/api/v1/blocks/" + owner.userId()).header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isNoContent());

		assertThat(countOfType(troll, "POST_LIKED")).isZero();
		// ...ale informacja o banie zostaje.
		assertThat(countOfType(troll, "COMMUNITY_BANNED")).isEqualTo(1);
	}

	/**
	 * Licznik nieprzeczytanych musi liczyć to samo, co widać na liście — inaczej
	 * plakietka pokazywałaby liczbę, której nie da się zbić.
	 */
	@Test
	void unreadCountMatchesWhatIsVisible() throws Exception {
		TestUser owner = user("modnotif-count-owner@songly.io");
		TestUser troll = user("modnotif-count-troll@songly.io");
		String communityId = createCommunity(owner, "Notif-licznik");
		join(troll, communityId);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + troll.userId())
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"spam"}""")).andExpect(status().isNoContent());
		mockMvc.perform(put("/api/v1/blocks/" + owner.userId()).header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));

		mockMvc.perform(post("/api/v1/notifications/read-all").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
	}
}
