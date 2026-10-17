package com.forensics.gateway.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import com.forensics.gateway.TriageProperties;
import com.google.api.core.ApiFutures;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.pubsub.v1.PubsubMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

class JobServiceFlowTest {

    private static final String ID = "3f6c1e0e-8a1d-4c55-9a0b-2c3d4e5f6a7b";

    private final Storage storage = mock(Storage.class);
    private final Publisher publisher = mock(Publisher.class);
    private final JobStore store = mock(JobStore.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private JobService service;

    @BeforeEach
    void setUp() {
        TriageProperties props = new TriageProperties("proj", "evidence-bucket", "forensic-jobs",
                Duration.ofMinutes(30), DataSize.ofGigabytes(6), List.of(), List.of("windows.pslist.PsList"), null, "k".repeat(32));
        var beans = new StaticListableBeanFactory(java.util.Map.of("publisher", publisher));
        service = new JobService(storage, beans.getBeanProvider(Publisher.class), store, props, json);
    }

    private static Job job(JobStatus status) {
        return new Job(ID, "dump.raw", 100, null, null, "windows.pslist.PsList", status, 0, "", "t", "t", null);
    }

    private static void expectStatus(Mono<?> mono, HttpStatus status) {
        StepVerifier.create(mono)
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(status))
                .verify();
    }

    @Test
    void createRejectsPluginsOutsideAllowList() {
        expectStatus(service.create(new CreateJobRequest("a.raw", 10, "windows.hashdump.Hashdump")), HttpStatus.BAD_REQUEST);
    }

    @Test
    void createRejectsOversizedEvidence() {
        expectStatus(service.create(new CreateJobRequest("a.raw", DataSize.ofGigabytes(7).toBytes(), null)), HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void startRefusesJobsThatAlreadyStarted() {
        when(store.get(ID)).thenReturn(Mono.just(new JobStore.Stored(job(JobStatus.QUEUED), 1)));
        expectStatus(service.start(ID), HttpStatus.CONFLICT);
        verify(publisher, never()).publish(any());
    }

    @Test
    void startRequiresUploadedEvidence() {
        when(store.get(ID)).thenReturn(Mono.just(new JobStore.Stored(job(JobStatus.AWAITING_UPLOAD), 1)));
        when(storage.get(any(BlobId.class))).thenReturn(null);
        expectStatus(service.start(ID), HttpStatus.CONFLICT);
    }

    @Test
    void startPinsGenerationAndMd5ThenPublishes() {
        Blob blob = mock(Blob.class);
        when(blob.getSize()).thenReturn(4096L);
        when(blob.getMd5()).thenReturn("q83vEjRWeJA=");
        when(blob.getGeneration()).thenReturn(1712L);
        when(blob.getBucket()).thenReturn("evidence-bucket");
        when(blob.getName()).thenReturn("evidence/" + ID + "/evidence.img");
        when(store.get(ID)).thenReturn(Mono.just(new JobStore.Stored(job(JobStatus.AWAITING_UPLOAD), 7)));
        when(storage.get(any(BlobId.class))).thenReturn(blob);
        when(store.replace(any(), anyLong())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(publisher.publish(any())).thenReturn(ApiFutures.immediateFuture("msg-1"));

        StepVerifier.create(service.start(ID))
                .assertNext(j -> {
                    assertThat(j.status()).isEqualTo(JobStatus.QUEUED);
                    assertThat(j.sizeBytes()).isEqualTo(4096L);
                    assertThat(j.md5()).isEqualTo("q83vEjRWeJA=");
                })
                .verifyComplete();

        verify(store).replace(any(), eq(7L));
        ArgumentCaptor<PubsubMessage> sent = ArgumentCaptor.forClass(PubsubMessage.class);
        verify(publisher).publish(sent.capture());
        JobMessage msg = json.readValue(sent.getValue().getData().toStringUtf8(), JobMessage.class);
        assertThat(msg).isEqualTo(new JobMessage(ID, "windows.pslist.PsList", "evidence-bucket",
                "evidence/" + ID + "/evidence.img", 1712L, "q83vEjRWeJA="));
    }

    @Test
    void failedPublishRollsBackSoStartCanBeRetried() {
        Blob blob = mock(Blob.class);
        when(blob.getSize()).thenReturn(10L);
        when(store.get(ID)).thenReturn(Mono.just(new JobStore.Stored(job(JobStatus.AWAITING_UPLOAD), 7)));
        when(storage.get(any(BlobId.class))).thenReturn(blob);
        when(store.replace(any(), anyLong())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(publisher.publish(any())).thenReturn(ApiFutures.immediateFailedFuture(new RuntimeException("pubsub down")));

        expectStatus(service.start(ID), HttpStatus.SERVICE_UNAVAILABLE);
        ArgumentCaptor<Job> writes = ArgumentCaptor.forClass(Job.class);
        verify(store, org.mockito.Mockito.times(2)).replace(writes.capture(), anyLong());
        assertThat(writes.getAllValues().get(1).status()).isEqualTo(JobStatus.AWAITING_UPLOAD);
    }
}
