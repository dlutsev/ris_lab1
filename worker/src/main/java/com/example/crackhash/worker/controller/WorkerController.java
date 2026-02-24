package com.example.crackhash.worker.controller;

import com.example.crackhash.worker.service.BruteforceService;
import com.example.crackhash.worker.xml.CrackHashTaskRequest;
import com.example.crackhash.worker.xml.CrackHashWorkerResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
    public ResponseEntity<Void> handleTask(@RequestBody CrackHashTaskRequest taskRequest) {
        List<String> answers = bruteforceService.findMatchingWords(taskRequest);

        CrackHashWorkerResponse response = new CrackHashWorkerResponse();
        response.setRequestId(taskRequest.getRequestId());
        response.setPartNumber(taskRequest.getPartNumber());
        response.setPartCount(taskRequest.getPartCount());
        response.setAnswers(answers);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<CrackHashWorkerResponse> entity = new HttpEntity<>(response, headers);

        restTemplate.patchForObject(managerCallbackUrl, entity, Void.class);

        return ResponseEntity.ok().build();
    }
}