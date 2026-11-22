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
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(MatchingProperties.class)
class MatchingService {

	private final ProfileDirectory profileDirectory;
	private final SocialGraph socialGraph;
	private final SuspendedAccounts suspendedAccounts;
	private final MatchingProperties properties;

	MatchingService(ProfileDirectory profileDirectory, SocialGraph socialGraph, SuspendedAccounts suspendedAccounts,
			MatchingProperties properties) {
		this.profileDirectory = profileDirectory;
		this.socialGraph = socialGraph;
		this.suspendedAccounts = suspendedAccounts;
		this.properties = properties;
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
		candidateIds.removeAll(suspendedAccounts.suspendedUserIds());
		if (candidateIds.isEmpty()) {
			return Page.empty(pageable);
		}
		Map<UUID, Set<UUID>> genresByCandidate = profileDirectory.favoriteGenreIdsByUser(candidateIds);
		Map<UUID, Double> weights = genreWeights(genresByCandidate, myGenres);
		List<ScoredCandidate> ranked = genresByCandidate.entrySet().stream()
				.map(entry -> score(entry.getKey(), entry.getValue(), myGenres, weights))
				.sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed()
						.thenComparing(candidate -> candidate.userId().toString()))
				.toList();
		int total = ranked.size();
		List<ScoredCandidate> slice = ranked;
		if (pageable.isPaged()) {
			int from = (int) Math.min(pageable.getOffset(), total);
			int to = Math.min(from + pageable.getPageSize(), total);
			slice = ranked.subList(from, to);
		}
		return new PageImpl<>(withProfiles(slice), pageable, total);
	}

	private List<MatchResponse> withProfiles(List<ScoredCandidate> slice) {
		if (slice.isEmpty()) {
			return List.of();
		}
		Set<UUID> userIds = slice.stream().map(ScoredCandidate::userId).collect(Collectors.toSet());
		Map<UUID, String> names = profileDirectory.displayNames(userIds);
		Map<UUID, String> avatars = profileDirectory.avatarUrls(userIds);
		return slice.stream()
				.map(candidate -> new MatchResponse(candidate.userId(), names.get(candidate.userId()),
						avatars.get(candidate.userId()), candidate.score(), candidate.confidence(),
						candidate.sharedGenreIds()))
				.toList();
	}

	private record ScoredCandidate(UUID userId, double score, double confidence, List<UUID> sharedGenreIds) {
	}

	private Map<UUID, Double> genreWeights(Map<UUID, Set<UUID>> genresByCandidate, Set<UUID> myGenres) {
		Set<UUID> relevantGenres = new HashSet<>(myGenres);
		genresByCandidate.values().forEach(relevantGenres::addAll);
		Map<UUID, Long> counts = profileDirectory.genreUserCounts(relevantGenres);
		long totalUsers = Math.max(1, profileDirectory.usersWithAnyGenreCount());
		return relevantGenres.stream().collect(Collectors.toMap(genreId -> genreId, genreId -> {
			long pickedBy = Math.max(1, counts.getOrDefault(genreId, 1L));
			return Math.log(1.0 + (double) totalUsers / pickedBy);
		}));
	}

	private ScoredCandidate score(UUID candidateId, Set<UUID> candidateGenres, Set<UUID> myGenres,
			Map<UUID, Double> weights) {
		Set<UUID> shared = new HashSet<>(candidateGenres);
		shared.retainAll(myGenres);
		Set<UUID> union = new HashSet<>(candidateGenres);
		union.addAll(myGenres);
		double unionWeight = weightOf(union, weights);
		double damped = unionWeight + properties.getConfidencePrior();
		double score = weightOf(shared, weights) / damped;
		double confidence = unionWeight / damped;
		return new ScoredCandidate(candidateId, score, confidence,
				shared.stream().sorted(Comparator.comparing(UUID::toString)).toList());
	}

	private double weightOf(Set<UUID> genres, Map<UUID, Double> weights) {
		return genres.stream().mapToDouble(genreId -> weights.getOrDefault(genreId, 1.0)).sum();
	}
}
