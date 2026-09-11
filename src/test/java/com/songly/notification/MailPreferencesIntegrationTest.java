package com.songly.notification;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Preferencje kanału e-mail (ADR 0020).
 *
 * <p>
 * Najważniejszy tu jest niezmiennik, a nie sam przełącznik: wyłączenie poczty
 * <b>nie rusza skrzynki w aplikacji</b>. Decyzji moderacyjnej nie da się
 * odmówić (ADR 0012), a ścieżka odwoławcza (ADR 0017) traciłaby sens, gdyby
 * dało się nie dowiedzieć o własnym banie.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class MailPreferencesIntegrationTest extends AbstractIntegrationTest {

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

	private void setPreferences(TestUser user, boolean decisionsAboutMe, boolean moderationQueue) throws Exception {
		mockMvc.perform(put("/api/v1/notifications/mail-preferences").header("Authorization", "Bearer " + user.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"decisionsAboutMe":%b,"moderationQueue":%b}""".formatted(decisionsAboutMe, moderationQueue)))
				.andExpect(status().isNoContent());
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

	private void ban(TestUser moderator, String communityId, TestUser target) throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + target.userId())
				.header("Authorization", "Bearer " + moderator.token()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"reason":"spam"}""")).andExpect(status().isNoContent());
	}

	private long countOfType(TestUser user, String type) throws Exception {
		JsonNode content = objectMapper
				.readTree(
						mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + user.token()))
								.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("content");
		long count = 0;
		for (JsonNode node : content) {
			if (type.equals(node.get("type").asText())) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Brak wiersza to zgoda — te maile są konsekwencją korzystania z usługi, nie
	 * marketingiem.
	 */
	@Test
	void defaultsAreBothEnabledAndUpdatePersists() throws Exception {
		TestUser someone = user("mailprefs-default@songly.io");

		mockMvc.perform(
				get("/api/v1/notifications/mail-preferences").header("Authorization", "Bearer " + someone.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.decisionsAboutMe").value(true))
				.andExpect(jsonPath("$.moderationQueue").value(true));

		setPreferences(someone, false, true);

		mockMvc.perform(
				get("/api/v1/notifications/mail-preferences").header("Authorization", "Bearer " + someone.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.decisionsAboutMe").value(false))
				.andExpect(jsonPath("$.moderationQueue").value(true));
	}

	/**
	 * Sedno ADR 0020: wypisanie się z poczty wycisza <b>tylko</b> pocztę.
	 * Powiadomienie o banie zostaje w skrzynce, bo to ono jest zapisem decyzji, od
	 * której da się odwołać.
	 */
	@Test
	void disablingDecisionMailLeavesTheInboxUntouched() throws Exception {
		TestUser owner = user("mailprefs-ban-owner@songly.io");
		TestUser troll = user("mailprefs-ban-troll@songly.io");
		String communityId = createCommunity(owner, "Mailprefs-ban");
		join(troll, communityId);
		setPreferences(troll, false, true);
		mails.clearFor(troll.email());

		ban(owner, communityId, troll);

		assertThat(mails.sentTo(troll.email())).isEmpty();
		assertThat(countOfType(troll, "COMMUNITY_BANNED")).isEqualTo(1);
	}

	/**
	 * Filtr działa <b>per adresat</b>: jeden moderator wypisany z kolejki nie może
	 * wyciszyć maila pozostałym, bo zgłoszenie idzie do wszystkich naraz.
	 */
	@Test
	void queueMailIsFilteredPerRecipient() throws Exception {
		TestUser owner = user("mailprefs-queue-owner@songly.io");
		TestUser moderator = user("mailprefs-queue-mod@songly.io");
		TestUser author = user("mailprefs-queue-author@songly.io");
		TestUser reporter = user("mailprefs-queue-reporter@songly.io");
		String communityId = createCommunity(owner, "Mailprefs-kolejka");
		join(moderator, communityId);
		join(author, communityId);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/members/" + moderator.userId() + "/role")
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"role":"MODERATOR"}""")).andExpect(status().isNoContent());
		String postId = objectMapper.readTree(mockMvc
				.perform(post("/api/v1/communities/" + communityId + "/posts")
						.header("Authorization", "Bearer " + author.token()).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"content":"sporny post"}"""))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
		// Właściciel nie chce poczty z kolejki, moderator chce.
		setPreferences(owner, true, false);
		mails.clear();

		mockMvc.perform(
				post("/api/v1/posts/" + postId + "/reports").header("Authorization", "Bearer " + reporter.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"reason":"SPAM"}"""))
				.andExpect(status().isAccepted());

		assertThat(mails.sentTo(owner.email())).isEmpty();
		assertThat(mails.sentTo(moderator.email())).hasSize(1);
		// Skrzynka w aplikacji dostaje powiadomienie niezależnie od preferencji poczty.
		assertThat(countOfType(owner, "COMMUNITY_POST_REPORTED")).isEqualTo(1);
	}

	/**
	 * Dwa przełączniki są niezależne, bo odpowiadają dwóm rolom: moderator
	 * zasypywany pocztą ze swojej społeczności nie powinien przy okazji tracić
	 * informacji o własnym koncie.
	 */
	@Test
	void switchesAreIndependent() throws Exception {
		TestUser owner = user("mailprefs-indep-owner@songly.io");
		TestUser moderator = user("mailprefs-indep-mod@songly.io");
		String communityId = createCommunity(owner, "Mailprefs-niezaleznosc");
		join(moderator, communityId);
		// Moderator wypisuje się z kolejki, ale zostaje przy decyzjach o sobie...
		setPreferences(moderator, true, false);
		mails.clearFor(moderator.email());

		// ...a ban to decyzja o nim, więc mail przychodzi.
		ban(owner, communityId, moderator);

		assertThat(mails.sentTo(moderator.email())).hasSize(1);
		assertThat(mails.lastTo(moderator.email()).subject()).contains("ban");
	}
}
