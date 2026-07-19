package com.songly.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "genres")
class Genre {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true, length = 100)
	private String name;

	protected Genre() {
	}

	Genre(UUID id, String name) {
		this.id = id;
		this.name = name;
	}

	UUID getId() {
		return id;
	}

	String getName() {
		return name;
	}
}
