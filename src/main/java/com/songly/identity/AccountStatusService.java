package com.songly.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountStatusService {

	private final UserRepository userRepository;

	AccountStatusService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Transactional(readOnly = true)
	public Optional<AccountStatus> statusOf(UUID userId) {
		Instant now = Instant.now();
		return userRepository.findById(userId).map(user -> new AccountStatus(user.isAdmin(), user.isSuspendedAt(now)));
	}
}
