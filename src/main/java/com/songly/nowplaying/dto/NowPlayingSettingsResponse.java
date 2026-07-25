package com.songly.nowplaying.dto;

/**
 * Ustawienia widoczności presence. {@code shareWithFriends=false} znaczy, że
 * status nie trafia ani do listy znajomych, ani do strumienia SSE, ani nawet do
 * serwerowego polla.
 */
public record NowPlayingSettingsResponse(boolean shareWithFriends) {
}
