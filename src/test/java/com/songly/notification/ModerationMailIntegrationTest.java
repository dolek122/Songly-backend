package com.songly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import com.songly.RecordingMailSenderConfig.SentMail;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * E-mail jako drugi kanał powiadomień o decyzjach moderacyjnych (ADR 0013).
 *
 * <p>
 * Skrzynkę w aplikacji pokrywa {@code ModerationNotificationIntegrationTest} —
 * tu sprawdzamy wyłącznie, co wyszło mailem. Rejestracja też wysyła wiadomości
 * (weryfikacja adresu, ADR 0009), więc każdy przypadek zaczyna od
 * {@code mails.clearFor} adresata, którego skrzynkę pocztową potem asertuje.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class ModerationMailIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private record TestUser(String token, String userId, String email) {
	}

	private TestUser user(String email) throws Exception {
		String token = objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
		String userId = objectMapper
				.readTree(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("userId").asText();
		return new TestUser(token, userId, email);
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

	private void ban(TestUser moderator, String communityId, TestUser target) throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + target.userId())
				.header("Authorization", "Bearer " + moderator.token()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"reason":"spam"}""")).andExpect(status().isNoContent());
	}

	private void report(TestUser reporter, String postId) throws Exception {
		mockMvc.perform(
				post("/api/v1/posts/" + postId + "/reports").header("Authorization", "Bearer " + reporter.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"reason":"SPAM"}"""))
				.andExpect(status().isAccepted());
	}

	@Test
	void bannedUserGetsAnEmailNamingTheCommunity() throws Exception {
		TestUser owner = user("modmail-ban-owner@songly.io");
		TestUser troll = user("modmail-ban-troll@songly.io");
		String communityId = createCommunity(owner, "Mail-ban");
		join(troll, communityId);
		mails.clearFor(troll.email());

		ban(owner, communityId, troll);

		SentMail mail = mails.lastTo(troll.email());
		assertThat(mail.subject()).contains("ban").contains("Mail-ban");
		assertThat(mail.body()).contains("Mail-ban");
	}

	/**
	 * Bramka „tylko nowy ban" jest u publikującego zdarzenie, więc obowiązuje oba
	 * kanały naraz — mail dziedziczy ją tak samo jak skrzynka.
	 */
	@Test
	void repeatedBanDoesNotRepeatTheEmail() throws Exception {
		TestUser owner = user("modmail-reban-owner@songly.io");
		TestUser troll = user("modmail-reban-troll@songly.io");
		String communityId = createCommunity(owner, "Mail-reban");
		join(troll, communityId);
		mails.clearFor(troll.email());

		for (int attempt = 0; attempt < 3; attempt++) {
			ban(owner, communityId, troll);
		}

		assertThat(mails.sentTo(troll.email())).hasSize(1);
	}

	@Test
	void authorGetsAnEmailOnlyWhenSomeoneElseRemovesTheirPost() throws Exception {
		TestUser owner = user("modmail-rm-owner@songly.io");
		TestUser author = user("modmail-rm-author@songly.io");
		String communityId = createCommunity(owner, "Mail-usuwanie");
		join(author, communityId);
		String ownPostId = createPost(author, communityId, "sam usuwam");
		String moderatedPostId = createPost(author, communityId, "usuwa moderator");
		mails.clearFor(author.email());

		mockMvc.perform(delete("/api/v1/posts/" + ownPostId).header("Authorization", "Bearer " + author.token()))
				.andExpect(status().isNoContent());
		assertThat(mails.sentTo(author.email())).isEmpty();

		mockMvc.perform(delete("/api/v1/posts/" + moderatedPostId).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isNoContent());

		SentMail mail = mails.lastTo(author.email());
		assertThat(mail.subject()).contains("usunięty").contains("Mail-usuwanie");
	}

	/**
	 * Sedno ADR 0013: mail o zgłoszeniu dosięga moderatorów, którzy do aplikacji
	 * nie zaglądają — skrzynka w aplikacji tego nie umiała.
	 */
	@Test
	void firstReportEmailsAllModeratorsAndOnlyOnce() throws Exception {
		TestUser owner = user("modmail-rep-owner@songly.io");
		TestUser moderator = user("modmail-rep-mod@songly.io");
		TestUser author = user("modmail-rep-author@songly.io");
		TestUser reporter = user("modmail-rep-reporter@songly.io");
		TestUser secondReporter = user("modmail-rep-reporter2@songly.io");
		String communityId = createCommunity(owner, "Mail-zgloszenia");
		join(moderator, communityId);
		join(author, communityId);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/members/" + moderator.userId() + "/role")
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"role":"MODERATOR"}""")).andExpect(status().isNoContent());
		String postId = createPost(author, communityId, "sporny post");
		mails.clear();

		report(reporter, postId);

		assertThat(mails.lastTo(owner.email()).subject()).contains("zgłoszenie").contains("Mail-zgloszenia");
		assertThat(mails.lastTo(moderator.email()).subject()).contains("zgłoszenie");
		// Ani autor zgłoszonego posta, ani zgłaszający nie dostają maila.
		assertThat(mails.sentTo(author.email())).isEmpty();
		assertThat(mails.sentTo(reporter.email())).isEmpty();

		// Drugie zgłoszenie tego samego posta nie wysyła niczego — bramka „tylko
		// pierwsze zgłoszenie" jest u publikującego, więc obowiązuje i mail.
		report(secondReporter, postId);
		assertThat(mails.sentTo(owner.email())).hasSize(1);
		assertThat(mails.sentTo(moderator.email())).hasSize(1);
	}

	@Test
	void reporterGetsAnEmailWhenTheirReportIsDismissed() throws Exception {
		TestUser owner = user("modmail-dis-owner@songly.io");
		TestUser author = user("modmail-dis-author@songly.io");
		TestUser reporter = user("modmail-dis-reporter@songly.io");
		String communityId = createCommunity(owner, "Mail-odrzucenia");
		join(author, communityId);
		String postId = createPost(author, communityId, "post do zgloszenia");
		report(reporter, postId);
		String reportId = objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/post-reports").header("Authorization",
						"Bearer " + owner.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content").get(0)
				.get("id").asText();
		mails.clearFor(reporter.email());

		mockMvc.perform(post("/api/v1/communities/" + communityId + "/post-reports/" + reportId + "/dismissal")
				.header("Authorization", "Bearer " + owner.token())).andExpect(status().isNoContent());

		SentMail mail = mails.lastTo(reporter.email());
		assertThat(mail.subject()).contains("rozpatrzone");
		assertThat(mail.body()).contains("Mail-odrzucenia");
	}

	/**
	 * Mail wychodzi wyłącznie dla decyzji moderacyjnych. Zwykła aktywność (lajk)
	 * zapisuje się w skrzynce, ale mailem nie wychodzi — skrzynka umie ukryć
	 * powiadomienie od zablokowanego przy odczycie, maila nie da się cofnąć.
	 */
	@Test
	void ordinaryActivityDoesNotSendEmail() throws Exception {
		TestUser author = user("modmail-like-author@songly.io");
		TestUser fan = user("modmail-like-fan@songly.io");
		String postId = objectMapper.readTree(mockMvc
				.perform(post("/api/v1/posts").header("Authorization", "Bearer " + author.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"zwykly post"}"""))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
		mails.clearFor(author.email());

		mockMvc.perform(put("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + fan.token()))
				.andExpect(status().isNoContent());

		List<SentMail> sent = mails.sentTo(author.email());
		assertThat(sent).isEmpty();
	}
}
