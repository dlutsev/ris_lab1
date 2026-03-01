package com.lab1.crackhash.common.dto;

public class WorkerTaskRequest {

    private String requestId;
    private String hash;
    private String alphabet;
    private int maxLength;
    private int partNumber;
    private int partCount;

    public WorkerTaskRequest() {
    }

    public WorkerTaskRequest(String requestId, String hash, String alphabet, int maxLength, int partNumber, int partCount) {
        this.requestId = requestId;
        this.hash = hash;
        this.alphabet = alphabet;
        this.maxLength = maxLength;
        this.partNumber = partNumber;
        this.partCount = partCount;
    }

    public String getRequestId() { return requestId; }
    public String getHash() { return hash; }
    public String getAlphabet() { return alphabet; }
    public int getMaxLength() { return maxLength; }
    public int getPartNumber() { return partNumber; }
    public int getPartCount() { return partCount; }
}
