package com.songly.content;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Klucz złożony {@link ReporterReputation}: osoba i społeczność (ADR 0027). */
class ReporterReputationId implements Serializable {

	private UUID userId;

	private UUID communityId;

	protected ReporterReputationId() {
	}

	ReporterReputationId(UUID userId, UUID communityId) {
		this.userId = userId;
		this.communityId = communityId;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ReporterReputationId that)) {
			return false;
		}
		return Objects.equals(userId, that.userId) && Objects.equals(communityId, that.communityId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(userId, communityId);
	}
}
