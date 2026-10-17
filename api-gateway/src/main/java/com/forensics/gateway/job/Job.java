package com.forensics.gateway.job;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/** Public view of a triage job, as returned by the REST API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Job(
        String id,
        String filename,
        long sizeBytes,
        String sha256,
        String plugin,
        JobStatus status,
        int progress,
        String message,
        String createdAt,
        String updatedAt,
        JsonNode result) {
}
