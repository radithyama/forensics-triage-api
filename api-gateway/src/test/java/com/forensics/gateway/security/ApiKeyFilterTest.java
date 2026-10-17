package com.forensics.gateway.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;

import com.forensics.gateway.TriageProperties;
import com.forensics.gateway.job.JobController;
import com.forensics.gateway.job.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.unit.DataSize;

class ApiKeyFilterTest {

    static final String KEY = "Abcdefghijklmnopqrstuvwxyz0123456789";

    static TriageProperties props(String key) {
        return new TriageProperties("p", "b", "t", Duration.ofMinutes(5), DataSize.ofMegabytes(10), DataSize.ofGigabytes(6),
                List.of("https://frontend.example"), List.of("windows.pslist.PsList"), null, key);
    }

    private final WebTestClient client = WebTestClient
            .bindToController(new JobController(mock(JobService.class)))
            .webFilter(new ApiKeyFilter(provider(KEY), new RateLimiter(10, java.time.Duration.ofHours(1))))
            .build();

    @Test
    void rejectsMissingAndWrongKeys() {
        client.get().uri("/api/session").exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.message").isEqualTo("Missing or invalid X-API-Key");
        client.get().uri("/api/session").header("X-API-Key", KEY + "x").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/session").header("X-API-Key", "").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void acceptsTheConfiguredKey() {
        client.get().uri("/api/session").header("X-API-Key", KEY).exchange().expectStatus().isNoContent();
    }

    @Test
    void refusesToStartWithAWeakOrMissingKey() {
        assertThatThrownBy(() -> new ApiKeyProvider(props(""))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiKeyProvider(props("short"))).isInstanceOf(IllegalStateException.class);
    }

    static ApiKeyProvider provider(String key) {
        try {
            return new ApiKeyProvider(props(key));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
