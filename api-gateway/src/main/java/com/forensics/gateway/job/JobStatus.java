package com.forensics.gateway.job;

public enum JobStatus {
    AWAITING_UPLOAD, QUEUED, RUNNING, COMPLETED, FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
