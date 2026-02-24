package com.example.crackhash.manager.controller;

import com.example.crackhash.manager.api.CrackHashRequestDto;
import com.example.crackhash.manager.api.CrackHashResponseDto;
import com.example.crackhash.manager.api.CrackHashStatusResponseDto;
import com.example.crackhash.manager.service.CrackHashService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/hash")
public class PublicHashController {

    private final CrackHashService service;

    public PublicHashController(CrackHashService service) {
        this.service = service;
    }

    @PostMapping("/crack")
    public ResponseEntity<CrackHashResponseDto> crack(@RequestBody CrackHashRequestDto dto) {
        String requestId = service.createCrackRequest(dto);
        return ResponseEntity.ok(new CrackHashResponseDto(requestId));
    }

    @GetMapping("/status")
    public ResponseEntity<CrackHashStatusResponseDto> status(@RequestParam("requestId") String requestId) {
        CrackHashStatusResponseDto status = service.getStatus(requestId);
        return ResponseEntity.ok(status);
    }
}