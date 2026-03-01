package com.lab1.crackhash.manager.api;

public class CrackHashRequestDto {
    private String hash;
    private int maxLength;

    public CrackHashRequestDto() {
    }

    public CrackHashRequestDto(String hash, int maxLength) {
        this.hash = hash;
        this.maxLength = maxLength;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }
}