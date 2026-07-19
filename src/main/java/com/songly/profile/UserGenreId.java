package com.songly.profile;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

class UserGenreId implements Serializable {

	private UUID userId;
	private UUID genreId;

	protected UserGenreId() {
	}

	UserGenreId(UUID userId, UUID genreId) {
		this.userId = userId;
		this.genreId = genreId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof UserGenreId other))
			return false;
		return Objects.equals(userId, other.userId) && Objects.equals(genreId, other.genreId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(userId, genreId);
	}
}
