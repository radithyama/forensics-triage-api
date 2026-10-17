package com.forensics.gateway.job;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.forensics.gateway.TriageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the job lifecycle: evidence intake to the temp volume, job state in a Redis hash
 * ({@code job:<id>}), and hand-off to the worker via a Redis list used as a queue.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final String EVIDENCE_FILE = "evidence.img";

    private final ReactiveStringRedisTemplate redis;
    private final TriageProperties props;
    private final JsonMapper json;

    public JobService(ReactiveStringRedisTemplate redis, TriageProperties props, JsonMapper json) {
        this.redis = redis;
        this.props = props;
        this.json = json;
    }

    public Mono<Job> submit(FilePart file, String requestedPlugin) {
        String plugin = (requestedPlugin == null || requestedPlugin.isBlank())
                ? TriageProperties.DEFAULT_PLUGIN : requestedPlugin.trim();
        if (!props.allowedPlugins().contains(plugin)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported plugin: " + plugin));
        }

        String id = UUID.randomUUID().toString();
        // The client-supplied name is kept as metadata only; on disk we never use it (no path traversal).
        String filename = sanitize(file.filename());
        Path dir = props.uploadDir().resolve(id);
        Path target = dir.resolve(EVIDENCE_FILE);

        return Mono.fromCallable(() -> Files.createDirectories(dir))
                .subscribeOn(Schedulers.boundedElastic())
                .then(file.transferTo(target))
                .then(Mono.fromCallable(() -> new Evidence(Files.size(target), sha256(target)))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(ev -> {
                    if (ev.size() == 0) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is empty"));
                    }
                    String now = Instant.now().toString();
                    Map<String, String> fields = new HashMap<>();
                    fields.put("id", id);
                    fields.put("filename", filename);
                    fields.put("sizeBytes", Long.toString(ev.size()));
                    fields.put("sha256", ev.sha256());
                    fields.put("plugin", plugin);
                    fields.put("status", JobStatus.QUEUED.name());
                    fields.put("progress", "0");
                    fields.put("message", "Queued for analysis");
                    fields.put("createdAt", now);
                    fields.put("updatedAt", now);

                    String message = json.writeValueAsString(new JobMessage(id, plugin, filename, ev.sha256()));
                    return redis.opsForHash().putAll(key(id), fields)
                            .then(redis.expire(key(id), props.jobTtl()))
                            .then(redis.opsForList().leftPush(props.queueKey(), message))
                            .doOnSuccess(depth -> log.info("Queued job {} ({} bytes, sha256={}, plugin={}, queueDepth={})",
                                    id, ev.size(), ev.sha256(), plugin, depth))
                            .then(find(id));
                })
                .onErrorResume(e -> deleteEvidence(id).then(Mono.error(e)));
    }

    public Mono<Job> find(String id) {
        requireUuid(id);
        return redis.<String, String>opsForHash().entries(key(id))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .filter(m -> !m.isEmpty())
                .map(this::toJob)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found")));
    }

    public Mono<Job> applyUpdate(String id, StatusUpdate update) {
        return find(id).flatMap(existing -> {
            if (existing.status().isTerminal()) {
                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Job already finished"));
            }
            Map<String, String> fields = new HashMap<>();
            fields.put("status", update.status().name());
            fields.put("progress", Integer.toString(update.progress()));
            fields.put("updatedAt", Instant.now().toString());
            if (update.message() != null) {
                fields.put("message", update.message());
            }
            if (update.result() != null) {
                fields.put("result", json.writeValueAsString(update.result()));
            }
            Mono<Void> cleanup = update.status().isTerminal() ? deleteEvidence(id) : Mono.empty();
            return redis.opsForHash().putAll(key(id), fields)
                    .then(cleanup)
                    .then(find(id));
        });
    }

    /** Path of the stored evidence, for the worker download endpoint. */
    public Mono<Path> evidencePath(String id) {
        requireUuid(id);
        Path path = props.uploadDir().resolve(id).resolve(EVIDENCE_FILE);
        return Mono.fromCallable(() -> Files.isRegularFile(path))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(exists -> exists ? Mono.just(path)
                        : Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence not available")));
    }

    /** Evidence is removed as soon as a job finishes so raw memory never lingers on the volume. */
    private Mono<Void> deleteEvidence(String id) {
        Path dir = props.uploadDir().resolve(id);
        return Mono.<Void>fromRunnable(() -> {
            if (!Files.exists(dir)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                log.warn("Failed to delete evidence for job {}: {}", id, e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Job toJob(Map<String, String> m) {
        String result = m.get("result");
        return new Job(
                m.get("id"),
                m.get("filename"),
                Long.parseLong(m.getOrDefault("sizeBytes", "0")),
                m.get("sha256"),
                m.get("plugin"),
                JobStatus.valueOf(m.get("status")),
                Integer.parseInt(m.getOrDefault("progress", "0")),
                m.get("message"),
                m.get("createdAt"),
                m.get("updatedAt"),
                result == null ? null : json.readTree(result));
    }

    private static String key(String id) {
        return "job:" + id;
    }

    private static void requireUuid(String id) {
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job id");
        }
    }

    static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "memory.img";
        }
        String base = Path.of(name).getFileName().toString();
        String clean = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() > 128 ? clean.substring(0, 128) : clean;
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 20];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record Evidence(long size, String sha256) {
    }
}
