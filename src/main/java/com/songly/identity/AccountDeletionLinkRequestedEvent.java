package com.songly.identity;

import java.util.UUID;

record AccountDeletionLinkRequestedEvent(UUID userId, String email, String token) {
}
