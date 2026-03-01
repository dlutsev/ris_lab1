package com.example.crackhash.worker.controller;

import com.example.crackhash.worker.service.BruteforceService;
import com.example.crackhash.worker.dto.WorkerTaskRequest;
import com.example.crackhash.worker.dto.WorkerTaskResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@RestController
@RequestMapping("/internal/api/worker/hash/crack")
public class WorkerController {

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
        List<String> answers = bruteforceService.findMatchingWords(taskRequest);

        WorkerTaskResponse response = new WorkerTaskResponse();
        response.setRequestId(taskRequest.getRequestId());
        response.setPartNumber(taskRequest.getPartNumber());
        response.setPartCount(taskRequest.getPartCount());
        response.setAnswers(answers);

        restTemplate.patchForObject(managerCallbackUrl, response, Void.class);

        return ResponseEntity.ok().build();
    }
}