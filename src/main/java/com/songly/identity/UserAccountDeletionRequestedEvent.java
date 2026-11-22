package com.songly.identity;

import java.util.UUID;

public record UserAccountDeletionRequestedEvent(UUID userId) {
}
