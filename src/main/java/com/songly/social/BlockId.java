package com.songly.social;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

class BlockId implements Serializable {

	private UUID blockerId;
	private UUID blockedId;

	protected BlockId() {
	}

	BlockId(UUID blockerId, UUID blockedId) {
		this.blockerId = blockerId;
		this.blockedId = blockedId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof BlockId other)) {
			return false;
		}
		return Objects.equals(blockerId, other.blockerId) && Objects.equals(blockedId, other.blockedId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(blockerId, blockedId);
	}
}
