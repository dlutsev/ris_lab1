package com.example.crackhash.manager.controller;

import com.example.crackhash.manager.service.CrackHashService;
import com.example.crackhash.manager.dto.WorkerTaskResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/api/manager/hash/crack")
public class InternalManagerController {

    private final CrackHashService service;

    public InternalManagerController(CrackHashService service) {
        this.service = service;
    }

    @PatchMapping("/request")
    public ResponseEntity<Void> handleWorkerResponse(@RequestBody WorkerTaskResponse response) {
        service.handleWorkerResponse(response);
        return ResponseEntity.ok().build();
    }
}