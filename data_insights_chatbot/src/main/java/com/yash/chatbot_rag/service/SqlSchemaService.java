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
public class SqlSchemaService {

    private final ChatModel chatModel;
    private final JdbcTemplate jdbcTemplate;

    @Value("classpath:/prompts/schema-generation-prompt.txt")
    private Resource schemaPromptResource;

    @Value("classpath:/prompts/insert-generation-prompt.txt")
    private Resource insertPromptResource;

    /**
     * Generate SQL table schema from CSV headers using LLM
     */
    public String generateSchema(String tableName, List<String> headers, List<Map<String, String>> sampleData) {
        try {
            String promptTemplate = new String(schemaPromptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // Format sample data for context
            StringBuilder sampleDataStr = new StringBuilder();
            int count = 0;
            for (Map<String, String> row : sampleData) {
                if (count++ >= 5) break; // Only first 5 rows as sample
                sampleDataStr.append(row.toString()).append("\n");
            }

            String prompt = promptTemplate
                    .replace("{tableName}", tableName)
                    .replace("{headers}", String.join(", ", headers))
                    .replace("{sampleData}", sampleDataStr.toString());

            log.info("Generating schema for table: {}", tableName);
            String schema = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();

            // Extract SQL from response (remove markdown if present)
            schema = extractSql(schema);

            log.info("Generated schema: {}", schema);
            return schema;
        } catch (Exception e) {
            log.error("Error generating schema", e);
            throw new RuntimeException("Failed to generate schema", e);
        }
    }

    /**
     * Generate SQL INSERT statement from data using LLM
     */
    public String generateInsertQuery(String tableName, Map<String, String> data, String tableSchema) {
        try {
            String promptTemplate = new String(insertPromptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            String prompt = promptTemplate
                    .replace("{tableName}", tableName)
                    .replace("{tableSchema}", tableSchema)
                    .replace("{data}", data.toString());

            log.debug("Generating INSERT query for table: {}", tableName);
            String insertQuery = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();

            // Extract SQL from response
            insertQuery = extractSql(insertQuery);

            log.debug("Generated INSERT query: {}", insertQuery);
            return insertQuery;
        } catch (Exception e) {
            log.error("Error generating INSERT query", e);
            throw new RuntimeException("Failed to generate INSERT query", e);
        }
    }

    /**
     * Create table in database
     */
    public void createTable(String createTableSql) {
        try {
            jdbcTemplate.execute(createTableSql);
            log.info("Table created successfully");
        } catch (Exception e) {
            log.error("Error creating table", e);
            throw new RuntimeException("Failed to create table", e);
        }
    }

    /**
     * Execute INSERT query
     */
    public void executeInsert(String insertSql) {
        try {
            jdbcTemplate.execute(insertSql);
            log.debug("Data inserted successfully");
        } catch (Exception e) {
            log.error("Error inserting data: {}", insertSql, e);
            throw new RuntimeException("Failed to insert data", e);
        }
    }

    /**
     * Check if table exists
     */
    public boolean tableExists(String tableName) {
        try {
            String sql = "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?)";
            Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, tableName.toLowerCase());
            return exists != null && exists;
        } catch (Exception e) {
            log.error("Error checking table existence", e);
            return false;
        }
    }

    /**
     * Generate data summary using LLM
     */
    public String generateDataSummary(String tableName) {
        try {
            // Get table statistics
            String countSql = "SELECT COUNT(*) FROM " + tableName;
            Integer totalRows = jdbcTemplate.queryForObject(countSql, Integer.class);

            // Get column names
            String columnSql = "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ?";
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(columnSql, tableName.toLowerCase());

            // Sample data
            String sampleSql = "SELECT * FROM " + tableName + " LIMIT 10";
            List<Map<String, Object>> sampleData = jdbcTemplate.queryForList(sampleSql);

            // Build summary
            StringBuilder summary = new StringBuilder();
            summary.append("Table: ").append(tableName).append("\n");
            summary.append("Total Rows: ").append(totalRows).append("\n");
            summary.append("Columns: \n");
            for (Map<String, Object> col : columns) {
                summary.append("  - ").append(col.get("column_name"))
                       .append(" (").append(col.get("data_type")).append(")\n");
            }
            summary.append("\nSample Data (first 10 rows):\n");
            summary.append(sampleData.toString());

            log.info("Generated data summary for table: {}", tableName);
            return summary.toString();
        } catch (Exception e) {
            log.error("Error generating data summary", e);
            return "Unable to generate summary: " + e.getMessage();
        }
    }

    /**
     * Extract SQL from LLM response (remove markdown code blocks)
     */
    private String extractSql(String response) {
        if (response == null) return "";

        // Remove markdown code blocks
        response = response.replaceAll("```sql\\n?", "");
        response = response.replaceAll("```\\n?", "");
        response = response.trim();

        return response;
    }

    /**
     * Drop table if exists (for testing/reset)
     */
    public void dropTable(String tableName) {
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName + " CASCADE");
            log.info("Table {} dropped", tableName);
        } catch (Exception e) {
            log.error("Error dropping table", e);
        }
    }
}

