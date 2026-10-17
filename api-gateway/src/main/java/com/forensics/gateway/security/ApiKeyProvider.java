package com.forensics.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.forensics.gateway.TriageProperties;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds the access code. In production it is fetched once from Secret Manager at startup
 * ({@code API_KEY_SECRET=projects/../secrets/triage-api-key/versions/latest}); startup fails if it
 * can't be read, so the API never runs unauthenticated.
 */
@Component
public class ApiKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyProvider.class);
    private static final int MIN_LENGTH = 24;

    private final byte[] key;

    public ApiKeyProvider(TriageProperties props) throws Exception {
        String value;
        if (props.apiKeySecret() != null && !props.apiKeySecret().isBlank()) {
            try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
                value = client.accessSecretVersion(props.apiKeySecret()).getPayload().getData().toStringUtf8().trim();
            }
            log.info("Loaded API key from Secret Manager ({})", props.apiKeySecret().replaceAll("/versions/.*", ""));
        } else {
            value = props.apiKey() == null ? "" : props.apiKey().trim();
            log.warn("Using API key from configuration; use API_KEY_SECRET in production");
        }
        if (value.length() < MIN_LENGTH) {
            throw new IllegalStateException("API key missing or shorter than " + MIN_LENGTH + " characters");
        }
        this.key = value.getBytes(StandardCharsets.UTF_8);
    }

    /** Constant-time comparison so response timing reveals nothing about the key. */
    public boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(key, candidate.getBytes(StandardCharsets.UTF_8));
    }
}
