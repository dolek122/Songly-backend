package com.songly.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "friend_requests")
class FriendRequest {

	@Id
	private UUID id;

	@Column(name = "requester_id", nullable = false)
	private UUID requesterId;

	@Column(name = "addressee_id", nullable = false)
	private UUID addresseeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private FriendRequestStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "responded_at")
	private Instant respondedAt;

	protected FriendRequest() {
	}

	FriendRequest(UUID id, UUID requesterId, UUID addresseeId, Instant createdAt) {
		this.id = id;
		this.requesterId = requesterId;
		this.addresseeId = addresseeId;
		this.status = FriendRequestStatus.PENDING;
		this.createdAt = createdAt;
	}

	void accept(Instant respondedAt) {
		this.status = FriendRequestStatus.ACCEPTED;
		this.respondedAt = respondedAt;
	}

	void decline(Instant respondedAt) {
		this.status = FriendRequestStatus.DECLINED;
		this.respondedAt = respondedAt;
	}

	UUID getId() {
		return id;
	}

	UUID getRequesterId() {
		return requesterId;
	}

	UUID getAddresseeId() {
		return addresseeId;
	}

	FriendRequestStatus getStatus() {
		return status;
	}

	Instant getCreatedAt() {
		return createdAt;
	}
}
