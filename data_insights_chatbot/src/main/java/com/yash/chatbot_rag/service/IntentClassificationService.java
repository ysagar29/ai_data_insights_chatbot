package com.yash.chatbot_rag.service;

import com.yash.chatbot_rag.dto.QueryIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentClassificationService {

    private final ChatModel chatModel;

    @Value("classpath:/prompts/intent-classification-prompt.txt")
    private Resource intentPromptResource;

    /**
     * Classify the user's question intent using LLM
     */
    public QueryIntent classifyIntent(String question) {
        try {
            String promptTemplate = new String(intentPromptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String prompt = promptTemplate.replace("{question}", question);

            log.info("Classifying intent for question: {}", question);
            String response = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText().trim().toUpperCase();

            log.info("LLM classified intent as: {}", response);

            // Parse the response
            if (response.contains("ANALYTICAL")) {
                return QueryIntent.ANALYTICAL;
            } else if (response.contains("FACTUAL")) {
                return QueryIntent.FACTUAL;
            } else {
                log.warn("Unable to parse intent from LLM response: {}. Defaulting to FACTUAL", response);
                return QueryIntent.FACTUAL; // Default to factual if unclear
            }
        } catch (Exception e) {
            log.error("Error classifying intent, defaulting to FACTUAL", e);
            return QueryIntent.FACTUAL; // Default to factual on error
        }
    }
}

