package com.songly.identity;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Jedyny punkt, przez który pakiet {@code security} (JwtAuthenticationFilter)
 * może sprawdzić uprawnienia admina — bez dostępu do encji User ani
 * repozytorium (Claude.md §2: encje JPA nie wychodzą poza moduł).
 */
@Service
public class AdminStatusService {

	private final UserRepository userRepository;

	AdminStatusService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	public boolean isAdmin(UUID userId) {
		return userRepository.existsByIdAndAdminTrue(userId);
	}
}
