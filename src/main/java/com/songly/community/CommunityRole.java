package com.songly.community;

import java.util.Set;

public enum CommunityRole {

	OWNER,

	MODERATOR,

	MEMBER;

	static final Set<CommunityRole> MODERATING = Set.of(OWNER, MODERATOR);

	boolean canModerate() {
		return MODERATING.contains(this);
	}
}
