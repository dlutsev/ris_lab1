package com.lab1.crackhash.manager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class CrackHashRequestInfo {
    private final String requestId;
    private final String hash;
    private final int maxLength;
    private final int partCount;
    private final AtomicInteger completedParts = new AtomicInteger(0);
    private final AtomicInteger failedParts = new AtomicInteger(0);
    private final List<String> answers = Collections.synchronizedList(new ArrayList<>());
    private volatile RequestStatus status = RequestStatus.IN_PROGRESS;

    public CrackHashRequestInfo(String requestId, String hash, int maxLength, int partCount) {
        this.requestId = requestId;
        this.hash = hash;
        this.maxLength = maxLength;
        this.partCount = partCount;
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

    public int getPartCount() {
        return partCount;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public List<String> getAnswers() {
        return answers;
    }

    public void addAnswers(List<String> newAnswers) {
        if (newAnswers != null && !newAnswers.isEmpty()) {
            answers.addAll(newAnswers);
        }
    }

    public int incrementCompletedParts() {
        return completedParts.incrementAndGet();
    }

    public int getCompletedParts() {
        return completedParts.get();
    }

    public int incrementFailedParts() {
        return failedParts.incrementAndGet();
    }

    public int getFailedParts() {
        return failedParts.get();
    }
}