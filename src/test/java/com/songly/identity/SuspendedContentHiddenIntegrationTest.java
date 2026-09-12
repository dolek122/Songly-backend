package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * Ukrywanie treści zawieszonego konta (ADR 0026).
 *
 * <p>
 * ADR 0022 odciął zawieszone konto od pisania i czytania, ale jego treści stały
 * dalej: zawieszało się kogoś za nękanie, a nękające posty, komentarze i profil
 * zostawały na widoku. Najważniejszy jest tu ostatni test — zdjęcie zawieszenia
 * przywraca wszystko, bo ukrywamy przy odczycie i nigdy nie kasujemy.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class SuspendedContentHiddenIntegrationTest extends AbstractIntegrationTest {

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

	private void rename(TestUser user, String displayName) throws Exception {
		mockMvc.perform(put("/api/v1/profiles/me").header("Authorization", "Bearer " + user.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"displayName":"%s","bio":"bio"}""".formatted(displayName))).andExpect(status().isOk());
	}

	private String createPost(TestUser author, String content) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(post("/api/v1/posts").header("Authorization", "Bearer " + author.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"%s"}""".formatted(content)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
	}

	private void suspend(TestUser admin, TestUser target, Integer durationDays) throws Exception {
		String body = durationDays == null ? """
				{"reason":"nękanie"}""" : """
				{"reason":"nękanie","durationDays":%d}""".formatted(durationDays);
		mockMvc.perform(put("/api/v1/admin/users/" + target.userId() + "/suspension")
				.header("Authorization", "Bearer " + admin.token()).contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isNoContent());
	}

	private boolean publicListContains(TestUser viewer, String postId) throws Exception {
		JsonNode content = objectMapper.readTree(
				mockMvc.perform(get("/api/v1/posts?size=100").header("Authorization", "Bearer " + viewer.token()))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("content");
		for (JsonNode post : content) {
			if (postId.equals(post.get("id").asText())) {
				return true;
			}
		}
		return false;
	}

	private boolean searchFinds(TestUser viewer, String query, String userId) throws Exception {
		JsonNode content = objectMapper.readTree(mockMvc
				.perform(get("/api/v1/profiles/search?query=" + query).header("Authorization",
						"Bearer " + viewer.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
		for (JsonNode entry : content) {
			if (userId.equals(entry.get("userId").asText())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Sedno ADR 0026: treści zawieszonego znikają z każdej ścieżki odczytu, a jego
	 * profil przestaje być osiągalny — także wprost z linku, nie tylko z
	 * wyszukiwarki.
	 */
	@Test
	void suspensionHidesPostsProfileAndSearchResults() throws Exception {
		TestUser adminUser = admin("hidden-main-admin@songly.io");
		TestUser viewer = user("hidden-main-viewer@songly.io");
		TestUser troll = user("hidden-main-troll@songly.io");
		rename(troll, "Nekacz Hiddenmain");
		String postId = createPost(troll, "tresc do ukrycia");
		assertThat(publicListContains(viewer, postId)).isTrue();
		assertThat(searchFinds(viewer, "Hiddenmain", troll.userId())).isTrue();

		suspend(adminUser, troll, null);

		assertThat(publicListContains(viewer, postId)).isFalse();
		assertThat(searchFinds(viewer, "Hiddenmain", troll.userId())).isFalse();
		mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + viewer.token()))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/profiles/" + troll.userId()).header("Authorization", "Bearer " + viewer.token()))
				.andExpect(status().isNotFound());
	}

	/**
	 * Post ukryty jest nietykalny także dla ścieżek zapisu — inaczej dałoby się
	 * komentować i lajkować treść, której nie widać, a autor dostawałby
	 * powiadomienia o aktywności pod niewidzialnym postem.
	 */
	@Test
	void hiddenPostCannotBeLikedOrCommented() throws Exception {
		TestUser adminUser = admin("hidden-write-admin@songly.io");
		TestUser viewer = user("hidden-write-viewer@songly.io");
		TestUser troll = user("hidden-write-troll@songly.io");
		String postId = createPost(troll, "tresc");
		suspend(adminUser, troll, null);

		mockMvc.perform(put("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + viewer.token()))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v1/posts/" + postId + "/comments")
				.header("Authorization", "Bearer " + viewer.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"komentarz"}""")).andExpect(status().isNotFound());
	}

	/**
	 * Sankcja nie rozlewa się na innych: posty pozostałych kont zostają widoczne.
	 */
	@Test
	void otherAccountsAreUnaffected() throws Exception {
		TestUser adminUser = admin("hidden-other-admin@songly.io");
		TestUser viewer = user("hidden-other-viewer@songly.io");
		TestUser troll = user("hidden-other-troll@songly.io");
		TestUser innocent = user("hidden-other-innocent@songly.io");
		rename(innocent, "Niewinny Hiddenother");
		String trollPost = createPost(troll, "tresc trolla");
		String innocentPost = createPost(innocent, "tresc niewinnego");

		suspend(adminUser, troll, null);

		assertThat(publicListContains(viewer, trollPost)).isFalse();
		assertThat(publicListContains(viewer, innocentPost)).isTrue();
		assertThat(searchFinds(viewer, "Hiddenother", innocent.userId())).isTrue();
	}

	/**
	 * Najważniejszy test tej zmiany: <b>ukrywamy przy odczycie, nigdy nie
	 * kasujemy</b>. Zawieszenia bywają czasowe i zdejmowane, więc treści muszą
	 * wrócić w całości — inaczej zawieszenie byłoby karą cięższą od samego
	 * zawieszenia.
	 */
	@Test
	void liftingAndExpiryRestoreEverything() throws Exception {
		TestUser adminUser = admin("hidden-restore-admin@songly.io");
		TestUser viewer = user("hidden-restore-viewer@songly.io");
		TestUser troll = user("hidden-restore-troll@songly.io");
		rename(troll, "Wracajacy Hiddenrestore");
		String postId = createPost(troll, "tresc wracajaca");

		// Zdjęcie zawieszenia przywraca.
		suspend(adminUser, troll, null);
		assertThat(publicListContains(viewer, postId)).isFalse();
		mockMvc.perform(delete("/api/v1/admin/users/" + troll.userId() + "/suspension").header("Authorization",
				"Bearer " + adminUser.token())).andExpect(status().isNoContent());
		assertThat(publicListContains(viewer, postId)).isTrue();
		assertThat(searchFinds(viewer, "Hiddenrestore", troll.userId())).isTrue();

		// Wygaśnięcie terminu też — i bez niczyjej akcji (ADR 0014).
		suspend(adminUser, troll, 7);
		assertThat(publicListContains(viewer, postId)).isFalse();
		OffsetDateTime past = Instant.now().minus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC);
		jdbcTemplate.update("UPDATE users SET suspension_expires_at = ? WHERE id = ?", past,
				UUID.fromString(troll.userId()));

		assertThat(publicListContains(viewer, postId)).isTrue();
		mockMvc.perform(get("/api/v1/profiles/" + troll.userId()).header("Authorization", "Bearer " + viewer.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Wracajacy Hiddenrestore"));
	}
}
