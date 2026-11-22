package com.songly.identity.dto;

import com.songly.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(@NotBlank String currentPassword,
		@NotBlank @Size(min = 8, max = 72) @MaxUtf8Bytes(72) String newPassword) {
}
