package com.songly.identity;

record RegistrationVerificationRequestedEvent(String email, String token) {
}
