package com.songly.identity.dto;

import java.util.List;
import java.util.UUID;

public record AdminListResponse(List<UUID> userIds) {
}
