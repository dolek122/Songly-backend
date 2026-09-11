package com.songly.identity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserEmailDirectoryService implements UserEmailDirectory {

	private final UserRepository userRepository;

	UserEmailDirectoryService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> emailsOf(Collection<UUID> userIds) {
		return userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, User::getEmail));
	}
}
