package com.yash.chatbot_rag;

import com.yash.chatbot_rag.service.SqlSchemaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(CsvLoaderProperties.class)
public class CsvDataLoader implements CommandLineRunner {

    private final VectorStore vectorStore;
    private final SqlSchemaService sqlSchemaService;

    @Value("classpath:/docs/data.csv")
    private Resource resource;

    @Autowired
    private CsvLoaderProperties csvLoaderProperties;

    @Value("${chatbot.sql.batch-size:20}")
    private int sqlBatchSize;

    @Override
    public void run(String... args) {
        // Removed custom cleanup logic. Use vectorstore.pgvector.initialize-schema property for vector DB cleanup.
        if (!csvLoaderProperties.isIngestOnStartup()) {
            log.info("CSV ingestion on startup is disabled by config. Skipping.");
            return;
        }
        asyncIngest();
    }

    @Async
    public void asyncIngest() {
        try {
            if (isDataAlreadyLoaded()) {
                log.info("Data already loaded. Skipping.");
                return;
            }
            log.info("Loading CSV data asynchronously...");
            List<Document> documents = new ArrayList<>();
            List<Map<String, String>> allRecords = new ArrayList<>();
            List<String> headers;

            try (CSVParser parser = CSVParser.parse(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8),
                    CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {

                // Get headers
                headers = new ArrayList<>(parser.getHeaderNames());
                log.info("CSV Headers: {}", headers);

                int row = 1;
                for (CSVRecord csvRecord : parser) {
                    Map<String, String> recordMap = csvRecord.toMap();
                    allRecords.add(recordMap);

                    // Build a more meaningful text representation for vector DB
                    StringBuilder textBuilder = new StringBuilder();
                    recordMap.forEach((key, value) -> {
                        if (value != null && !value.isBlank()) {
                            textBuilder.append(key).append(": ").append(value).append(". ");
                        }
                    });

                    String text = textBuilder.toString().trim();

                    // Clean and validate text - must be at least 10 characters and valid UTF-8
                    text = cleanText(text);

                    if (text.length() >= 10) {
                        documents.add(new Document(text, Map.of(
                                "row", row,
                                "source", "csv",
                                "file", resource.getFilename() != null ? resource.getFilename() : "data.csv")));
                    }
                    row++;
                }
            }

            if (documents.isEmpty()) {
                log.warn("No valid documents to store. Check CSV content.");
                return;
            }

            // Store in Vector DB
            log.info("Storing {} documents in Vector DB...", documents.size());
            vectorStore.add(documents);
            log.info("Vector DB ingestion completed!");

            // Store in SQL DB for aggregated queries
            String tableName = "csv_data_" + (resource.getFilename() != null ?
                    resource.getFilename().replace(".csv", "").replaceAll("[^a-zA-Z0-9_]", "_") :
                    "default");

            storeCsvDataInSql(tableName, headers, allRecords);

            log.info("CSV ingestion completed (async)!");
        } catch (Exception e) {
            log.error("Error during async CSV ingestion", e);
        }
    }

    private String cleanText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        // Remove control characters and non-printable characters
        text = text.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");

        // Normalize whitespace
        text = text.replaceAll("\\s+", " ").trim();

        // Ensure it's valid UTF-8
        return text;
    }

    /**
     * Store CSV data in SQL database for aggregated queries
     */
    private void storeCsvDataInSql(String tableName, List<String> headers, List<Map<String, String>> records) {
        try {
            log.info("Storing CSV data in SQL database...");
            if (sqlSchemaService.tableExists(tableName)) {
                log.info("Table {} already exists. Skipping SQL ingestion.", tableName);
                return;
            }

            // Generate table schema using LLM
            log.info("Generating table schema using LLM...");
            String schema = sqlSchemaService.generateSchema(tableName, headers, records.subList(0, Math.min(5, records.size())));

            // Create table
            log.info("Creating table: {}", tableName);
            sqlSchemaService.createTable(schema);

            // Insert data using LLM-generated queries
            log.info("Inserting {} records into SQL database...", records.size());
            // Batch insert optimization
            int batchSize = sqlBatchSize;
            int successCount = 0;
            List<String> batchQueries = new ArrayList<>();
            for (int i = 0; i < records.size(); i++) {
                try {
                    Map<String, String> record = records.get(i);
                    String insertQuery = sqlSchemaService.generateInsertQuery(tableName, record, schema);
                    batchQueries.add(insertQuery);
                    if (batchQueries.size() == batchSize || i == records.size() - 1) {
                        for (String q : batchQueries) sqlSchemaService.executeInsert(q);
                        successCount += batchQueries.size();
                        log.info("Inserted {} / {} records", successCount, records.size());
                        batchQueries.clear();
                    }
                } catch (Exception e) {
                    log.error("Failed to insert record {}: {}", i + 1, e.getMessage());
                }
            }
            log.info("Successfully inserted {} / {} records into SQL database", successCount, records.size());

            // Generate and store data summary
            log.info("Generating data summary...");
            String summary = sqlSchemaService.generateDataSummary(tableName);

            // Store summary as a document in vector DB for easy retrieval
            Document summaryDoc = new Document(
                    "Database Summary for " + tableName + ":\n" + summary,
                    Map.of(
                            "source", "sql_summary",
                            "file", resource.getFilename() != null ? resource.getFilename() : "data.csv",
                            "table_name", tableName,
                            "type", "aggregated_data_summary"
                    )
            );
            vectorStore.add(List.of(summaryDoc));
            log.info("Data summary stored in vector DB");

        } catch (Exception e) {
            log.error("Error storing CSV data in SQL database", e);
        }
    }

    private boolean isDataAlreadyLoaded() {
        try {
            var results = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("test")
                    .topK(1)
                    .build());
            return !results.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}

@Configuration
@EnableAsync
class AsyncConfig {}

@ConfigurationProperties(prefix = "chatbot")
class CsvLoaderProperties {
    private boolean ingestOnStartup = true;
    // Removed cleanupOnStartup property
    public boolean isIngestOnStartup() { return ingestOnStartup; }
    public void setIngestOnStartup(boolean ingestOnStartup) { this.ingestOnStartup = ingestOnStartup; }
}
