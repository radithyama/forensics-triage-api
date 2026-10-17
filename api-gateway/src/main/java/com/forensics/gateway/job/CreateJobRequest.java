package com.forensics.gateway.job;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateJobRequest(@NotBlank String filename, @Positive long sizeBytes, String plugin) {
}
