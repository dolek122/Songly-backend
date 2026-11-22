package com.songly.identity;

import java.util.UUID;

record PasswordResetRequestedEvent(UUID userId, String email, String token) {
}
