package com.lab1.crackhash.manager.controller;

import com.lab1.crackhash.manager.api.CrackHashRequestDto;
import com.lab1.crackhash.manager.api.CrackHashResponseDto;
import com.lab1.crackhash.manager.api.CrackHashStatusResponseDto;
import com.lab1.crackhash.manager.service.CrackHashService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/hash")
public class PublicHashController {

    private static final Logger log = LoggerFactory.getLogger(PublicHashController.class);
    private final CrackHashService service;

    public PublicHashController(CrackHashService service) {
        this.service = service;
    }

    @PostMapping("/crack")
    public ResponseEntity<CrackHashResponseDto> crack(@RequestBody CrackHashRequestDto dto) {
        log.info("Crack request received: hash={}, maxLength={}", dto.getHash(), dto.getMaxLength());
        String requestId = service.createCrackRequest(dto);
        log.info("Crack request created: requestId={}", requestId);
        return ResponseEntity.ok(new CrackHashResponseDto(requestId));
    }

    @GetMapping("/status")
    public ResponseEntity<CrackHashStatusResponseDto> status(@RequestParam("requestId") String requestId) {
        log.debug("Status requested: requestId={}", requestId);
        CrackHashStatusResponseDto status = service.getStatus(requestId);
        return ResponseEntity.ok(status);
    }
}