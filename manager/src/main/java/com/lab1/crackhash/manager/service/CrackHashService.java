package com.lab1.crackhash.manager.service;

import com.lab1.crackhash.common.dto.WorkerTaskRequest;
import com.lab1.crackhash.common.dto.WorkerTaskResponse;
import com.lab1.crackhash.manager.api.CrackHashRequestDto;
import com.lab1.crackhash.manager.api.CrackHashStatusResponseDto;
import com.lab1.crackhash.manager.model.CrackHashRequestInfo;
import com.lab1.crackhash.manager.model.RequestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CrackHashService {

    private static final Logger log = LoggerFactory.getLogger(CrackHashService.class);
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final RestTemplate restTemplate;
    private final Executor executor;
    private final Map<String, CrackHashRequestInfo> requests = new ConcurrentHashMap<>();

    private final int workerCount;
    private final List<String> workerBaseUrls;
    private final long requestTtlMillis;

    private final int queueCapacity;
    private final int cacheCapacity;
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final Deque<CrackHashRequestInfo> pendingQueue = new ArrayDeque<>();
    private final Map<String, CrackHashStatusResponseDto> cache;

    public CrackHashService(RestTemplate restTemplate,
                            Executor workerTasksExecutor,
                            @Value("${crackhash.worker-count}") int workerCount,
                            @Value("${crackhash.worker-base-urls}") String workerBaseUrls,
                            @Value("${crackhash.request-ttl-millis}") long requestTtlMillis,
                            @Value("${crackhash.queue-capacity:100}") int queueCapacity,
                            @Value("${crackhash.cache-capacity:100}") int cacheCapacity) {
        this.restTemplate = restTemplate;
        this.executor = workerTasksExecutor;
        this.workerCount = workerCount;
        this.workerBaseUrls = parseWorkerBaseUrls(workerBaseUrls);
        this.requestTtlMillis = requestTtlMillis;
        this.queueCapacity = queueCapacity;
        this.cacheCapacity = cacheCapacity;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, CrackHashStatusResponseDto>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CrackHashStatusResponseDto> eldest) {
                return size() > CrackHashService.this.cacheCapacity;
            }
        });
    }

    public String createCrackRequest(CrackHashRequestDto dto) {
        String cacheKey = cacheKey(dto.getHash(), dto.getMaxLength());
        CrackHashStatusResponseDto cached = cache.get(cacheKey);
        if (cached != null) {
            String cachedRequestId = UUID.randomUUID().toString();
            CrackHashRequestInfo cachedInfo = new CrackHashRequestInfo(
                    cachedRequestId,
                    dto.getHash(),
                    dto.getMaxLength(),
                    0
            );
            if ("READY".equals(cached.getStatus()) || "PARTIAL_RESULT".equals(cached.getStatus())) {
                if (cached.getData() != null) {
                    cachedInfo.addAnswers(cached.getData());
                }
                cachedInfo.setStatus(RequestStatus.valueOf(cached.getStatus()));
            } else {
                cachedInfo.setStatus(RequestStatus.ERROR);
            }
            requests.put(cachedRequestId, cachedInfo);
            log.info("Cache hit for hash={}, maxLength={}, requestId={}", dto.getHash(), dto.getMaxLength(), cachedRequestId);
            return cachedRequestId;
        }

        String requestId = UUID.randomUUID().toString();
        CrackHashRequestInfo info = new CrackHashRequestInfo(
                requestId,
                dto.getHash(),
                dto.getMaxLength(),
                workerCount
        );
        requests.put(requestId, info);

        int currentActive = activeRequests.get();
        if (currentActive < queueCapacity) {
            activeRequests.incrementAndGet();
            log.info("Request [{}] registered and started immediately, active={}, queueSize={}, workers={}",
                    requestId, activeRequests.get(), pendingQueue.size(), workerBaseUrls);
            startProcessing(info);
        } else if (pendingQueue.size() < queueCapacity) {
            pendingQueue.offer(info);
            log.info("Request [{}] queued, active={}, queueSize={}", requestId, activeRequests.get(), pendingQueue.size());
        } else {
            log.warn("Request [{}] rejected: queue and active slots are full (active={}, queueSize={})",
                    requestId, activeRequests.get(), pendingQueue.size());
            info.setStatus(RequestStatus.ERROR);
        }

        return requestId;
    }

    private void startProcessing(CrackHashRequestInfo info) {
        log.info("Request [{}] starting processing, splitting into {} parts, workers={}",
                info.getRequestId(), workerCount, workerBaseUrls);
        for (int part = 0; part < workerCount; part++) {
            int partNumber = part;
            executor.execute(() -> sendTaskToWorker(info, partNumber));
        }
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
        log.info("Request [{}] sending part {}/{} to worker {}", info.getRequestId(), partNumber, info.getPartCount(), baseUrl);

        try {
            restTemplate.postForEntity(url, entity, Void.class);
        } catch (Exception ex) {
            log.error("Request [{}] part {}/{} failed to send to {}: {}", info.getRequestId(), partNumber, info.getPartCount(), baseUrl, ex.getMessage());
            info.incrementFailedParts();
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
        log.info("Request [{}] worker response: part {}/{}, answers={}, completedParts={}/{}",
                response.getRequestId(), response.getPartNumber(), response.getPartCount(), response.getAnswers(), completed, info.getPartCount());

        int failed = info.getFailedParts();
        if (completed + failed >= info.getPartCount() && info.getStatus() == RequestStatus.IN_PROGRESS) {
            if (!info.getAnswers().isEmpty() && failed > 0) {
                info.setStatus(RequestStatus.PARTIAL_RESULT);
            } else if (!info.getAnswers().isEmpty()) {
                info.setStatus(RequestStatus.READY);
            } else {
                info.setStatus(RequestStatus.ERROR);
            }
            finishRequest(info);
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
        } else if (info.getStatus() == RequestStatus.PARTIAL_RESULT) {
            return new CrackHashStatusResponseDto("PARTIAL_RESULT", new ArrayList<>(info.getAnswers()));
        } else {
            return new CrackHashStatusResponseDto("ERROR", null);
        }
    }

    private void finishRequest(CrackHashRequestInfo info) {
        int active = activeRequests.decrementAndGet();
        log.info("Request [{}] finished with status {}, active={}, queueSize={}",
                info.getRequestId(), info.getStatus(), active, pendingQueue.size());

        if (info.getStatus() == RequestStatus.READY) {
            cache.put(cacheKey(info.getHash(), info.getMaxLength()),
                    new CrackHashStatusResponseDto(info.getStatus().name(), new ArrayList<>(info.getAnswers())));
        }

        CrackHashRequestInfo next = pendingQueue.poll();
        if (next != null) {
            activeRequests.incrementAndGet();
            log.info("Dequeued request [{}] for processing, active={}, queueSize={}",
                    next.getRequestId(), activeRequests.get(), pendingQueue.size());
            startProcessing(next);
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

    private String cacheKey(String hash, int maxLength) {
        return (hash != null ? hash.toLowerCase() : "null") + "|" + maxLength;
    }
}