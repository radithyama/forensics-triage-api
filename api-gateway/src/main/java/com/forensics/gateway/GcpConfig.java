package com.forensics.gateway;

import java.io.IOException;

import com.google.cloud.ServiceOptions;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.pubsub.v1.TopicName;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** GCP clients authenticate via Application Default Credentials (the Cloud Run service account); no keys. */
@Configuration
public class GcpConfig {

    @Bean
    Storage storage() {
        return StorageOptions.getDefaultInstance().getService();
    }

    @Bean(destroyMethod = "shutdown")
    Publisher publisher(TriageProperties props) throws IOException {
        String project = props.projectId() == null || props.projectId().isBlank()
                ? ServiceOptions.getDefaultProjectId() : props.projectId();
        return Publisher.newBuilder(TopicName.of(project, props.topic())).build();
    }
}
