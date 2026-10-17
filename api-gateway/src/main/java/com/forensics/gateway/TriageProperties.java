package com.forensics.gateway;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("triage")
public record TriageProperties(
        String projectId,
        String bucket,
        String topic,
        Duration uploadUrlTtl,
        DataSize maxUploadSize,
        List<String> allowedOrigins,
        List<String> allowedPlugins) {

    public static final String DEFAULT_PLUGIN = "windows.pslist.PsList";
}
