package com.songly.matching;

import com.songly.identity.SuspendedAccounts;
import com.songly.matching.dto.MatchResponse;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dopasowywanie po gatunkach: <b>ważony</b> współczynnik Jaccarda, liczony na
 * żądanie — bez tabeli {@code match_scores} i bez ML, dopóki nie ma danych o
 * zachowaniu userów, które by je uzasadniły (Claude.md §8).
 *
 * <p>
 * Wagą jest rzadkość gatunku (ADR 0029). Surowy Jaccard traktował „Pop", który
 * wybrała połowa serwisu, dokładnie tak samo jak „Dark Ambient" wybrany przez
 * dwie osoby — a to drugie mówi o ludziach nieporównywalnie więcej. Skutkiem
 * było to, że wszyscy z mainstreamowym gustem wyglądali na identycznie
 * dopasowanych.
 */
@Service
class MatchingService {

	private final ProfileDirectory profileDirectory;
	private final SocialGraph socialGraph;
	private final SuspendedAccounts suspendedAccounts;

	MatchingService(ProfileDirectory profileDirectory, SocialGraph socialGraph, SuspendedAccounts suspendedAccounts) {
		this.profileDirectory = profileDirectory;
		this.socialGraph = socialGraph;
		this.suspendedAccounts = suspendedAccounts;
	}

	@Transactional(readOnly = true)
	Page<MatchResponse> matches(UUID userId, Pageable pageable) {
		Set<UUID> myGenres = profileDirectory.favoriteGenreIdsByUser(Set.of(userId)).getOrDefault(userId, Set.of());
		if (myGenres.isEmpty()) {
			return Page.empty(pageable);
		}
		Set<UUID> candidateIds = new HashSet<>(profileDirectory.userIdsWithAnyGenre(myGenres));
		candidateIds.remove(userId);
		candidateIds.removeAll(socialGraph.friendIds(userId));
		candidateIds.removeAll(socialGraph.blockedEitherWayIds(userId));
		// Zawieszone konta wypadają z propozycji (ADR 0029, luka po ADR 0026):
		// dopasowywanie jest zaproszeniem do kontaktu, a proponowanie kontaktu z kimś,
		// komu platforma odebrała dostęp do usługi, jest wprost szkodliwe — zwłaszcza
		// że zawieszenie bierze się zwykle z nękania.
		candidateIds.removeAll(suspendedAccounts.suspendedUserIds());
		if (candidateIds.isEmpty()) {
			return Page.empty(pageable);
		}
		Map<UUID, Set<UUID>> genresByCandidate = profileDirectory.favoriteGenreIdsByUser(candidateIds);
		Map<UUID, Double> weights = genreWeights(genresByCandidate, myGenres);
		Map<UUID, String> names = profileDirectory.displayNames(candidateIds);
		Map<UUID, String> avatars = profileDirectory.avatarUrls(candidateIds);
		List<MatchResponse> ranked = genresByCandidate.entrySet().stream()
				.map(entry -> toMatch(entry.getKey(), entry.getValue(), myGenres, weights, names, avatars))
				.sorted(Comparator.comparingDouble(MatchResponse::score).reversed()
						.thenComparing(match -> match.userId().toString()))
				.toList();
		int total = ranked.size();
		if (pageable.isUnpaged()) {
			return new PageImpl<>(ranked, pageable, total);
		}
		int from = (int) Math.min(pageable.getOffset(), total);
		int to = Math.min(from + pageable.getPageSize(), total);
		return new PageImpl<>(ranked.subList(from, to), pageable, total);
	}

	/**
	 * Waga gatunku: {@code ln(1 + N / n)}, gdzie {@code N} to liczba userów z
	 * jakimikolwiek gatunkami, a {@code n} — liczba userów, którzy wskazali ten
	 * gatunek (ADR 0029).
	 *
	 * <p>
	 * Ten kształt jest wybrany celowo. Waga <b>nigdy nie spada do zera</b>, więc
	 * wspólny gatunek mainstreamowy nadal coś znaczy (przy {@code n = N} wychodzi
	 * {@code ln 2}), i <b>nigdy nie ucieka w nieskończoność</b>, więc jeden unikat
	 * nie przesłania całego profilu — logarytm rośnie wolno, a jedynka pod nim
	 * zabezpiecza przed {@code ln 0}. Gatunek bez ani jednego wskazania nie może
	 * pojawić się w żadnym zbiorze, więc dzielenia przez zero tu nie ma.
	 *
	 * <p>
	 * Popularność jest liczona <b>globalnie</b>, nie wśród kandydatów: „niszowy"
	 * musi znaczyć „rzadki w serwisie", inaczej waga zmieniałaby się w zależności
	 * od tego, kto pyta — a wtedy dwie osoby widziałyby różne dopasowania do siebie
	 * samych.
	 */
	private Map<UUID, Double> genreWeights(Map<UUID, Set<UUID>> genresByCandidate, Set<UUID> myGenres) {
		Set<UUID> relevantGenres = new HashSet<>(myGenres);
		genresByCandidate.values().forEach(relevantGenres::addAll);
		Map<UUID, Long> counts = profileDirectory.genreUserCounts(relevantGenres);
		long totalUsers = Math.max(1, profileDirectory.usersWithAnyGenreCount());
		return relevantGenres.stream().collect(java.util.stream.Collectors.toMap(genreId -> genreId, genreId -> {
			long pickedBy = Math.max(1, counts.getOrDefault(genreId, 1L));
			return Math.log(1.0 + (double) totalUsers / pickedBy);
		}));
	}

	/**
	 * Ważony Jaccard: suma wag części wspólnej przez sumę wag złączenia. Wynik
	 * zostaje w przedziale (0, 1] jak przy surowym Jaccardzie, więc kontrakt API
	 * się nie zmienia — zmienia się wyłącznie kolejność, i to jest cały cel.
	 *
	 * <p>
	 * Mianownik zostaje złączeniem, nie liczbą wspólnych gatunków: człowiek, który
	 * wybrał wszystko, ma być słabym dopasowaniem do każdego, bo jego zbiór nie
	 * niesie informacji o guście.
	 */
	private MatchResponse toMatch(UUID candidateId, Set<UUID> candidateGenres, Set<UUID> myGenres,
			Map<UUID, Double> weights, Map<UUID, String> names, Map<UUID, String> avatars) {
		Set<UUID> shared = new HashSet<>(candidateGenres);
		shared.retainAll(myGenres);
		Set<UUID> union = new HashSet<>(candidateGenres);
		union.addAll(myGenres);
		double score = weightOf(shared, weights) / weightOf(union, weights);
		return new MatchResponse(candidateId, names.get(candidateId), avatars.get(candidateId), score,
				shared.stream().sorted(Comparator.comparing(UUID::toString)).toList());
	}

	private double weightOf(Set<UUID> genres, Map<UUID, Double> weights) {
		return genres.stream().mapToDouble(genreId -> weights.getOrDefault(genreId, 1.0)).sum();
	}
}
