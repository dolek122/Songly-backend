package com.songly.community;

import java.util.Optional;
import java.util.UUID;

public interface CommunityDirectory {

	Optional<String> nameOf(UUID communityId);
}
