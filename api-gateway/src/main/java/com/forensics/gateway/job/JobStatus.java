package com.forensics.gateway.job;

public enum JobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
