package com.forensics.gateway.job;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class JobController {

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    // ---------- Public API (proxied to the browser) ----------

    @PostMapping(path = "/api/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Job>> upload(ServerWebExchange exchange) {
        return exchange.getMultipartData().flatMap(parts -> {
            Part file = parts.getFirst("file");
            if (!(file instanceof FilePart filePart)) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Multipart field 'file' is required"));
            }
            String plugin = parts.getFirst("plugin") instanceof FormFieldPart field ? field.value() : null;
            return jobs.submit(filePart, plugin);
        }).map(job -> ResponseEntity.status(HttpStatus.ACCEPTED).body(job));
    }

    @GetMapping("/api/jobs/{id}")
    public Mono<Job> get(@PathVariable String id) {
        return jobs.find(id);
    }

    // ---------- Worker API (token protected, not exposed through the frontend proxy) ----------

    @GetMapping("/internal/jobs/{id}/evidence")
    public Mono<ResponseEntity<Resource>> evidence(@PathVariable String id) {
        return jobs.evidencePath(id).map(path -> ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path)));
    }

    @PutMapping("/internal/jobs/{id}/status")
    public Mono<Job> updateStatus(@PathVariable String id, @Validated @RequestBody StatusUpdate update) {
        return jobs.applyUpdate(id, update);
    }
}
