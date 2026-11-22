package com.songly.profile;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ProfileDirectory {

	Map<UUID, String> displayNames(Collection<UUID> userIds);

	Map<UUID, String> avatarUrls(Collection<UUID> userIds);

	Set<UUID> userIdsWithAnyGenre(Collection<UUID> genreIds);

	Map<UUID, Set<UUID>> favoriteGenreIdsByUser(Collection<UUID> userIds);

	Map<UUID, Long> genreUserCounts(Collection<UUID> genreIds);

	long usersWithAnyGenreCount();
}
