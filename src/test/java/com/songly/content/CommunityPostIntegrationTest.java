package com.songly.content;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Posty w społecznościach — publikacja, izolacja od feedu, moderacja przez
 * właściciela i sprzątanie po usuniętej społeczności.
 *
 * <p>
 * Test integracyjny, nie jednostkowy, bo najciekawsze rzeczy dzieją się na
 * granicy modułów i w bazie: {@code posts.community_id} ma klucz obcy do
 * {@code communities}, więc usunięcie społeczności musi przejść przez zdarzenie
 * i sprzątanie w module content — mock nie pokazałby, czy baza to w ogóle
 * przyjmie.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class CommunityPostIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private record TestUser(String token, String userId) {
	}

	private TestUser register(String email) throws Exception {
		MvcResult result = registerAndVerify(email, "password123");
		String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
		MvcResult profile = mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andReturn();
		return new TestUser(token,
				objectMapper.readTree(profile.getResponse().getContentAsString()).get("userId").asText());
	}

	private String createCommunity(TestUser owner, String name) throws Exception {
		MvcResult result = mockMvc
				.perform(post("/api/v1/communities").header("Authorization", "Bearer " + owner.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"name":"%s","description":"opis"}""".formatted(name)))
				.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void join(TestUser user, String communityId) throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + user.token())).andExpect(status().isNoContent());
	}

	private String publish(TestUser user, String communityId, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/communities/" + communityId + "/posts")
				.header("Authorization", "Bearer " + user.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"%s"}""".formatted(content))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.communityId").value(communityId)).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void befriend(TestUser a, TestUser b) throws Exception {
		MvcResult request = mockMvc
				.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + a.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"addresseeId":"%s"}""".formatted(b.userId())))
				.andExpect(status().isCreated()).andReturn();
		String requestId = objectMapper.readTree(request.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId)).header("Authorization",
				"Bearer " + b.token())).andExpect(status().isOk());
	}

	@Test
	void memberPublishes_andPostIsListedInTheCommunity() throws Exception {
		TestUser owner = register("cpost-owner@songly.io");
		String communityId = createCommunity(owner, "Fani Doom Metalu");

		publish(owner, communityId, "Nowa plyta Candlemass");

		mockMvc.perform(
				get("/api/v1/communities/" + communityId + "/posts").header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].content").value("Nowa plyta Candlemass"))
				.andExpect(jsonPath("$.content[0].communityId").value(communityId));
	}

	@Test
	void nonMemberCannotPublish_butCanRead() throws Exception {
		TestUser owner = register("cpost-read-owner@songly.io");
		TestUser outsider = register("cpost-outsider@songly.io");
		String communityId = createCommunity(owner, "Fani Stoner Rocka");
		publish(owner, communityId, "Kyuss na zawsze");

		mockMvc.perform(post("/api/v1/communities/" + communityId + "/posts")
				.header("Authorization", "Bearer " + outsider.token()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"content":"wchodze z buta"}""")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_COMMUNITY_MEMBER"));

		// Społeczności są w pełni publiczne (listowanie, podglądanie, dołączanie bez
		// zgody), więc ukrywanie treści przed nie-członkiem byłoby ochroną pozorną.
		mockMvc.perform(get("/api/v1/communities/" + communityId + "/posts").header("Authorization",
				"Bearer " + outsider.token())).andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1));
	}

	@Test
	void publishingToUnknownCommunity_returnsNotFound() throws Exception {
		TestUser user = register("cpost-unknown@songly.io");

		mockMvc.perform(post("/api/v1/communities/" + UUID.randomUUID() + "/posts")
				.header("Authorization", "Bearer " + user.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"w pustke"}""")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
	}

	/**
	 * Sedno rozdziału obu światów: post z niszowej społeczności nie może wypływać
	 * na feed ani profil autora, bo trafiłby do ludzi, którzy do tej społeczności
	 * nie należą i nigdy jej nie wybrali.
	 */
	@Test
	void communityPostStaysOutOfFeedAndAuthorTimeline() throws Exception {
		TestUser author = register("cpost-author@songly.io");
		TestUser friend = register("cpost-friend@songly.io");
		befriend(author, friend);
		String communityId = createCommunity(author, "Fani Grindcore");

		publish(author, communityId, "post spolecznosciowy");
		mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + author.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"post zwykly"}""")).andExpect(status().isCreated());

		mockMvc.perform(get("/api/v1/feed").header("Authorization", "Bearer " + friend.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].content").value("post zwykly"));

		mockMvc.perform(get("/api/v1/posts").param("authorId", author.userId()).header("Authorization",
				"Bearer " + friend.token())).andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].content").value("post zwykly"));
	}

	/**
	 * Minimum moderacji (Claude.md §8) — bez tego społeczność nie ma żadnej obrony
	 * przed spamem, a właściciel nie może zrobić nic z treścią w miejscu, które
	 * założył.
	 */
	@Test
	void communityOwnerDeletesSomeoneElsesPost() throws Exception {
		TestUser owner = register("cpost-mod-owner@songly.io");
		TestUser member = register("cpost-mod-member@songly.io");
		TestUser outsider = register("cpost-mod-outsider@songly.io");
		String communityId = createCommunity(owner, "Fani Sludge");
		join(member, communityId);
		String postId = publish(member, communityId, "spam");

		// Ani autor, ani właściciel — moderacja nie rozlewa się na wszystkich.
		mockMvc.perform(delete("/api/v1/posts/" + postId).header("Authorization", "Bearer " + outsider.token()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_RESOURCE_OWNER"));

		mockMvc.perform(delete("/api/v1/posts/" + postId).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isNoContent());

		mockMvc.perform(
				get("/api/v1/communities/" + communityId + "/posts").header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
	}

	/**
	 * Ownerowi wolno moderować tylko u siebie: post zwykły (bez społeczności)
	 * chroni wyłącznie autorstwo.
	 */
	@Test
	void communityOwnerCannotDeletePlainPostsOfOthers() throws Exception {
		TestUser owner = register("cpost-scope-owner@songly.io");
		TestUser other = register("cpost-scope-other@songly.io");
		createCommunity(owner, "Fani Post-Rocka");

		MvcResult plain = mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + other.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"moj post"}""")).andExpect(status().isCreated()).andReturn();
		String postId = objectMapper.readTree(plain.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(delete("/api/v1/posts/" + postId).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isForbidden());
	}

	/**
	 * {@code posts.community_id} ma klucz obcy do {@code communities}, więc bez
	 * synchronicznego sprzątania w module content baza odrzuciłaby to usunięcie.
	 */
	@Test
	void deletingCommunity_removesItsPosts() throws Exception {
		TestUser owner = register("cpost-del-owner@songly.io");
		TestUser member = register("cpost-del-member@songly.io");
		String communityId = createCommunity(owner, "Fani Krautrocka");
		join(member, communityId);
		String postId = publish(member, communityId, "Can - Tago Mago");

		mockMvc.perform(delete("/api/v1/communities/" + communityId).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + member.token()))
				.andExpect(status().isNotFound());
	}

	/**
	 * Ta sama pułapka na drugiej ścieżce: usunięcie konta właściciela kasuje jego
	 * społeczności, a razem z nimi muszą zniknąć posty — również cudze. Wcześniej
	 * szło to masowym {@code deleteByOwnerId}, które omija cykl życia encji i nie
	 * wysłałoby ani jednego zdarzenia.
	 */
	@Test
	void deletingOwnerAccount_removesCommunityPostsOfOtherUsers() throws Exception {
		TestUser owner = register("cpost-acc-owner@songly.io");
		TestUser member = register("cpost-acc-member@songly.io");
		String communityId = createCommunity(owner, "Fani Shoegaze");
		join(member, communityId);
		String postId = publish(member, communityId, "Loveless");

		mockMvc.perform(delete("/api/v1/auth/me").header("Authorization", "Bearer " + owner.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"password":"password123"}""")).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + member.token()))
				.andExpect(status().isNotFound());
	}
}
