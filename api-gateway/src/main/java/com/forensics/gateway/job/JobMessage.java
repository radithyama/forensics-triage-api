package com.forensics.gateway.job;

/** Payload published to the Redis work queue. */
public record JobMessage(String jobId, String plugin, String filename, String sha256) {
}
