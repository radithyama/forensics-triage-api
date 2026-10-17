package com.forensics.gateway.job;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Metadata sent before upload. Validation is strict: anything unexpected is rejected rather than
 * cleaned up, so no client-chosen string ever reaches a storage path or log line unvetted.
 */
public record CreateJobRequest(
        // A bare file name (no separators, no "..", no leading dot) with a memory-image extension.
        @NotNull
        @Pattern(regexp = FILENAME, message = "must be a plain file name ending in .raw, .mem, .vmem, .dmp, .lime, .img, .bin or .dd")
        String filename,

        @Positive
        long sizeBytes,

        @Pattern(regexp = "^[a-z]+\\.[a-z]+\\.[A-Za-z]+$", message = "must look like windows.pslist.PsList")
        String plugin,

        // Optional base64 MD5 (16 bytes -> 22 chars + "=="). When given, it is bound into the signed URL as
        // Content-MD5, so GCS rejects any upload whose bytes don't match what the client hashed.
        @Pattern(regexp = "^[A-Za-z0-9+/]{22}==$", message = "must be a base64-encoded MD5 digest")
        String md5) {

    static final String FILENAME = "^(?!.*\\.\\.)[A-Za-z0-9][A-Za-z0-9._-]{0,123}\\.(?i:raw|mem|vmem|dmp|lime|img|bin|dd)$";
}
