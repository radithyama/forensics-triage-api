package com.forensics.gateway;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("triage")
public record TriageProperties(
        Path uploadDir,
        String queueKey,
        Duration jobTtl,
        String workerToken,
        List<String> allowedOrigins,
        List<String> allowedPlugins) {

    public static final String DEFAULT_PLUGIN = "windows.pslist.PsList";
}
