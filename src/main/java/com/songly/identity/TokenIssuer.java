package com.songly.identity;

import com.songly.identity.dto.AuthTokensResponse;
import com.songly.identity.security.JwtService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class TokenIssuer {

	private final SessionRepository sessionRepository;
	private final JwtService jwtService;

	TokenIssuer(SessionRepository sessionRepository, JwtService jwtService) {
		this.sessionRepository = sessionRepository;
		this.jwtService = jwtService;
	}

	AuthTokensResponse issueFor(User user) {
		String accessToken = jwtService.generateAccessToken(user.getId());
		String refreshToken = SecureTokens.generate();
		Instant now = Instant.now();
		Session session = new Session(UUID.randomUUID(), user.getId(), SecureTokens.hash(refreshToken), now,
				now.plus(jwtService.getRefreshTokenTtl()));
		sessionRepository.save(session);
		return new AuthTokensResponse(accessToken, refreshToken, "Bearer", jwtService.getAccessTokenTtl().toSeconds());
	}
}
