package com.songly.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
		matchingService = new MatchingService(profileDirectory, socialGraph, suspendedAccounts,
				new MatchingProperties());
	}

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

		assertThat(page.getContent()).hasSize(2);
		assertThat(page.getContent().get(0).userId()).isEqualTo(similar);
		assertThat(page.getContent().get(0).sharedGenreIds()).containsExactlyInAnyOrder(g1, g2);
		assertThat(page.getContent().get(1).userId()).isEqualTo(lessSimilar);
		assertThat(page.getContent().get(0).score()).isGreaterThan(page.getContent().get(1).score());
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
		assertThat(page.getContent().get(0).score()).isGreaterThan(0.0).isLessThan(1.0);
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

		Page<MatchResponse> page = matchingService.matches(me, PageRequest.of(1, 5));

		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent()).isEmpty();
		verify(profileDirectory, never()).displayNames(anyCollection());
		verify(profileDirectory, never()).avatarUrls(anyCollection());
	}

	@Test
	void matches_looksUpNamesOnlyForTheReturnedPage() {
		UUID best = UUID.randomUUID();
		UUID worse = UUID.randomUUID();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(me))).thenReturn(Map.of(me, Set.of(g1, g2)));
		when(profileDirectory.userIdsWithAnyGenre(anyCollection())).thenReturn(Set.of(best, worse));
		when(socialGraph.friendIds(me)).thenReturn(Set.of());
		when(socialGraph.blockedEitherWayIds(me)).thenReturn(Set.of());
		when(suspendedAccounts.suspendedUserIds()).thenReturn(Set.of());
		allGenresEquallyPopular();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(best, worse)))
				.thenReturn(Map.of(best, Set.of(g1, g2), worse, Set.of(g1)));
		when(profileDirectory.displayNames(Set.of(best))).thenReturn(Map.of(best, "Bono"));

		Page<MatchResponse> page = matchingService.matches(me, PageRequest.of(0, 1));

		assertThat(page.getTotalElements()).isEqualTo(2);
		assertThat(page.getContent()).extracting(MatchResponse::userId).containsExactly(best);
		assertThat(page.getContent().get(0).displayName()).isEqualTo("Bono");
		verify(profileDirectory).avatarUrls(Set.of(best));
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
		when(profileDirectory.genreUserCounts(anyCollection()))
				.thenReturn(Map.of(niche, 2L, mainstream, 900L, g3, 10L, g4, 10L));
		when(profileDirectory.usersWithAnyGenreCount()).thenReturn(1000L);
		when(profileDirectory.displayNames(anyCollection()))
				.thenReturn(Map.of(nicheTwin, "Niszowy", mainstreamTwin, "Popowy"));

		Page<MatchResponse> page = matchingService.matches(me, Pageable.unpaged());

		assertThat(page.getContent()).extracting(MatchResponse::userId).containsExactly(nicheTwin, mainstreamTwin);
		assertThat(page.getContent().get(0).score()).isGreaterThan(page.getContent().get(1).score());
		assertThat(page.getContent()).allSatisfy(match -> assertThat(match.score()).isGreaterThan(0.0).isLessThan(1.0));
	}

	@Test
	void matches_rankRicherProfileAboveTinyIdenticalOne() {
		UUID twin = UUID.randomUUID();
		UUID richCandidate = UUID.randomUUID();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(me))).thenReturn(Map.of(me, Set.of(g1)));
		when(profileDirectory.userIdsWithAnyGenre(anyCollection())).thenReturn(Set.of(me, twin, richCandidate));
		when(socialGraph.friendIds(me)).thenReturn(Set.of());
		when(socialGraph.blockedEitherWayIds(me)).thenReturn(Set.of());
		when(suspendedAccounts.suspendedUserIds()).thenReturn(Set.of());
		allGenresEquallyPopular();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(twin, richCandidate)))
				.thenReturn(Map.of(twin, Set.of(g1), richCandidate, Set.of(g1, g2, g3, g4)));
		when(profileDirectory.displayNames(anyCollection()))
				.thenReturn(Map.of(twin, "Bliźniak", richCandidate, "Bogaty"));

		Page<MatchResponse> page = matchingService.matches(me, Pageable.unpaged());

		assertThat(page.getContent()).extracting(MatchResponse::confidence)
				.allSatisfy(confidence -> assertThat(confidence).isGreaterThan(0.0).isLessThan(1.0));
		MatchResponse twinMatch = matchOf(page, twin);
		MatchResponse richMatch = matchOf(page, richCandidate);
		assertThat(richMatch.confidence()).isGreaterThan(twinMatch.confidence());
		assertThat(twinMatch.score()).isLessThan(0.6);
	}

	@Test
	void matches_confidenceFollowsHowMuchBothSidesTold() {
		UUID poorlyKnown = UUID.randomUUID();
		UUID wellKnown = UUID.randomUUID();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(me))).thenReturn(Map.of(me, Set.of(g1, g2)));
		when(profileDirectory.userIdsWithAnyGenre(anyCollection())).thenReturn(Set.of(me, poorlyKnown, wellKnown));
		when(socialGraph.friendIds(me)).thenReturn(Set.of());
		when(socialGraph.blockedEitherWayIds(me)).thenReturn(Set.of());
		when(suspendedAccounts.suspendedUserIds()).thenReturn(Set.of());
		allGenresEquallyPopular();
		when(profileDirectory.favoriteGenreIdsByUser(Set.of(poorlyKnown, wellKnown)))
				.thenReturn(Map.of(poorlyKnown, Set.of(g1), wellKnown, Set.of(g1, g3, g4)));
		when(profileDirectory.displayNames(anyCollection()))
				.thenReturn(Map.of(poorlyKnown, "Skryty", wellKnown, "Otwarty"));

		Page<MatchResponse> page = matchingService.matches(me, Pageable.unpaged());

		assertThat(matchOf(page, wellKnown).confidence()).isGreaterThan(matchOf(page, poorlyKnown).confidence());
		assertThat(matchOf(page, wellKnown).score()).isLessThan(matchOf(page, poorlyKnown).score());
	}

	private MatchResponse matchOf(Page<MatchResponse> page, UUID userId) {
		return page.getContent().stream().filter(match -> match.userId().equals(userId)).findFirst()
				.orElseThrow(() -> new AssertionError("brak dopasowania do " + userId));
	}

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
