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
        DataSize minUploadSize,
        DataSize maxUploadSize,
        List<String> allowedOrigins,
        List<String> allowedPlugins,
        String apiKeySecret,
        String apiKey) {

    public static final String DEFAULT_PLUGIN = "windows.pslist.PsList";
    public static final Duration MAX_UPLOAD_URL_TTL = Duration.ofMinutes(5);

    public TriageProperties {
        if (uploadUrlTtl == null || uploadUrlTtl.isNegative() || uploadUrlTtl.isZero() || uploadUrlTtl.compareTo(MAX_UPLOAD_URL_TTL) > 0) {
            throw new IllegalArgumentException("triage.upload-url-ttl must be between 1s and 5m, was " + uploadUrlTtl);
        }
        if (minUploadSize == null || maxUploadSize == null || minUploadSize.compareTo(maxUploadSize) > 0) {
            throw new IllegalArgumentException("triage.min-upload-size must not exceed triage.max-upload-size");
        }
    }
}
