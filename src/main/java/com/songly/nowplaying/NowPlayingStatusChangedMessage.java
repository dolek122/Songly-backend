package com.songly.nowplaying;

import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import java.util.UUID;

record NowPlayingStatusChangedMessage(UUID userId, NowPlayingStatusResponse status) {
}
