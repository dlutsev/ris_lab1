package com.example.crackhash.manager.api;

import java.util.List;

public class CrackHashStatusResponseDto {

    private String status;
    private List<String> data;

    public CrackHashStatusResponseDto() {
    }

    public CrackHashStatusResponseDto(String status, List<String> data) {
        this.status = status;
        this.data = data;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<String> getData() {
        return data;
    }

    public void setData(List<String> data) {
        this.data = data;
    }
}