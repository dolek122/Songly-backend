package com.songly.community;

import java.util.Set;
import java.util.UUID;

public interface CommunityMembership {

	boolean exists(UUID communityId);

	boolean isMember(UUID communityId, UUID userId);

	boolean isOwner(UUID communityId, UUID userId);

	boolean canModerate(UUID communityId, UUID userId);

	boolean isBanned(UUID communityId, UUID userId);

	Set<UUID> moderatorIds(UUID communityId);
}
