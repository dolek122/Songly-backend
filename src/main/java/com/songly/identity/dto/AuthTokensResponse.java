package com.songly.identity.dto;

public record AuthTokensResponse(String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {
}
