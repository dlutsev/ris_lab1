package com.lab1.crackhash.manager.dto;

public class CrackHashResponseDto {
    private String requestId;

    public CrackHashResponseDto() {
    }

    public CrackHashResponseDto(String requestId) {
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}