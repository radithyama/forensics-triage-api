package com.forensics.gateway.job;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class JobController {

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    /** Step 1: register the job and get a signed upload URL. */
    @PostMapping("/api/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CreateJobResponse> create(@Validated @RequestBody CreateJobRequest request) {
        return jobs.create(request);
    }

    /** Step 2: after the upload completes, queue the job for analysis. */
    @PostMapping("/api/jobs/{id}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Job> start(@PathVariable String id) {
        return jobs.start(id);
    }

    /** Lets the UI check an access code: reaching this handler means the API key filter accepted it. */
    @GetMapping("/api/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> session() {
        return Mono.empty();
    }

    @GetMapping("/api/jobs/{id}")
    public Mono<Job> get(@PathVariable String id) {
        return jobs.find(id);
    }
}
