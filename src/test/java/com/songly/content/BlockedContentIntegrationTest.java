package com.songly.content;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Blokada egzekwowana w module content. Moduł social pilnował jej dotąd tylko u
 * siebie (zaproszenia) i w matchingu, więc blokada zrywała znajomość, ale nie
 * zasłaniała ani jednej treści: zablokowany dalej czytał posty blokującego i —
 * co gorsza — dalej mógł go zaczepiać komentarzem pod jego własnym postem.
 *
 * <p>
 * Test integracyjny, nie jednostkowy, bo filtr siedzi w zapytaniach do bazy
 * (inaczej psułby paginację, patrz {@link PostRepository}). Mock powiedziałby
 * tylko, że wywołaliśmy metodę z odpowiednim argumentem — nie, czy Postgres
 * naprawdę odsiał te wiersze i czy {@code totalElements} się zgadza.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class BlockedContentIntegrationTest extends AbstractIntegrationTest {

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

	private String publish(TestUser user, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + user.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"%s"}""".formatted(content))).andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void block(TestUser blocker, TestUser blocked) throws Exception {
		mockMvc.perform(put("/api/v1/blocks/" + blocked.userId()).header("Authorization", "Bearer " + blocker.token()))
				.andExpect(status().isNoContent());
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

	/**
	 * Warunek konieczny całej reszty: pusty zbiór blokad to przypadek
	 * <b>zdecydowanej większości</b> użytkowników, a filtr {@code not in} na pustej
	 * liście to klasyczna pułapka SQL-a — {@code x not in (null)} nie jest prawdą
	 * dla żadnego wiersza, więc naiwna implementacja wygasza feed wszystkim naraz.
	 * Ten test pilnuje, że tak się nie dzieje.
	 */
	@Test
	void userWithoutBlocks_seesPostsNormally() throws Exception {
		TestUser viewer = register("block-none-viewer@songly.io");
		String postId = publish(viewer, "widoczny dla wszystkich");
		mockMvc.perform(post("/api/v1/posts/" + postId + "/comments")
				.header("Authorization", "Bearer " + viewer.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"wlasny komentarz"}""")).andExpect(status().isCreated());

		// Każde z tych trzech wyjść filtruje przez `not in :excludedAuthorIds`, a
		// zbiór jest tu pusty — czyli dokładnie przypadek, który przy złym
		// potraktowaniu pustej listy zwraca zero wierszy zamiast wszystkich.
		mockMvc.perform(get("/api/v1/feed").header("Authorization", "Bearer " + viewer.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1));

		mockMvc.perform(get("/api/v1/posts").header("Authorization", "Bearer " + viewer.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(greaterThanOrEqualTo(1)));

		mockMvc.perform(
				get("/api/v1/posts/" + postId + "/comments").header("Authorization", "Bearer " + viewer.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1));

		// Licznik komentarzy też filtruje po tym samym pustym zbiorze.
		mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + viewer.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.commentCount").value(1));
	}

	@Test
	void blockedAuthorsPost_disappearsFromListsAndDirectReads() throws Exception {
		TestUser author = register("block-hide-author@songly.io");
		TestUser blocker = register("block-hide-blocker@songly.io");
		String postId = publish(author, "tresc zablokowanego");
		block(blocker, author);

		mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + blocker.token()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));

		mockMvc.perform(
				get("/api/v1/posts?authorId=" + author.userId()).header("Authorization", "Bearer " + blocker.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0))
				.andExpect(jsonPath("$.page.totalElements").value(0));
	}

	/**
	 * Blokada działa w obie strony: zablokowany traci widok tak samo jak blokujący.
	 * Inaczej blokada byłaby tylko zasłonięciem sobie oczu, a druga strona dalej
	 * śledziłaby wszystko, co piszemy.
	 */
	@Test
	void blockIsSymmetric_blockedUserAlsoLosesSight() throws Exception {
		TestUser blocker = register("block-sym-blocker@songly.io");
		TestUser blocked = register("block-sym-blocked@songly.io");
		String postId = publish(blocker, "tresc blokujacego");
		block(blocker, blocked);

		mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + blocked.token()))
				.andExpect(status().isNotFound());
	}

	/**
	 * Sedno blokady. Lajk i komentarz kończą się powiadomieniem u autora, więc bez
	 * tego sprawdzenia „zablokowanie" nie odcinałoby najbardziej dokuczliwego
	 * kanału kontaktu, jaki produkt ma.
	 */
	@Test
	void blockedUserCannotInteractWithBlockersPost() throws Exception {
		TestUser blocker = register("block-write-blocker@songly.io");
		TestUser blocked = register("block-write-blocked@songly.io");
		String postId = publish(blocker, "moj post");
		block(blocker, blocked);

		mockMvc.perform(put("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + blocked.token()))
				.andExpect(status().isNotFound());
		mockMvc.perform(put("/api/v1/posts/" + postId + "/shares").header("Authorization", "Bearer " + blocked.token()))
				.andExpect(status().isNotFound());
		mockMvc.perform(
				post("/api/v1/posts/" + postId + "/comments").header("Authorization", "Bearer " + blocked.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"zaczepka"}"""))
				.andExpect(status().isNotFound());
	}

	/**
	 * Komentarz zablokowanego znika też z cudzego posta — tam obie strony spotykają
	 * się mimo braku wspólnej relacji. Licznik musi zejść razem z listą, inaczej
	 * post pokazywałby „2 komentarze" nad listą jednego.
	 */
	@Test
	void blockedUsersComment_vanishesFromListAndCount() throws Exception {
		TestUser host = register("block-comment-host@songly.io");
		TestUser blocker = register("block-comment-blocker@songly.io");
		TestUser troll = register("block-comment-troll@songly.io");
		String postId = publish(host, "post trzeciej osoby");

		mockMvc.perform(post("/api/v1/posts/" + postId + "/comments").header("Authorization", "Bearer " + troll.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"od trolla"}""")).andExpect(status().isCreated());
		mockMvc.perform(
				post("/api/v1/posts/" + postId + "/comments").header("Authorization", "Bearer " + blocker.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"od blokujacego"}"""))
				.andExpect(status().isCreated());

		block(blocker, troll);

		mockMvc.perform(
				get("/api/v1/posts/" + postId + "/comments").header("Authorization", "Bearer " + blocker.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].content").value("od blokujacego"))
				.andExpect(jsonPath("$.page.totalElements").value(1));

		mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + blocker.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.commentCount").value(1));
	}

	/**
	 * Jedyna droga, którą treść zablokowanego wracała do feedu po zerwaniu
	 * znajomości: udostępnienie przez wspólnego znajomego. Blokada wyklucza autora
	 * z {@code userIds}, ale nie z cudzych udostępnień — dlatego filtr autorów jest
	 * w zapytaniu feedu osobnym warunkiem.
	 */
	@Test
	void friendsShareOfBlockedAuthorsPost_staysOutOfFeed() throws Exception {
		TestUser blocker = register("block-feed-blocker@songly.io");
		TestUser friend = register("block-feed-friend@songly.io");
		TestUser troll = register("block-feed-troll@songly.io");
		befriend(blocker, friend);
		String trollPostId = publish(troll, "tresc trolla");
		block(blocker, troll);

		mockMvc.perform(
				put("/api/v1/posts/" + trollPostId + "/shares").header("Authorization", "Bearer " + friend.token()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/feed").header("Authorization", "Bearer " + blocker.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(0));
	}

	/**
	 * Społeczność jest publiczna, ale blokada to granica osobista — działa też w
	 * cudzym „domu".
	 */
	@Test
	void blockedAuthorsPost_isHiddenInsideCommunity() throws Exception {
		TestUser owner = register("block-comm-owner@songly.io");
		TestUser troll = register("block-comm-troll@songly.io");
		MvcResult created = mockMvc
				.perform(post("/api/v1/communities").header("Authorization", "Bearer " + owner.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"name":"Blokady i spolecznosci","description":"opis"}"""))
				.andExpect(status().isCreated()).andReturn();
		String communityId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + troll.token())).andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/posts")
				.header("Authorization", "Bearer " + troll.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"spam w spolecznosci"}""")).andExpect(status().isCreated());

		mockMvc.perform(
				get("/api/v1/communities/" + communityId + "/posts").header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1));

		block(owner, troll);

		mockMvc.perform(
				get("/api/v1/communities/" + communityId + "/posts").header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(0));
	}
}
