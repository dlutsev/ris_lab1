package com.example.crackhash.manager.controller;

import com.example.crackhash.manager.service.CrackHashService;
import com.example.crackhash.common.dto.WorkerTaskResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/api/manager/hash/crack")
public class InternalManagerController {

    private static final Logger log = LoggerFactory.getLogger(InternalManagerController.class);
    private final CrackHashService service;

    public InternalManagerController(CrackHashService service) {
        this.service = service;
    }

    @PatchMapping("/request")
    public ResponseEntity<Void> handleWorkerResponse(@RequestBody WorkerTaskResponse response) {
        log.info("Worker callback: requestId={}, part {}/{}, answers={}",
                response.getRequestId(), response.getPartNumber(), response.getPartCount(), response.getAnswers());
        service.handleWorkerResponse(response);
        return ResponseEntity.ok().build();
    }
}