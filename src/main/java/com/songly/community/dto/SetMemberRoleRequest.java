package com.songly.community.dto;

import com.songly.community.CommunityRole;
import jakarta.validation.constraints.NotNull;

public record SetMemberRoleRequest(@NotNull CommunityRole role) {
}
