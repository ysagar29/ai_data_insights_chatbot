package com.yash.chatbot_rag.controller;

import com.yash.chatbot_rag.dto.ChatRequest;
import com.yash.chatbot_rag.dto.ChatResponse;
import com.yash.chatbot_rag.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final RagService ragService;

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return ragService.chat(request.getQuestion());
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
