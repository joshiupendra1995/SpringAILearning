package com.springai.fundamentals.controller;

import com.springai.fundamentals.mcp.McpRemoteTools;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpRemoteTools remoteTools;

    @GetMapping
    public Mono<McpStatus> status() {
        return Mono.fromSupplier(() -> new McpStatus(remoteTools.isEnabled(), remoteTools.getToolNames()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public record McpStatus(boolean enabled, List<String> tools) {
    }
}
