package com.songly.nowplaying.dto;

import java.util.UUID;

public record FriendNowPlayingResponse(UUID userId, String displayName, String avatarUrl,
		NowPlayingStatusResponse status) {
}
