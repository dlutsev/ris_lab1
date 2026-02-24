package com.example.crackhash.worker.xml;

import java.util.ArrayList;
import java.util.List;

public class CrackHashWorkerResponse {

    private String requestId;
    private int partNumber;
    private int partCount;
    private List<String> answers = new ArrayList<>();

    public CrackHashWorkerResponse() {
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public int getPartNumber() {
        return partNumber;
    }

    public void setPartNumber(int partNumber) {
        this.partNumber = partNumber;
    }

    public int getPartCount() {
        return partCount;
    }

    public void setPartCount(int partCount) {
        this.partCount = partCount;
    }

    public List<String> getAnswers() {
        return answers;
    }

    public void setAnswers(List<String> answers) {
        this.answers = answers;
    }
}

