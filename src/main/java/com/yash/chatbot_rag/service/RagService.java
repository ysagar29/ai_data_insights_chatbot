package com.yash.chatbot_rag.service;

import com.yash.chatbot_rag.dto.ChatResponse;
import com.yash.chatbot_rag.dto.QueryIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final VectorStore vectorStore;
    private final ChatModel chatModel;
    private final AggregatedQueryService aggregatedQueryService;
    private final IntentClassificationService intentClassificationService;
    private final DataSummaryService dataSummaryService;

    @Value("classpath:/prompts/analytics-prompt.txt")
    private Resource promptResource;

    @Value("${chatbot.topk:3}")
    private int topK;

    public ChatResponse chat(String question) {
        log.info("Processing question: {}", question);

        // Step 1: Classify the intent using LLM
        QueryIntent intent = intentClassificationService.classifyIntent(question);
        log.info("Question intent classified as: {}", intent);

        // Step 2: Route based on intent
        if (intent == QueryIntent.ANALYTICAL) {
            log.info("Processing ANALYTICAL query with SQL...");
            return handleAnalyticalQuery(question);
        } else if (intent == QueryIntent.FACTUAL) {
            log.info("Processing FACTUAL query with RAG...");
            return handleFactualQuery(question);
        } else {
            log.info("Processing NONE (general conversation) query...");
            return handleGeneralConversation(question);
        }
    }

    /**
     * Handle analytical queries that require SQL aggregation
     */
    private ChatResponse handleAnalyticalQuery(String question) {
        try {
            // Get table name (assuming data.csv for now, can be made configurable)
            String tableName = aggregatedQueryService.getTableName("data.csv");

            // Step 3.2: Execute SQL query and get aggregated results
            String aggregatedData = aggregatedQueryService.executeAggregatedQuery(question, tableName);
            log.info("Aggregated data retrieved from SQL query, length: {} characters", aggregatedData);

            // Check if no data was found
            if (aggregatedData.contains("No data found") || aggregatedData.contains("Unable to execute")) {
                log.info("No relevant data found for analytical query, providing data summary");
                String helpfulResponse = dataSummaryService.getDataSummaryWithSuggestions(tableName, question);

                return ChatResponse.builder()
                        .answer(helpfulResponse)
                        .sources(List.of("Data Summary"))
                        .documentsUsed(0)
                        .build();
            }

            // Step 4: Generate final response using LLM with the aggregated data
            String finalAnswer = generateFinalResponse(question, aggregatedData, "SQL aggregation");

            // Store the conversation
            storeConversation(question, finalAnswer);

            return ChatResponse.builder()
                    .answer(finalAnswer)
                    .sources(List.of("SQL Database: " + tableName))
                    .documentsUsed(1)
                    .build();

        } catch (Exception e) {
            log.error("Error handling analytical query, falling back to RAG", e);
            // Fall back to factual RAG if SQL fails
            return handleFactualQuery(question);
        }
    }

    /**
     * Handle factual queries using RAG (semantic similarity search)
     */
    private ChatResponse handleFactualQuery(String question) {
        // Step 3.1: Perform semantic similarity search
        var docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(topK)
                .build());

        if (docs.isEmpty()) {
            log.info("No relevant documents found for factual query, providing data summary");

            // Get table name and provide helpful data summary
            String tableName = aggregatedQueryService.getTableName("data.csv");
            String helpfulResponse = dataSummaryService.getDataSummaryWithSuggestions(tableName, question);

            return ChatResponse.builder()
                    .answer(helpfulResponse)
                    .sources(List.of("Data Summary"))
                    .documentsUsed(0)
                    .build();
        }

        // Retrieve relevant content from embeddings
        String retrievedContent = docs.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n\n" + b);

        // Step 4: Generate final response using LLM with retrieved content
        String finalAnswer = generateFinalResponse(question, retrievedContent, "retrieved documents");

        List<String> sources = docs.stream()
                .map(d -> {
                    String source = (String) d.getMetadata().get("source");
                    String file = (String) d.getMetadata().get("file");

                    if ("conversation".equals(source)) {
                        return "Previous conversation from " + file;
                    } else if ("sql_summary".equals(source)) {
                        return "Database summary from " + file;
                    } else {
                        Object row = d.getMetadata().get("row");
                        return "Row " + row + " from " + file;
                    }
                })
                .distinct()
                .limit(5)
                .toList();

        // Store the conversation in vector store for future reference
        storeConversation(question, finalAnswer);

        return ChatResponse.builder()
                .answer(finalAnswer)
                .sources(sources)
                .documentsUsed(docs.size())
                .build();
    }

    /**
     * Handle general conversation (greetings, casual chat, etc.)
     */
    private ChatResponse handleGeneralConversation(String question) {
        try {
            log.info("Handling general conversation");

            // Create a simple conversational prompt
            String conversationPrompt = "You are a friendly AI chatbot assistant that helps analyze data. " +
                    "The user is having a general conversation with you. Respond naturally and helpfully.\n\n" +
                    "User: " + question;

            Prompt prompt = new Prompt(conversationPrompt);
            String answer = chatModel.call(prompt).getResult().getOutput().getText();

            return ChatResponse.builder()
                    .answer(answer)
                    .sources(List.of("General Conversation"))
                    .documentsUsed(0)
                    .build();
        } catch (Exception e) {
            log.error("Error handling general conversation", e);
            return ChatResponse.builder()
                    .answer("Hello! I'm your data insights assistant. How can I help you analyze your data today?")
                    .sources(List.of("General Conversation"))
                    .documentsUsed(0)
                    .build();
        }
    }

    /**
     * Generate final response using LLM with analytics prompt
     */
    private String generateFinalResponse(String question, String context, String contextType) {
        try {
            log.info("Generating final response using analytics prompt for {}", contextType);
            log.debug("Context length: {} characters", context.length());
            log.debug("Question: {}", question);

            // Load the analytics prompt template
            String promptTemplateContent = new String(promptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // Replace placeholders in the prompt template
            String systemPrompt = promptTemplateContent
                    .replace("{context}", context)
                    .replace("{question}", question);

            log.debug("System prompt preview (first 500 chars): {}",
                     systemPrompt.length() > 500 ? systemPrompt.substring(0, 500) + "..." : systemPrompt);

            // Create messages with system prompt and user question
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            messages.add(new UserMessage("Please analyze the above data and answer the question."));

            // Create prompt with messages
            Prompt prompt = new Prompt(messages);

            String answer = chatModel.call(prompt)
                    .getResult()
                    .getOutput()
                    .getText();

            log.info("Generated answer length: {} characters", answer != null ? answer.length() : 0);
            return answer;
        } catch (IOException e) {
            log.error("Error loading analytics prompt template", e);
            return "I encountered an error loading the prompt template: " + e.getMessage();
        } catch (Exception e) {
            log.error("Error generating final response", e);
            return "I encountered an error generating the response: " + e.getMessage();
        }
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
