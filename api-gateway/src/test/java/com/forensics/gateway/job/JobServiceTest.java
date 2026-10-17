package com.forensics.gateway.job;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JobServiceTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final long SIZE = 64L << 20;

    private static boolean valid(CreateJobRequest req) {
        return VALIDATOR.validate(req).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WIN10-WKSTN.raw", "case_42.mem", "host.vmem", "a.DMP", "img.lime", "x.dd"})
    void acceptsPlainMemoryImageNames(String name) {
        assertThat(valid(new CreateJobRequest(name, SIZE, null, null))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../etc/passwd.raw", "..raw", "a/b.raw", "a\\\\b.raw", ".hidden.raw", "dump.exe",
            "dump.raw.sh", "dump", "", "dump .raw", "dump%2e%2e.raw", "dump\u0000.raw"})
    void rejectsTraversalAndUnexpectedNames(String name) {
        assertThat(valid(new CreateJobRequest(name, SIZE, null, null))).isFalse();
    }

    @Test
    void rejectsLongNames() {
        assertThat(valid(new CreateJobRequest("a".repeat(130) + ".raw", SIZE, null, null))).isFalse();
    }

    @Test
    void validatesMd5Format() {
        assertThat(valid(new CreateJobRequest("a.raw", SIZE, null, "1B2M2Y8AsgTpgAmY7PhCfg=="))).isTrue();
        assertThat(valid(new CreateJobRequest("a.raw", SIZE, null, "d41d8cd98f00b204e9800998ecf8427e"))).isFalse(); // hex, not base64
        assertThat(valid(new CreateJobRequest("a.raw", SIZE, null, "1B2M2Y8AsgTpgAmY7PhCfg="))).isFalse();
    }

    @Test
    void validatesPluginShape() {
        assertThat(valid(new CreateJobRequest("a.raw", SIZE, "windows.pslist.PsList", null))).isTrue();
        assertThat(valid(new CreateJobRequest("a.raw", SIZE, "windows.pslist.PsList; rm -rf /", null))).isFalse();
    }
}
