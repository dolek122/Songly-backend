package com.songly.moderationlog;

import com.songly.community.CommunityMembership;
import com.songly.moderationlog.dto.ModerationLogEntryResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ModerationLogService {

	private final ModerationLogRepository repository;
	private final CommunityMembership communityMembership;

	ModerationLogService(ModerationLogRepository repository, CommunityMembership communityMembership) {
		this.repository = repository;
		this.communityMembership = communityMembership;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void record(ModerationAction action, UUID communityId, UUID subjectId, UUID actorId, Instant expiresAt,
			Integer reportCount, Instant occurredAt) {
		repository.save(new ModerationLogEntry(UUID.randomUUID(), action, communityId, subjectId, actorId, expiresAt,
				reportCount, occurredAt));
	}

	@Transactional(readOnly = true)
	Page<ModerationLogEntryResponse> communityLog(UUID communityId, UUID requesterId, Pageable pageable) {
		if (!communityMembership.exists(communityId)) {
			throw new CommunityLogNotFoundException();
		}
		if (!communityMembership.canModerate(communityId, requesterId)) {
			throw new NotCommunityModeratorException();
		}
		return repository.findByCommunityIdOrderByOccurredAtDesc(communityId, pageable)
				.map(ModerationLogService::toResponse);
	}

	@Transactional(readOnly = true)
	Page<ModerationLogEntryResponse> platformLog(UUID subjectId, Pageable pageable) {
		Page<ModerationLogEntry> page = subjectId == null
				? repository.findByOrderByOccurredAtDesc(pageable)
				: repository.findBySubjectIdOrderByOccurredAtDesc(subjectId, pageable);
		return page.map(ModerationLogService::toResponse);
	}

	@Transactional
	void deleteAccountData(UUID userId) {
		repository.deleteBySubjectId(userId);
		repository.clearActor(userId);
	}

	@Transactional
	void deleteCommunityData(UUID communityId) {
		repository.deleteByCommunityId(communityId);
	}

	private static ModerationLogEntryResponse toResponse(ModerationLogEntry entry) {
		return new ModerationLogEntryResponse(entry.getAction(), entry.getCommunityId(), entry.getSubjectId(),
				entry.getActorId(), entry.getExpiresAt(), entry.getReportCount(), entry.getOccurredAt());
	}
}
