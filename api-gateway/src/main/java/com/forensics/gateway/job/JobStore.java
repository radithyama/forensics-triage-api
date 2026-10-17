package com.forensics.gateway.job;

import java.nio.charset.StandardCharsets;

import com.forensics.gateway.TriageProperties;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job documents live next to the evidence as {@code jobs/<id>.json}, so the platform needs no database
 * and the bucket lifecycle rule expires job state together with evidence. Writes use GCS generation
 * preconditions, giving compare-and-set semantics for state transitions.
 */
@Component
public class JobStore {

    private static final int PRECONDITION_FAILED = 412;

    private final Storage storage;
    private final String bucket;
    private final JsonMapper json;

    public JobStore(Storage storage, TriageProperties props, JsonMapper json) {
        this.storage = storage;
        this.bucket = props.bucket();
        this.json = json;
    }

    public record Stored(Job job, long generation) {
    }

    public Mono<Stored> get(String id) {
        return Mono.fromCallable(() -> storage.get(BlobId.of(bucket, path(id))))
                .subscribeOn(Schedulers.boundedElastic())
                .map(blob -> new Stored(json.readValue(blob.getContent(), Job.class), blob.getGeneration()));
    }

    public Mono<Job> create(Job job) {
        return write(BlobId.of(bucket, path(job.id())), job, Storage.BlobTargetOption.doesNotExist());
    }

    /** Replaces the document only if nobody else has written it since {@code expectedGeneration}. */
    public Mono<Job> replace(Job job, long expectedGeneration) {
        return write(BlobId.of(bucket, path(job.id()), expectedGeneration), job, Storage.BlobTargetOption.generationMatch());
    }

    private Mono<Job> write(BlobId id, Job job, Storage.BlobTargetOption condition) {
        return Mono.fromCallable(() -> {
            BlobInfo info = BlobInfo.newBuilder(id).setContentType("application/json").build();
            Blob ignored = storage.create(info, json.writeValueAsString(job).getBytes(StandardCharsets.UTF_8), condition);
            return job;
        }).subscribeOn(Schedulers.boundedElastic()).onErrorMap(
                e -> e instanceof StorageException se && se.getCode() == PRECONDITION_FAILED,
                e -> new ResponseStatusException(HttpStatus.CONFLICT, "Job was modified concurrently"));
    }

    static String path(String id) {
        return "jobs/" + id + ".json";
    }
}
