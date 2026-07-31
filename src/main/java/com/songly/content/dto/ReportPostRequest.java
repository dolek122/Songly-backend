package com.songly.content.dto;

import com.songly.content.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReportPostRequest(@NotNull ReportReason reason, @Size(max = 500) String details) {
}
