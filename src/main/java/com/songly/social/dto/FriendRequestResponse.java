package com.songly.social.dto;

import com.songly.social.FriendRequestStatus;
import java.time.Instant;
import java.util.UUID;

public record FriendRequestResponse(UUID id, UUID requesterId, String requesterDisplayName, UUID addresseeId,
		String addresseeDisplayName, FriendRequestStatus status, Instant createdAt) {
}
