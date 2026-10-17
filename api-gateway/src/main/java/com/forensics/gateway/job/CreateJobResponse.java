package com.forensics.gateway.job;

import java.util.Map;

/** The client PUTs the evidence to {@code uploadUrl} with exactly {@code uploadHeaders}, then calls start. */
public record CreateJobResponse(Job job, String uploadUrl, String uploadMethod, Map<String, String> uploadHeaders) {
}
