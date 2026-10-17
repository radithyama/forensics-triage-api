package com.forensics.gateway.job;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Progress report pushed by the forensic worker. */
public record StatusUpdate(
        @NotNull JobStatus status,
        @Min(0) @Max(100) int progress,
        @Size(max = 2000) String message,
        JsonNode result) {
}
