package com.forensics.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.forensics.gateway.job.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@SpringBootTest(properties = {"triage.api-key=abcdefghijklmnopqrstuvwxyz0123456789", "triage.bucket=b"})
class SafeErrorAttributesTest {

    private static final String KEY = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String ID = "3f6c1e0e-8a1d-4c55-9a0b-2c3d4e5f6a7b";

    @Autowired
    ApplicationContext context;

    WebTestClient client;

    @MockitoBean
    JobService jobs;

    @MockitoBean
    com.google.cloud.storage.Storage storage;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Test
    void hidesInternalDetailsOnServerErrors() {
        when(jobs.find(any())).thenReturn(Mono.error(new IllegalStateException("POST https://storage.googleapis.com/b/secret-bucket")));
        client.get().uri("/api/jobs/" + ID).header("X-API-Key", KEY).exchange()
                .expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.message").isEqualTo("Internal error");
    }

    @Test
    void keepsOurOwnClientErrorMessages() {
        when(jobs.find(any())).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found")));
        client.get().uri("/api/jobs/" + ID).header("X-API-Key", KEY).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.message").isEqualTo("Job not found");
    }
}
