package com.songly.matching;

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
 * MVP dopasowywania (Claude.md §8): współczynnik Jaccarda na ulubionych
 * gatunkach, liczony na żądanie — bez tabeli {@code match_scores} i bez ML,
 * dopóki nie ma danych o zachowaniu userów, które by to uzasadniły. Kandydaci
 * to wyłącznie użytkownicy dzielący co najmniej jeden gatunek, więc zapytania
 * pozostają tanie.
 */
@Service
class MatchingService {

	private final ProfileDirectory profileDirectory;
	private final SocialGraph socialGraph;

	MatchingService(ProfileDirectory profileDirectory, SocialGraph socialGraph) {
		this.profileDirectory = profileDirectory;
		this.socialGraph = socialGraph;
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
		if (candidateIds.isEmpty()) {
			return Page.empty(pageable);
		}
		Map<UUID, Set<UUID>> genresByCandidate = profileDirectory.favoriteGenreIdsByUser(candidateIds);
		Map<UUID, String> names = profileDirectory.displayNames(candidateIds);
		Map<UUID, String> avatars = profileDirectory.avatarUrls(candidateIds);
		List<MatchResponse> ranked = genresByCandidate.entrySet().stream()
				.map(entry -> toMatch(entry.getKey(), entry.getValue(), myGenres, names, avatars))
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

	private MatchResponse toMatch(UUID candidateId, Set<UUID> candidateGenres, Set<UUID> myGenres,
			Map<UUID, String> names, Map<UUID, String> avatars) {
		Set<UUID> shared = new HashSet<>(candidateGenres);
		shared.retainAll(myGenres);
		Set<UUID> union = new HashSet<>(candidateGenres);
		union.addAll(myGenres);
		double score = (double) shared.size() / union.size();
		return new MatchResponse(candidateId, names.get(candidateId), avatars.get(candidateId), score,
				shared.stream().sorted(Comparator.comparing(UUID::toString)).toList());
	}
}
