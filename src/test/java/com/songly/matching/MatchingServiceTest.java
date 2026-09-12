package com.songly.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import com.songly.identity.SuspendedAccounts;
import com.songly.matching.dto.MatchResponse;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

	@Mock
	private ProfileDirectory profileDirectory;

	@Mock
	private SocialGraph socialGraph;

	@Mock
	private SuspendedAccounts suspendedAccounts;

	private MatchingService matchingService;

	private final UUID me = UUID.randomUUID();
	private final UUID g1 = UUID.randomUUID();
	private final UUID g2 = UUID.randomUUID();
	private final UUID g3 = UUID.randomUUID();
	private final UUID g4 = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		matchingService = new MatchingService(profileDirectory, socialGraph, suspendedAccounts);
	}

	/**
	 * Wszystkie gatunki równie popularne — wagi się wtedy skracają i ważony Jaccard
	 * daje dokładnie to samo co surowy. Pomocnik pozwala testom sprawdzającym inne
	 * rzeczy zostać przy dawnych, czytelnych liczbach.
	 */
	private void allGenresEquallyPopular() {
		when(profileDirectory.genreUserCounts(anyCollection()))
				.thenAnswer(invocation -> ((java.util.Collection<?>) invocation.getArgument(0)).stream()
						.collect(java.util.stream.Collectors.toMap(genreId -> (UUID) genreId, genreId -> 10L)));
		when(profileDirectory.usersWithAnyGenreCount()).thenReturn(100L);
	}

	@Test
	void matches_returnsEmpty_whenViewerHasNoGenres() {
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(me))).thenReturn(Map.of());

		Page<MatchResponse> page = matchingService.matches(me, Pageable.unpaged());

		assertThat(page.getContent()).isEmpty();
	}

	@Test
	void matches_ranksByJaccardScore() {
		UUID similar = UUID.randomUUID();
		UUID lessSimilar = UUID.randomUUID();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(me))).thenReturn(Map.of(me, Set.of(g1, g2, g3)));
		when(profileDirectory.userIdsWithAnyGenre(anyCollection())).thenReturn(Set.of(me, similar, lessSimilar));
		when(socialGraph.friendIds(me)).thenReturn(Set.of());
		when(socialGraph.blockedEitherWayIds(me)).thenReturn(Set.of());
		when(suspendedAccounts.suspendedUserIds()).thenReturn(Set.of());
		allGenresEquallyPopular();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(similar, lessSimilar)))
				.thenReturn(Map.of(similar, Set.of(g1, g2, g4), lessSimilar, Set.of(g3)));
		when(profileDirectory.displayNames(anyCollection())).thenReturn(Map.of(similar, "Bono", lessSimilar, "Carol"));

		Page<MatchResponse> page = matchingService.matches(me, Pageable.unpaged());

		// similar: |{g1,g2}| / |{g1,g2,g3,g4}| = 0.5; lessSimilar: |{g3}| /
		// |{g1,g2,g3}| = 1/3
		assertThat(page.getContent()).hasSize(2);
		assertThat(page.getContent().get(0).userId()).isEqualTo(similar);
		assertThat(page.getContent().get(0).score()).isEqualTo(0.5);
		assertThat(page.getContent().get(0).sharedGenreIds()).containsExactlyInAnyOrder(g1, g2);
		assertThat(page.getContent().get(1).userId()).isEqualTo(lessSimilar);
	}

	@Test
	void matches_excludesFriendsAndBlockedUsers() {
		UUID friend = UUID.randomUUID();
		UUID blocked = UUID.randomUUID();
		UUID candidate = UUID.randomUUID();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(me))).thenReturn(Map.of(me, Set.of(g1)));
		when(profileDirectory.userIdsWithAnyGenre(anyCollection())).thenReturn(Set.of(me, friend, blocked, candidate));
		when(socialGraph.friendIds(me)).thenReturn(Set.of(friend));
		when(socialGraph.blockedEitherWayIds(me)).thenReturn(Set.of(blocked));
		when(suspendedAccounts.suspendedUserIds()).thenReturn(Set.of());
		allGenresEquallyPopular();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(candidate))).thenReturn(Map.of(candidate, Set.of(g1)));
		when(profileDirectory.displayNames(anyCollection())).thenReturn(Map.of(candidate, "Dave"));

		Page<MatchResponse> page = matchingService.matches(me, Pageable.unpaged());

		assertThat(page.getContent()).extracting(MatchResponse::userId).containsExactly(candidate);
		assertThat(page.getContent().get(0).score()).isEqualTo(1.0);
	}

	@Test
	void matches_paginatesRankedResults() {
		UUID other = UUID.randomUUID();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(me))).thenReturn(Map.of(me, Set.of(g1)));
		when(profileDirectory.userIdsWithAnyGenre(anyCollection())).thenReturn(Set.of(other));
		when(socialGraph.friendIds(me)).thenReturn(Set.of());
		when(socialGraph.blockedEitherWayIds(me)).thenReturn(Set.of());
		when(suspendedAccounts.suspendedUserIds()).thenReturn(Set.of());
		allGenresEquallyPopular();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(other))).thenReturn(Map.of(other, Set.of(g1, g2)));
		when(profileDirectory.displayNames(anyCollection())).thenReturn(Map.of(other, "Bono"));

		Page<MatchResponse> page = matchingService.matches(me, PageRequest.of(1, 5));

		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent()).isEmpty();
	}

	@Test
	void matches_returnsEmpty_whenOnlyCandidateIsViewer() {
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(me))).thenReturn(Map.of(me, Set.of(g1)));
		when(profileDirectory.userIdsWithAnyGenre(anyCollection())).thenReturn(Set.of(me));
		when(socialGraph.friendIds(me)).thenReturn(Set.of());
		when(socialGraph.blockedEitherWayIds(me)).thenReturn(Set.of());
		when(suspendedAccounts.suspendedUserIds()).thenReturn(Set.of());

		Page<MatchResponse> page = matchingService.matches(me, Pageable.unpaged());

		assertThat(page.getContent()).isEmpty();
	}

	/**
	 * Sedno ADR 0029: przy <b>tej samej</b> liczbie wspólnych gatunków i tym samym
	 * rozmiarze zbiorów wyżej trafia ten, z którym dzielimy gatunek <b>niszowy</b>.
	 * Surowy Jaccard dawał tu remis, więc kolejność rozstrzygał identyfikator.
	 */
	@Test
	void matches_ranksRareSharedGenreAboveMainstreamOne() {
		UUID nicheTwin = UUID.randomUUID();
		UUID mainstreamTwin = UUID.randomUUID();
		UUID niche = g1;
		UUID mainstream = g2;
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(me))).thenReturn(Map.of(me, Set.of(niche, mainstream)));
		when(profileDirectory.userIdsWithAnyGenre(anyCollection())).thenReturn(Set.of(me, nicheTwin, mainstreamTwin));
		when(socialGraph.friendIds(me)).thenReturn(Set.of());
		when(socialGraph.blockedEitherWayIds(me)).thenReturn(Set.of());
		when(suspendedAccounts.suspendedUserIds()).thenReturn(Set.of());
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(nicheTwin, mainstreamTwin)))
				.thenReturn(Map.of(nicheTwin, Set.of(niche, g3), mainstreamTwin, Set.of(mainstream, g4)));
		// „Dark Ambient" wybrały 2 osoby, „Pop" — 900 z tysiąca; g3 i g4 mają po 10.
		when(profileDirectory.genreUserCounts(anyCollection()))
				.thenReturn(Map.of(niche, 2L, mainstream, 900L, g3, 10L, g4, 10L));
		when(profileDirectory.usersWithAnyGenreCount()).thenReturn(1000L);
		when(profileDirectory.displayNames(anyCollection()))
				.thenReturn(Map.of(nicheTwin, "Niszowy", mainstreamTwin, "Popowy"));

		Page<MatchResponse> page = matchingService.matches(me, Pageable.unpaged());

		assertThat(page.getContent()).extracting(MatchResponse::userId).containsExactly(nicheTwin, mainstreamTwin);
		assertThat(page.getContent().get(0).score()).isGreaterThan(page.getContent().get(1).score());
		// Wynik zostaje w (0, 1] — kontrakt API się nie zmienia, zmienia się kolejność.
		assertThat(page.getContent())
				.allSatisfy(match -> assertThat(match.score()).isGreaterThan(0.0).isLessThanOrEqualTo(1.0));
	}

	/**
	 * Zawieszone konta nie są proponowane (ADR 0029): dopasowanie jest zaproszeniem
	 * do kontaktu, a zawieszenie bierze się zwykle z nękania.
	 */
	@Test
	void matches_excludesSuspendedAccounts() {
		UUID suspended = UUID.randomUUID();
		UUID candidate = UUID.randomUUID();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(me))).thenReturn(Map.of(me, Set.of(g1)));
		when(profileDirectory.userIdsWithAnyGenre(anyCollection())).thenReturn(Set.of(me, suspended, candidate));
		when(socialGraph.friendIds(me)).thenReturn(Set.of());
		when(socialGraph.blockedEitherWayIds(me)).thenReturn(Set.of());
		when(suspendedAccounts.suspendedUserIds()).thenReturn(Set.of(suspended));
		allGenresEquallyPopular();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(candidate))).thenReturn(Map.of(candidate, Set.of(g1)));
		when(profileDirectory.displayNames(anyCollection())).thenReturn(Map.of(candidate, "Ewa"));

		Page<MatchResponse> page = matchingService.matches(me, Pageable.unpaged());

		assertThat(page.getContent()).extracting(MatchResponse::userId).containsExactly(candidate);
	}

}
