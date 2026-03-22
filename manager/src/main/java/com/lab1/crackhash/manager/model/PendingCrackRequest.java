package com.lab1.crackhash.manager.model;

public final class PendingCrackRequest {
    private final String requestId;
    private final String hash;
    private final int maxLength;

    public PendingCrackRequest(String requestId, String hash, int maxLength) {
        this.requestId = requestId;
        this.hash = hash;
        this.maxLength = maxLength;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getHash() {
        return hash;
    }

    public int getMaxLength() {
        return maxLength;
    }
}
