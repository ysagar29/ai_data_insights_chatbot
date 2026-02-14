package com.yash.chatbot_rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AggregatedQueryService {

    private final ChatModel chatModel;
    private final JdbcTemplate jdbcTemplate;

    @Value("classpath:/prompts/sql-generation-prompt.txt")
    private Resource sqlGenerationPromptResource;

    /**
     * Execute aggregated query using SQL and return raw aggregated data
     */
    public String executeAggregatedQuery(String question, String tableName) {
        try {
            // Generate SQL query from natural language question
            String sqlQuery = generateSqlFromQuestion(question, tableName);
            log.info("Generated SQL query: {}", sqlQuery);

            // Execute SQL query
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sqlQuery);

            if (results.isEmpty()) {
                return "No data found for the query.";
            }

            // Return raw aggregated data with SQL query context for LLM to understand filters
            return formatAggregatedData(results, sqlQuery);

        } catch (Exception e) {
            log.error("Error executing aggregated query", e);
            return "Unable to execute query: " + e.getMessage();
        }
    }

    /**
     * Format aggregated data into structured text for LLM
     * IMPORTANT: Includes SQL query to provide context about filters/WHERE clauses
     */
    private String formatAggregatedData(List<Map<String, Object>> results, String sqlQuery) {
        StringBuilder data = new StringBuilder();

        // IMPORTANT: Add the SQL query so AI understands filters (WHERE clause, etc.)
        data.append("SQL Query Executed:\n");
        data.append(sqlQuery).append("\n\n");
        data.append("IMPORTANT: The results below are ALREADY FILTERED by the WHERE clause in the SQL query above.\n");
        data.append("Do NOT say the data doesn't contain information about filters - the results ARE filtered!\n\n");

        data.append("Query Results:\n");
        data.append("=".repeat(80)).append("\n");

        if (results.isEmpty()) {
            return "No data found.";
        }

        // Add column headers
        List<String> columnNames = new ArrayList<>(results.get(0).keySet());
        data.append("Columns: ").append(String.join(", ", columnNames)).append("\n\n");

        // Format data in a more readable table-like structure
        data.append("Results:\n");
        data.append("-".repeat(80)).append("\n");

        int count = 0;
        for (Map<String, Object> row : results) {
            if (count++ >= 100) { // Limit to first 100 rows
                data.append("... and ").append(results.size() - 100).append(" more rows\n");
                break;
            }

            // Format each row with column name: value pairs for clarity
            List<String> rowParts = new ArrayList<>();
            for (String columnName : columnNames) {
                Object value = row.get(columnName);
                rowParts.add(columnName + "=" + (value != null ? value.toString() : "null"));
            }
            data.append(String.join(", ", rowParts)).append("\n");
        }

        data.append("-".repeat(80)).append("\n");
        data.append("Total Rows Returned: ").append(results.size()).append("\n");

        return data.toString();
    }

    /**
     * Generate SQL query from natural language question using LLM
     */
    private String generateSqlFromQuestion(String question, String tableName) {
        try {
            // Get table schema
            String schemaSql = "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position";
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(schemaSql, tableName.toLowerCase());

            StringBuilder schemaInfo = new StringBuilder();
            schemaInfo.append("Table: ").append(tableName).append("\n");
            schemaInfo.append("Columns:\n");
            for (Map<String, Object> col : columns) {
                schemaInfo.append("  - ").append(col.get("column_name"))
                         .append(" (").append(col.get("data_type")).append(")\n");
            }

            // Load prompt template from file
            String promptTemplate = new String(sqlGenerationPromptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // Replace placeholders
            String prompt = promptTemplate
                    .replace("{schema}", schemaInfo.toString())
                    .replace("{question}", question);

            String sqlQuery = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();

            // Clean up the response
            sqlQuery = sqlQuery.replaceAll("```sql\\n?", "");
            sqlQuery = sqlQuery.replaceAll("```\\n?", "");
            sqlQuery = sqlQuery.trim();

            // Remove trailing semicolon if present
            if (sqlQuery.endsWith(";")) {
                sqlQuery = sqlQuery.substring(0, sqlQuery.length() - 1);
            }

            return sqlQuery;

        } catch (IOException e) {
            log.error("Error loading SQL generation prompt template", e);
            throw new RuntimeException("Failed to load SQL generation prompt", e);
        } catch (Exception e) {
            log.error("Error generating SQL query", e);
            throw new RuntimeException("Failed to generate SQL query", e);
        }
    }


    /**
     * Get table name from CSV file
     */
    public String getTableName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "csv_data_default";
        }
        return "csv_data_" + fileName.replace(".csv", "").replaceAll("[^a-zA-Z0-9_]", "_");
    }
}


