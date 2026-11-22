package com.songly.social;

import java.util.Set;
import java.util.UUID;

public interface SocialGraph {

	Set<UUID> friendIds(UUID userId);

	boolean isBlockedEitherWay(UUID userId, UUID otherUserId);

	Set<UUID> blockedEitherWayIds(UUID userId);
}
