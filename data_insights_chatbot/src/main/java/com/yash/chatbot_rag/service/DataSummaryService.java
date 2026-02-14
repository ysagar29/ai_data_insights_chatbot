package com.yash.chatbot_rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataSummaryService {

    private final JdbcTemplate jdbcTemplate;
    private final ChatModel chatModel;

    @Value("classpath:/prompts/data-summary-prompt.txt")
    private Resource dataSummaryPromptResource;

    /**
     * Get comprehensive data summary with sample questions
     */
    public String getDataSummaryWithSuggestions(String tableName, String originalQuestion) {
        try {
            // Get data summary
            String dataSummary = generateDetailedDataSummary(tableName);

            // Generate helpful response with LLM
            return generateHelpfulResponse(dataSummary, originalQuestion);

        } catch (Exception e) {
            log.error("Error generating data summary with suggestions", e);
            return "I couldn't find relevant information for your question. Please try rephrasing or ask about the data we have available.";
        }
    }

    /**
     * Generate detailed data summary
     */
    private String generateDetailedDataSummary(String tableName) {
        try {
            StringBuilder summary = new StringBuilder();

            // Get total count
            String countSql = "SELECT COUNT(*) FROM " + tableName;
            Integer totalRows = jdbcTemplate.queryForObject(countSql, Integer.class);

            // Get column information
            String columnSql = "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position";
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(columnSql, tableName.toLowerCase());

            summary.append("DATA SUMMARY\n");
            summary.append("=============\n\n");
            summary.append("Total Records: ").append(totalRows).append("\n\n");

            summary.append("Available Columns:\n");
            for (Map<String, Object> col : columns) {
                String columnName = (String) col.get("column_name");
                String dataType = (String) col.get("data_type");

                if (!"id".equalsIgnoreCase(columnName)) {
                    summary.append("  • ").append(columnName).append(" (").append(dataType).append(")\n");

                    // Get sample distinct values for text columns (limited to 5)
                    if (dataType.contains("text") || dataType.contains("character")) {
                        try {
                            String distinctSql = "SELECT DISTINCT " + columnName + " FROM " + tableName + " WHERE " + columnName + " IS NOT NULL LIMIT 5";
                            List<Map<String, Object>> distinctValues = jdbcTemplate.queryForList(distinctSql);
                            if (!distinctValues.isEmpty()) {
                                summary.append("    Examples: ");
                                distinctValues.forEach(v -> summary.append(v.get(columnName)).append(", "));
                                summary.setLength(summary.length() - 2); // Remove last comma
                                summary.append("\n");
                            }
                        } catch (Exception e) {
                            // Skip if error
                        }
                    }
                }
            }

            // Get sample records
            summary.append("\nSample Records (first 3):\n");
            String sampleSql = "SELECT * FROM " + tableName + " LIMIT 3";
            List<Map<String, Object>> sampleData = jdbcTemplate.queryForList(sampleSql);
            int recordNum = 1;
            for (Map<String, Object> record : sampleData) {
                summary.append("  Record ").append(recordNum++).append(": ");
                record.forEach((key, value) -> {
                    if (!"id".equalsIgnoreCase(key)) {
                        summary.append(key).append("=").append(value).append(", ");
                    }
                });
                summary.setLength(summary.length() - 2); // Remove last comma
                summary.append("\n");
            }

            return summary.toString();

        } catch (Exception e) {
            log.error("Error generating detailed data summary", e);
            return "Data summary unavailable.";
        }
    }

    /**
     * Generate helpful response using LLM
     */
    private String generateHelpfulResponse(String dataSummary, String originalQuestion) {
        try {
            String promptTemplate = new String(dataSummaryPromptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            String prompt = promptTemplate
                    .replace("{dataSummary}", dataSummary)
                    .replace("{originalQuestion}", originalQuestion);

            log.info("Generating helpful response with data summary");
            String response = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();

            return response;

        } catch (Exception e) {
            log.error("Error generating helpful response", e);
            // Fallback to basic summary
            return "I couldn't find a relevant answer to your question.\n\n" + dataSummary +
                   "\n\nYou can ask questions about any of these columns or request aggregations like counts, averages, etc.";
        }
    }
}

