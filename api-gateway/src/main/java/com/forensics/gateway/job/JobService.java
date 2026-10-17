package com.forensics.gateway.job;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.forensics.gateway.TriageProperties;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Job lifecycle on serverless primitives:
 * <ol>
 *   <li>{@link #create} records the job and returns a short-lived V4 signed URL. The browser uploads the
 *       evidence straight to GCS, so multi-GB images never pass through Cloud Run (32 MiB request cap).</li>
 *   <li>{@link #start} pins the uploaded object generation + server-side MD5 and publishes to Pub/Sub,
 *       whose push subscription wakes the worker.</li>
 * </ol>
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    static final String UPLOAD_CONTENT_TYPE = "application/octet-stream";
    static final String CONTENT_LENGTH_RANGE = "x-goog-content-length-range";

    private final Storage storage;
    private final ObjectProvider<Publisher> publisher;
    private final JobStore jobs;
    private final TriageProperties props;
    private final JsonMapper json;

    public JobService(Storage storage, ObjectProvider<Publisher> publisher, JobStore jobs, TriageProperties props, JsonMapper json) {
        this.storage = storage;
        this.publisher = publisher;
        this.jobs = jobs;
        this.props = props;
        this.json = json;
    }

    public Mono<CreateJobResponse> create(CreateJobRequest req) {
        String plugin = req.plugin() == null ? TriageProperties.DEFAULT_PLUGIN : req.plugin();
        if (!props.allowedPlugins().contains(plugin)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported plugin: " + plugin));
        }
        long min = props.minUploadSize().toBytes();
        long max = props.maxUploadSize().toBytes();
        if (req.sizeBytes() < min || req.sizeBytes() > max) {
            HttpStatus status = req.sizeBytes() > max ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.BAD_REQUEST;
            return Mono.error(new ResponseStatusException(status, "Evidence must be between %d MB and %d GB"
                    .formatted(props.minUploadSize().toMegabytes(), props.maxUploadSize().toGigabytes())));
        }

        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        Job job = new Job(id, req.filename(), req.sizeBytes(), null, null, plugin,
                JobStatus.AWAITING_UPLOAD, 0, "Waiting for evidence upload", now, now, null);

        // Everything below is part of the V4 signature: GCS rejects the PUT if the client changes the
        // method, Content-Type, Content-MD5 or sends a body outside the length range, or after the TTL.
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", UPLOAD_CONTENT_TYPE);
        headers.put(CONTENT_LENGTH_RANGE, min + "," + max);
        BlobInfo.Builder target = BlobInfo.newBuilder(evidenceId(id)).setContentType(UPLOAD_CONTENT_TYPE);
        // Content-Type goes in as an explicit signed header: withContentType() alone does not add it to
        // a V4 signature's SignedHeaders, so GCS would accept any type.
        List<Storage.SignUrlOption> options = new ArrayList<>(List.of(
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                Storage.SignUrlOption.withExtHeaders(Map.of(
                        "Content-Type", UPLOAD_CONTENT_TYPE,
                        CONTENT_LENGTH_RANGE, min + "," + max)),
                Storage.SignUrlOption.withV4Signature()));
        if (req.md5() != null) {
            target.setMd5(req.md5());
            options.add(Storage.SignUrlOption.withMd5());
            headers.put("Content-MD5", req.md5());
        }

        return jobs.create(job)
                .then(Mono.fromCallable(() -> storage.signUrl(target.build(), props.uploadUrlTtl().toSeconds(), TimeUnit.SECONDS,
                                options.toArray(Storage.SignUrlOption[]::new)))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(url -> new CreateJobResponse(job, url.toString(), "PUT", headers,
                        Instant.now().plus(props.uploadUrlTtl()).toString()));
    }

    public Mono<Job> start(String id) {
        requireUuid(id);
        return jobs.get(id)
                .switchIfEmpty(Mono.error(notFound()))
                .flatMap(stored -> {
                    if (stored.job().status() != JobStatus.AWAITING_UPLOAD) {
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Job already started"));
                    }
                    return Mono.fromCallable(() -> storage.get(evidenceId(id)))
                            .subscribeOn(Schedulers.boundedElastic())
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Evidence has not been uploaded yet")))
                            .flatMap(blob -> enqueue(stored, blob));
                });
    }

    private Mono<Job> enqueue(JobStore.Stored stored, Blob blob) {
        // GCS already enforced these through the signed headers; re-checking defends against any object
        // that reached this path some other way (e.g. an operator copying files into the bucket).
        boolean sizeOk = blob.getSize() >= props.minUploadSize().toBytes() && blob.getSize() <= props.maxUploadSize().toBytes();
        if (!sizeOk || !UPLOAD_CONTENT_TYPE.equals(blob.getContentType())) {
            return Mono.fromRunnable(() -> storage.delete(blob.getBlobId()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .then(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Uploaded object violates the size or content-type policy and was discarded")));
        }

        Job previous = stored.job();
        Job queued = previous.withQueued(blob.getSize(), blob.getMd5(), Instant.now().toString());
        JobMessage message = new JobMessage(previous.id(), previous.plugin(), blob.getBucket(), blob.getName(),
                blob.getGeneration(), blob.getMd5());

        return jobs.replace(queued, stored.generation())
                .then(publish(message))
                .doOnNext(msgId -> log.info("Queued job {} ({} bytes, md5={}, generation={}, pubsub={})",
                        queued.id(), blob.getSize(), blob.getMd5(), blob.getGeneration(), msgId))
                .thenReturn(queued)
                // If publishing failed, roll back to AWAITING_UPLOAD so the client can retry start.
                .onErrorResume(e -> !(e instanceof ResponseStatusException),
                        e -> jobs.get(previous.id())
                                .flatMap(s -> jobs.replace(previous, s.generation()))
                                .then(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                        "Could not queue job, please retry", e))));
    }

    private Mono<String> publish(JobMessage message) {
        PubsubMessage msg = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(json.writeValueAsString(message)))
                .putAttributes("jobId", message.jobId())
                .build();
        return Mono.fromCallable(() -> publisher.getObject().publish(msg).get(30, TimeUnit.SECONDS))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Job> find(String id) {
        requireUuid(id);
        return jobs.get(id).map(JobStore.Stored::job).switchIfEmpty(Mono.error(notFound()));
    }

    private BlobId evidenceId(String id) {
        return BlobId.of(props.bucket(), "evidence/" + id + "/evidence.img");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
    }

    private static void requireUuid(String id) {
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job id");
        }
    }
}
