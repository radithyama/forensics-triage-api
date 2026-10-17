package com.forensics.gateway.job;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/**
 * A triage job. Persisted as {@code jobs/<id>.json} in the evidence bucket and shared with the
 * Python worker, which updates status, progress and result in the same document.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Job(
        String id,
        String filename,
        long sizeBytes,
        String md5,
        String sha256,
        String plugin,
        JobStatus status,
        int progress,
        String message,
        String createdAt,
        String updatedAt,
        JsonNode result) {

    Job withQueued(long size, String md5, String now) {
        return new Job(id, filename, size, md5, sha256, plugin, JobStatus.QUEUED, 0,
                "Queued for analysis", createdAt, now, result);
    }
}
