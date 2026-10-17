package com.forensics.gateway.job;

/**
 * Pub/Sub payload. {@code generation} pins the exact object version that was uploaded, and
 * {@code md5} is GCS's server-side digest, which the worker re-verifies after download.
 */
public record JobMessage(String jobId, String plugin, String bucket, String object, long generation, String md5) {
}
