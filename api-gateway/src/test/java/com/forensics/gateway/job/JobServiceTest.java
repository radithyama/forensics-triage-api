package com.forensics.gateway.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobServiceTest {

    @Test
    void sanitizeStripsPathsAndUnsafeCharacters() {
        assertThat(JobService.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(JobService.sanitize("my dump (1).raw")).isEqualTo("my_dump__1_.raw");
        assertThat(JobService.sanitize("")).isEqualTo("memory.img");
        assertThat(JobService.sanitize("a".repeat(300))).hasSize(128);
    }
}
