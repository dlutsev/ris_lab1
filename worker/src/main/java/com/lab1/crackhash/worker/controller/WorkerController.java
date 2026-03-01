package com.lab1.crackhash.worker.controller;

import com.lab1.crackhash.worker.service.BruteforceService;
import com.lab1.crackhash.common.dto.WorkerTaskRequest;
import com.lab1.crackhash.common.dto.WorkerTaskResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@RestController
@RequestMapping("/internal/api/worker/hash/crack")
public class WorkerController {

    private static final Logger log = LoggerFactory.getLogger(WorkerController.class);
    private final BruteforceService bruteforceService;
    private final RestTemplate restTemplate;
    private final String managerCallbackUrl;

    public WorkerController(BruteforceService bruteforceService,
                            RestTemplate restTemplate,
                            @Value("${crackhash.manager-callback-url}") String managerCallbackUrl) {
        this.bruteforceService = bruteforceService;
        this.restTemplate = restTemplate;
        this.managerCallbackUrl = managerCallbackUrl;
    }

    @PostMapping("/task")
    public ResponseEntity<Void> handleTask(@RequestBody WorkerTaskRequest taskRequest) {
        log.info("Task received: requestId={}, part {}/{}, hash={}, maxLength={}, alphabetSize={}",
                taskRequest.getRequestId(), taskRequest.getPartNumber(), taskRequest.getPartCount(),
                taskRequest.getHash(), taskRequest.getMaxLength(),
                taskRequest.getAlphabet() != null ? taskRequest.getAlphabet().length() : 0);
        List<String> answers = bruteforceService.findMatchingWords(taskRequest);

        WorkerTaskResponse response = new WorkerTaskResponse();
        response.setRequestId(taskRequest.getRequestId());
        response.setPartNumber(taskRequest.getPartNumber());
        response.setPartCount(taskRequest.getPartCount());
        response.setAnswers(answers);

        log.info("Task [{}] part {}/{} finished, answers={}, sending to manager", taskRequest.getRequestId(), taskRequest.getPartNumber(), taskRequest.getPartCount(), answers);
        restTemplate.patchForObject(managerCallbackUrl, response, Void.class);

        return ResponseEntity.ok().build();
    }
}