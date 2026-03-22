package com.lab1.crackhash.manager.service;

import com.lab1.crackhash.common.dto.WorkerTaskRequest;
import com.lab1.crackhash.common.dto.WorkerTaskResponse;
import com.lab1.crackhash.manager.dto.CrackHashRequestDto;
import com.lab1.crackhash.manager.dto.CrackHashStatusResponseDto;
import com.lab1.crackhash.manager.model.CrackHashRequestInfo;
import com.lab1.crackhash.manager.model.RequestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
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

@Service
public class CrackHashService {
    private static final Logger log = LoggerFactory.getLogger(CrackHashService.class);
    private static final int MAX_CONCURRENT_REQUESTS = 1;
    private final RestTemplate restTemplate;
    private final Executor executor;
    private final Map<String, CrackHashRequestInfo> requests = new ConcurrentHashMap<>();
    private final String alphabet;
    private final int workerCount;
    private final List<String> workerBaseUrls;
    private final long requestTtlMillis;
    private final int queueCapacity;
    private final int cacheCapacity;
    private final Object schedulingLock = new Object();
    private int activeRequests = 0;
    private CrackHashRequestInfo activeRequest = null;
    private final Deque<CrackHashRequestInfo> pendingQueue = new ArrayDeque<>();
    private final Map<String, CrackHashStatusResponseDto> cache;

    public CrackHashService(RestTemplate restTemplate,
                            Executor workerTasksExecutor,
                            @Value("${crackhash.alphabet:abcdefghijklmnopqrstuvwxyz0123456789}") String alphabet,
                            @Value("${crackhash.worker-count}") int workerCount,
                            @Value("${crackhash.worker-base-urls}") String workerBaseUrls,
                            @Value("${crackhash.request-ttl-millis}") long requestTtlMillis,
                            @Value("${crackhash.queue-capacity:100}") int queueCapacity,
                            @Value("${crackhash.cache-capacity:100}") int cacheCapacity) {
        this.restTemplate = restTemplate;
        this.executor = workerTasksExecutor;
        this.alphabet = alphabet;
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
            if ("READY".equals(cached.getStatus())) {
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

        boolean shouldStartNow = false;
        int queueSize;
        int active;
        synchronized (schedulingLock) {
            if (activeRequests < MAX_CONCURRENT_REQUESTS) {
                activeRequests++;
                shouldStartNow = true;
            } else if (pendingQueue.size() < queueCapacity) {
                pendingQueue.offer(info);
            } else {
                info.setStatus(RequestStatus.ERROR);
            }
            queueSize = pendingQueue.size();
            active = activeRequests;
        }

        if (info.getStatus() == RequestStatus.ERROR) {
            log.warn("Request [{}] rejected: scheduler full (active={}/{}, queueSize={}/{})",
                    requestId, active, MAX_CONCURRENT_REQUESTS, queueSize, queueCapacity);
            return requestId;
        }

        if (shouldStartNow) {
            log.info("Request [{}] started (active={}/{}, queueSize={}/{})",
                    requestId, active, MAX_CONCURRENT_REQUESTS, queueSize, queueCapacity);
            synchronized (schedulingLock) {
                activeRequest = info;
            }
            startProcessing(info);
        } else {
            log.info("Request [{}] queued (active={}/{}, queueSize={}/{})",
                    requestId, active, MAX_CONCURRENT_REQUESTS, queueSize, queueCapacity);
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
                alphabet,
                info.getMaxLength(),
                partNumber,
                info.getPartCount()
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<WorkerTaskRequest> entity = new HttpEntity<>(taskRequest, headers);

        String baseUrl = workerBaseUrls.get(partNumber % workerBaseUrls.size());
        String url = baseUrl + "/internal/api/worker/hash/crack/task";
        log.info("Request [{}] sending part {}/{} to worker {}", info.getRequestId(), partNumber+1, info.getPartCount(), baseUrl);

        try {
            restTemplate.postForEntity(url, entity, Void.class);
        } catch (Exception ex) {
            log.error("Request [{}] part {}/{} failed to send to {}: {}", info.getRequestId(), partNumber+1, info.getPartCount(), baseUrl, ex.getMessage());
            info.incrementFailedParts();
            tryFinalizeIfDone(info);
        }
    }

    public void handleWorkerResponse(WorkerTaskResponse response) {
        CrackHashRequestInfo info = requests.get(response.getRequestId());
        if (info == null) {
            return;
        }

        if (isExpired(info)) {
            forceExpire(info, "worker_response_after_ttl");
            return;
        }

        info.addAnswers(response.getAnswers());
        int completed = info.incrementCompletedParts();
        log.info("Request [{}] worker response: part {}/{}, answers={}, completedParts={}/{}",
                response.getRequestId(), response.getPartNumber(), response.getPartCount(), response.getAnswers(), completed, info.getPartCount());
        tryFinalizeIfDone(info);
    }

    public CrackHashStatusResponseDto getStatus(String requestId) {
        CrackHashRequestInfo info = requests.get(requestId);
        if (info == null) {
            return new CrackHashStatusResponseDto("ERROR", null);
        }

        if (info.getStatus() == RequestStatus.IN_PROGRESS && isExpired(info)) {
            forceExpire(info, "status_poll");
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
        if (info.getStatus() == RequestStatus.READY) {
            cache.put(cacheKey(info.getHash(), info.getMaxLength()),
                    new CrackHashStatusResponseDto(info.getStatus().name(), new ArrayList<>(info.getAnswers())));
        }
        CrackHashRequestInfo nextToStart = null;
        int active;
        int queueSize;
        synchronized (schedulingLock) {
            activeRequests = Math.max(0, activeRequests - 1);
            if (activeRequest == info) {
                activeRequest = null;
            }
            while (true) {
                CrackHashRequestInfo next = pendingQueue.poll();
                if (next == null) {
                    break;
                }

                CrackHashStatusResponseDto cached = cache.get(cacheKey(next.getHash(), next.getMaxLength()));
                if (cached != null && ("READY".equals(cached.getStatus()))) {
                    if (cached.getData() != null) {
                        next.addAnswers(cached.getData());
                    }
                    next.setStatus(RequestStatus.valueOf(cached.getStatus()));
                    log.info("Dequeued request [{}] served from cache, status={}", next.getRequestId(), next.getStatus());
                    continue;
                }
                nextToStart = next;
                activeRequests++;
                activeRequest = nextToStart;
                break;
            }
            active = activeRequests;
            queueSize = pendingQueue.size();
        }

        log.info("Request [{}] finished with status {} (active={}/{}, queueSize={}/{})",
                info.getRequestId(), info.getStatus(), active, MAX_CONCURRENT_REQUESTS, queueSize, queueCapacity);

        if (nextToStart != null) {
            log.info("Dequeued request [{}] for processing (active={}/{}, queueSize={}/{})",
                    nextToStart.getRequestId(), active, MAX_CONCURRENT_REQUESTS, queueSize, queueCapacity);
            startProcessing(nextToStart);
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void timeoutSweep() {
        CrackHashRequestInfo active;
        List<CrackHashRequestInfo> expiredQueued = new ArrayList<>();

        synchronized (schedulingLock) {
            active = activeRequest;
            for (var it = pendingQueue.iterator(); it.hasNext(); ) {
                CrackHashRequestInfo queued = it.next();
                if (queued.getStatus() == RequestStatus.IN_PROGRESS && isExpired(queued)) {
                    it.remove();
                    queued.setStatus(RequestStatus.ERROR);
                    expiredQueued.add(queued);
                }
            }
        }

        for (CrackHashRequestInfo queued : expiredQueued) {
            log.warn("Request [{}] expired while queued -> ERROR", queued.getRequestId());
        }

        if (active != null && active.getStatus() == RequestStatus.IN_PROGRESS && isExpired(active)) {
            forceExpire(active, "timeout_sweep");
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

    private void tryFinalizeIfDone(CrackHashRequestInfo info) {
        boolean shouldFinish = false;
        synchronized (info) {
            if (info.getStatus() != RequestStatus.IN_PROGRESS) {
                return;
            }
            int completed = info.getCompletedParts();
            int failed = info.getFailedParts();
            if (completed + failed < info.getPartCount()) {
                return;
            }

            if (!info.getAnswers().isEmpty() && failed > 0) {
                info.setStatus(RequestStatus.PARTIAL_RESULT);
            } else if (!info.getAnswers().isEmpty()) {
                info.setStatus(RequestStatus.READY);
            } else {
                info.setStatus(RequestStatus.ERROR);
            }
            shouldFinish = true;
        }

        if (shouldFinish) {
            finishRequest(info);
        }
    }

    private void forceExpire(CrackHashRequestInfo info, String source) {
        boolean removedFromQueue = false;
        synchronized (schedulingLock) {
            for (var it = pendingQueue.iterator(); it.hasNext(); ) {
                if (it.next() == info) {
                    it.remove();
                    removedFromQueue = true;
                    break;
                }
            }
        }

        if (removedFromQueue) {
            synchronized (info) {
                if (info.getStatus() == RequestStatus.IN_PROGRESS) {
                    info.setStatus(RequestStatus.ERROR);
                }
            }
            log.warn("Request [{}] expired by {} while queued -> ERROR", info.getRequestId(), source);
            return;
        }
        boolean shouldFinish = false;
        synchronized (info) {
            if (info.getStatus() != RequestStatus.IN_PROGRESS) {
                return;
            }
            info.setStatus(RequestStatus.ERROR);
            shouldFinish = true;
        }
        log.warn("Request [{}] expired by {} while active -> ERROR (finishing)", info.getRequestId(), source);
        if (shouldFinish) {
            finishRequest(info);
        }
    }
}