package com.songly.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Cache popularności gatunków (ADR 0032) — przez prawdziwy Redis i prawdziwą
 * bazę.
 *
 * <p>
 * Test potrzebuje odróżnić „policzone z bazy" od „wzięte z cache'a", więc
 * zmienia liczby <b>obok</b> aplikacji, zapytaniem SQL. To jedyny sposób: każda
 * ścieżka aplikacji, która rusza {@code user_genres}, sama unieważnia obraz,
 * więc przez API nie da się doprowadzić do rozjazdu, który tu sprawdzamy.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class GenrePopularityCacheIntegrationTest extends AbstractIntegrationTest {

	/** Metal — gatunek z seeda V2, którego nie tyka żaden inny test. */
	private static final UUID GENRE_METAL = UUID.fromString("a5a9f290-1111-4a2e-8b1a-000000000008");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ProfileDirectory profileDirectory;

	private record TestUser(String token, UUID userId) {
	}

	private TestUser user(String email) throws Exception {
		String token = objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
		UUID userId = UUID.fromString(objectMapper
				.readTree(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("userId").asText());
		return new TestUser(token, userId);
	}

	/** Ustawia gatunki przez API — czyli ścieżką, która unieważnia cache. */
	private void setMetalViaApi(TestUser user) throws Exception {
		mockMvc.perform(put("/api/v1/profiles/me/genres").header("Authorization", "Bearer " + user.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"genreIds":["%s"]}""".formatted(GENRE_METAL))).andExpect(status().isNoContent());
	}

	/** Dopisuje ulubiony gatunek obok aplikacji — baza wie, cache nie. */
	private void addFavoriteBehindTheApplication(TestUser user) {
		jdbcTemplate.update("INSERT INTO user_genres (user_id, genre_id, created_at) VALUES (?, ?, now())",
				user.userId(), GENRE_METAL);
	}

	private long metalCount() {
		return profileDirectory.genreUserCounts(Set.of(GENRE_METAL)).getOrDefault(GENRE_METAL, 0L);
	}

	/**
	 * Sedno ADR 0032 w obie strony: dopóki nikt nie zmienia gatunków, liczby idą z
	 * cache'a (więc wiersz dopisany obok aplikacji jest niewidoczny), a pierwsza
	 * zmiana przez API unieważnia obraz i liczby doganiają bazę.
	 */
	@Test
	void popularityIsCachedUntilFavoriteGenresChange() throws Exception {
		TestUser owner = user("genrecache-owner@songly.io");
		TestUser bystander = user("genrecache-bystander@songly.io");
		// Ustawienie przez API unieważnia cache, więc następny odczyt liczy z bazy i
		// zapisuje świeży obraz — to jest punkt startowy pomiaru.
		setMetalViaApi(owner);
		long before = metalCount();
		assertThat(before).isPositive();

		addFavoriteBehindTheApplication(bystander);

		assertThat(metalCount()).isEqualTo(before);

		// Ta sama wartość gatunków przez API — nie zmienia liczb, ale unieważnia obraz.
		setMetalViaApi(owner);

		assertThat(metalCount()).isEqualTo(before + 1);
	}

	/**
	 * Mianownik wagi (ADR 0029) siedzi w tym samym wpisie co popularność, więc
	 * podlega dokładnie temu samemu unieważnianiu — inaczej {@code ln(1 + N/n)}
	 * liczyłoby się z liczb z dwóch różnych momentów.
	 */
	@Test
	void totalUserCountComesFromTheSameSnapshot() throws Exception {
		TestUser owner = user("genrecache-total@songly.io");
		TestUser bystander = user("genrecache-total-bystander@songly.io");
		setMetalViaApi(owner);
		long before = profileDirectory.usersWithAnyGenreCount();
		assertThat(before).isPositive();

		addFavoriteBehindTheApplication(bystander);

		assertThat(profileDirectory.usersWithAnyGenreCount()).isEqualTo(before);

		setMetalViaApi(owner);

		assertThat(profileDirectory.usersWithAnyGenreCount()).isEqualTo(before + 1);
	}
}
