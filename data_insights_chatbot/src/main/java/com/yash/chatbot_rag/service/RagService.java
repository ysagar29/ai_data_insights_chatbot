package com.yash.chatbot_rag.service;

import com.yash.chatbot_rag.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    @Value("classpath:/prompts/analytics-prompt.txt")
    private Resource promptResource;

    private String promptTemplate;

    public ChatResponse chat(String question) {
        log.info("Question: {}", question);

        var docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(100)
                .build());

        if (docs.isEmpty()) {
            return ChatResponse.builder()
                    .answer("No relevant information found.")
                    .documentsUsed(0)
                    .build();
        }

        String context = docs.stream().map(Document::getText).reduce("", (a, b) -> a + "\n\n" + b);
        String answer = chatModel.call(new PromptTemplate(promptResource)
                .create(Map.of("context", context, "question", question)))
                .getResult().getOutput().getText();

        List<String> sources = docs.stream()
                .map(d -> {
                    String source = (String) d.getMetadata().get("source");
                    String file = (String) d.getMetadata().get("file");

                    if ("conversation".equals(source)) {
                        return "Previous conversation from " + file;
                    } else {
                        Object row = d.getMetadata().get("row");
                        return "Row " + row + " from " + file;
                    }
                })
                .distinct()
                .limit(5)
                .toList();

        // Store the conversation in vector store for future reference
        storeConversation(question, answer);

        return ChatResponse.builder()
                .answer(answer)
                .sources(sources)
                .documentsUsed(docs.size())
                .build();
    }

    private void storeConversation(String question, String answer) {
        try {
            // Create a document with the question and answer
            String conversationText = "Question: " + question + "\n\nAnswer: " + answer;

            Document conversationDoc = new Document(
                    conversationText,
                    Map.of(
                            "source", "conversation",
                            "file", "chat_history",
                            "question", question,
                            "answer", answer,
                            "timestamp", System.currentTimeMillis()
                    )
            );

            vectorStore.add(List.of(conversationDoc));
            log.info("Stored conversation in vector store for future reference");
        } catch (Exception e) {
            log.error("Failed to store conversation in vector store", e);
            // Don't fail the main request if storing conversation fails
        }
    }
}
