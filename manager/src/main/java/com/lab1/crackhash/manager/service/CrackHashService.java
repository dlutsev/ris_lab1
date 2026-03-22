package com.lab1.crackhash.manager.service;

import com.lab1.crackhash.common.dto.WorkerTaskRequest;
import com.lab1.crackhash.common.dto.WorkerTaskResponse;
import com.lab1.crackhash.manager.dto.CrackHashRequestDto;
import com.lab1.crackhash.manager.dto.CrackHashStatusResponseDto;
import com.lab1.crackhash.manager.model.CrackHashRequestInfo;
import com.lab1.crackhash.manager.model.PendingCrackRequest;
import com.lab1.crackhash.manager.model.RequestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
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
    private final Map<String, CrackHashStatusResponseDto> requestStatusById = new ConcurrentHashMap<>();
    private final String alphabet;
    private final int workerCount;
    private final List<String> workerBaseUrls;
    private final int queueCapacity;
    private final int cacheCapacity;
    private final Object schedulingLock = new Object();
    private int activeRequests = 0;
    private CrackHashRequestInfo activeRequest = null;
    private final Deque<PendingCrackRequest> pendingQueue = new ArrayDeque<>();
    private final Map<String, CrackHashStatusResponseDto> cache;

    public CrackHashService(RestTemplate restTemplate,
                            Executor workerTasksExecutor,
                            @Value("${crackhash.alphabet:abcdefghijklmnopqrstuvwxyz0123456789}") String alphabet,
                            @Value("${crackhash.worker-count}") int workerCount,
                            @Value("${crackhash.worker-base-urls}") String workerBaseUrls,
                            @Value("${crackhash.queue-capacity:100}") int queueCapacity,
                            @Value("${crackhash.cache-capacity:100}") int cacheCapacity) {
        this.restTemplate = restTemplate;
        this.executor = workerTasksExecutor;
        this.alphabet = alphabet;
        this.workerCount = workerCount;
        this.workerBaseUrls = parseWorkerBaseUrls(workerBaseUrls);
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
            requestStatusById.put(cachedRequestId, cached);
            log.info("Cache hit for hash={}, maxLength={}, requestId={}", dto.getHash(), dto.getMaxLength(), cachedRequestId);
            return cachedRequestId;
        }

        String requestId = UUID.randomUUID().toString();
        requestStatusById.put(requestId, new CrackHashStatusResponseDto("IN_PROGRESS", null));

        boolean shouldStartNow = false;
        boolean rejected = false;
        int queueSize;
        int active;
        synchronized (schedulingLock) {
            if (activeRequests < MAX_CONCURRENT_REQUESTS) {
                activeRequests++;
                shouldStartNow = true;
            } else if (pendingQueue.size() < queueCapacity) {
                pendingQueue.offer(new PendingCrackRequest(requestId, dto.getHash(), dto.getMaxLength()));
            } else {
                requestStatusById.put(requestId, new CrackHashStatusResponseDto("ERROR", null));
                rejected = true;
            }
            queueSize = pendingQueue.size();
            active = activeRequests;
        }

        if (rejected) {
            log.warn("Request [{}] rejected: scheduler full (active={}/{}, queueSize={}/{})",
                    requestId, active, MAX_CONCURRENT_REQUESTS, queueSize, queueCapacity);
            return requestId;
        }

        if (shouldStartNow) {
            CrackHashRequestInfo info = new CrackHashRequestInfo(
                    requestId,
                    dto.getHash(),
                    dto.getMaxLength(),
                    workerCount
            );
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
        log.info("Request [{}] sending part {}/{} to worker {}", info.getRequestId(), partNumber + 1, info.getPartCount(), baseUrl);

        try {
            restTemplate.postForEntity(url, entity, Void.class);
        } catch (Exception ex) {
            log.error("Request [{}] part {}/{} failed to send to {}: {}", info.getRequestId(), partNumber + 1, info.getPartCount(), baseUrl, ex.getMessage());
            synchronized (info) {
                if (info.getStatus() != RequestStatus.IN_PROGRESS) {
                    return;
                }
            }
            if (ex.getCause() instanceof SocketTimeoutException && ex.getMessage().contains("Read timed out")) {
                failEntireRequestAsError(info, "read_timeout (RestTemplate readTimeout = request-ttl-millis)");
                return;
            }
            info.incrementFailedParts();
            tryFinalizeIfDone(info);
        }
    }

    private void failEntireRequestAsError(CrackHashRequestInfo info, String reason) {
        boolean shouldFinish = false;
        synchronized (info) {
            if (info.getStatus() != RequestStatus.IN_PROGRESS) {
                return;
            }
            info.setStatus(RequestStatus.ERROR);
            shouldFinish = true;
        }
        log.warn("Request [{}] -> ERROR ({})", info.getRequestId(), reason);
        if (shouldFinish) {
            finishRequest(info);
        }
    }

    public void handleWorkerResponse(WorkerTaskResponse response) {
        CrackHashRequestInfo info;
        synchronized (schedulingLock) {
            if (activeRequest == null || !activeRequest.getRequestId().equals(response.getRequestId())) {
                return;
            }
            info = activeRequest;
        }
        synchronized (info) {
            if (info.getStatus() != RequestStatus.IN_PROGRESS) {
                return;
            }
        }
        info.addAnswers(response.getAnswers());
        int completed = info.incrementCompletedParts();
        log.info("Request [{}] worker response: part {}/{}, answers={}, completedParts={}/{}",
                response.getRequestId(), response.getPartNumber(), response.getPartCount(), response.getAnswers(), completed, info.getPartCount());
        tryFinalizeIfDone(info);
    }

    public CrackHashStatusResponseDto getStatus(String requestId) {
        CrackHashStatusResponseDto stored = requestStatusById.get(requestId);
        if (stored == null) {
            return new CrackHashStatusResponseDto("ERROR", null);
        }
        return stored;
    }

    private void finishRequest(CrackHashRequestInfo info) {
        requestStatusById.put(info.getRequestId(), toClientDto(info));
        if (info.getStatus() == RequestStatus.READY) {
            cache.put(cacheKey(info.getHash(), info.getMaxLength()),
                    new CrackHashStatusResponseDto(info.getStatus().name(), copyAnswersList(info)));
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
                PendingCrackRequest pending = pendingQueue.poll();
                if (pending == null) {
                    break;
                }

                CrackHashStatusResponseDto cached = cache.get(cacheKey(pending.getHash(), pending.getMaxLength()));
                if (cached != null && ("READY".equals(cached.getStatus()))) {
                    requestStatusById.put(pending.getRequestId(), cached);
                    log.info("Dequeued request [{}] served from cache, status=READY", pending.getRequestId());
                    continue;
                }
                nextToStart = new CrackHashRequestInfo(
                        pending.getRequestId(),
                        pending.getHash(),
                        pending.getMaxLength(),
                        workerCount
                );
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

    private static List<String> copyAnswersList(CrackHashRequestInfo info) {
        return new ArrayList<>(info.getAnswers());
    }

    private static CrackHashStatusResponseDto toClientDto(CrackHashRequestInfo info) {
        RequestStatus s = info.getStatus();
        if (s == RequestStatus.READY) {
            return new CrackHashStatusResponseDto("READY", copyAnswersList(info));
        }
        if (s == RequestStatus.PARTIAL_RESULT) {
            return new CrackHashStatusResponseDto("PARTIAL_RESULT", copyAnswersList(info));
        }
        return new CrackHashStatusResponseDto("ERROR", null);
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
}
