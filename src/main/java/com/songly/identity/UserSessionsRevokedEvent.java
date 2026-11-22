package com.songly.identity;

import java.util.UUID;

public record UserSessionsRevokedEvent(UUID userId) {
}
