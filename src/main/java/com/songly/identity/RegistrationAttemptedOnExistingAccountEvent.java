package com.songly.identity;

import java.util.UUID;

record RegistrationAttemptedOnExistingAccountEvent(UUID userId, String email) {
}
