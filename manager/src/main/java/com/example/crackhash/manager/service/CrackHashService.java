package com.example.crackhash.manager.service;

import com.example.crackhash.manager.api.CrackHashRequestDto;
import com.example.crackhash.manager.api.CrackHashStatusResponseDto;
import com.example.crackhash.manager.model.CrackHashRequestInfo;
import com.example.crackhash.manager.model.RequestStatus;
import com.example.crackhash.manager.dto.WorkerTaskRequest;
import com.example.crackhash.manager.dto.WorkerTaskResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Service
public class CrackHashService {
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private final RestTemplate restTemplate;
    private final Executor executor;
    private final Map<String, CrackHashRequestInfo> requests = new ConcurrentHashMap<>();

    private final int workerCount;
    private final List<String> workerBaseUrls;
    private final long requestTtlMillis;

    public CrackHashService(RestTemplate restTemplate,
                            Executor workerTasksExecutor,
                            @Value("${crackhash.worker-count}") int workerCount,
                            @Value("${crackhash.worker-base-urls}") String workerBaseUrls,
                            @Value("${crackhash.request-ttl-millis}") long requestTtlMillis) {
        this.restTemplate = restTemplate;
        this.executor = workerTasksExecutor;
        this.workerCount = workerCount;
        this.workerBaseUrls = parseWorkerBaseUrls(workerBaseUrls);
        this.requestTtlMillis = requestTtlMillis;
    }

    public String createCrackRequest(CrackHashRequestDto dto) {
        String requestId = UUID.randomUUID().toString();
        CrackHashRequestInfo info = new CrackHashRequestInfo(
                requestId,
                dto.getHash(),
                dto.getMaxLength(),
                workerCount
        );
        requests.put(requestId, info);

        for (int part = 0; part < workerCount; part++) {
            int partNumber = part;
            executor.execute(() -> sendTaskToWorker(info, partNumber));
        }

        return requestId;
    }

    private void sendTaskToWorker(CrackHashRequestInfo info, int partNumber) {
        WorkerTaskRequest taskRequest = new WorkerTaskRequest(
                info.getRequestId(),
                info.getHash(),
                ALPHABET,
                info.getMaxLength(),
                partNumber,
                info.getPartCount()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<WorkerTaskRequest> entity = new HttpEntity<>(taskRequest, headers);

        String baseUrl = workerBaseUrls.get(partNumber % workerBaseUrls.size());
        String url = baseUrl + "/internal/api/worker/hash/crack/task";

        try {
            restTemplate.postForEntity(url, entity, Void.class);
        } catch (Exception ex) {
            info.setStatus(RequestStatus.ERROR);
        }
    }

    public void handleWorkerResponse(WorkerTaskResponse response) {
        CrackHashRequestInfo info = requests.get(response.getRequestId());
        if (info == null) {
            return;
        }

        if (isExpired(info)) {
            info.setStatus(RequestStatus.ERROR);
            return;
        }

        info.addAnswers(response.getAnswers());
        int completed = info.incrementCompletedParts();

        if (completed >= info.getPartCount() && info.getStatus() == RequestStatus.IN_PROGRESS) {
            info.setStatus(RequestStatus.READY);
        }
    }

    public CrackHashStatusResponseDto getStatus(String requestId) {
        CrackHashRequestInfo info = requests.get(requestId);
        if (info == null) {
            return new CrackHashStatusResponseDto("ERROR", null);
        }

        if (info.getStatus() == RequestStatus.IN_PROGRESS && isExpired(info)) {
            info.setStatus(RequestStatus.ERROR);
        }

        if (info.getStatus() == RequestStatus.IN_PROGRESS) {
            return new CrackHashStatusResponseDto("IN_PROGRESS", null);
        } else if (info.getStatus() == RequestStatus.READY) {
            return new CrackHashStatusResponseDto("READY", new ArrayList<>(info.getAnswers()));
        } else {
            return new CrackHashStatusResponseDto("ERROR", null);
        }
    }

    private boolean isExpired(CrackHashRequestInfo info) {
        long now = Instant.now().toEpochMilli();
        return now - info.getCreatedAtMillis() > requestTtlMillis;
    }

    private List<String> parseWorkerBaseUrls(String value) {
        if (value == null || value.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = value.split(",");
        List<String> urls = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                urls.add(trimmed);
            }
        }
        return urls;
    }
}