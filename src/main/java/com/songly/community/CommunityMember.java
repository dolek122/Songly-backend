package com.songly.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "community_members")
@IdClass(CommunityMemberId.class)
class CommunityMember {

	@Id
	@Column(name = "community_id")
	private UUID communityId;

	@Id
	@Column(name = "user_id")
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private CommunityRole role;

	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt;

	protected CommunityMember() {
	}

	CommunityMember(UUID communityId, UUID userId, CommunityRole role, Instant joinedAt) {
		this.communityId = communityId;
		this.userId = userId;
		this.role = role;
		this.joinedAt = joinedAt;
	}

	UUID getCommunityId() {
		return communityId;
	}

	UUID getUserId() {
		return userId;
	}

	CommunityRole getRole() {
		return role;
	}

	Instant getJoinedAt() {
		return joinedAt;
	}
}
