package com.yash.chatbot_rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CsvDataLoader implements CommandLineRunner {

    private final VectorStore vectorStore;

    @Value("classpath:/docs/data.csv")
    private Resource resource;

    @Override
    public void run(String... args) throws Exception {
        if (isDataAlreadyLoaded()) {
            log.info("Data already loaded. Skipping.");
            return;
        }

        log.info("Loading CSV data...");
        List<Document> documents = new ArrayList<>();

        try (CSVParser parser = CSVParser.parse(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {

            int row = 1;
            for (CSVRecord csvRecord : parser) {
                Map<String, String> recordMap = csvRecord.toMap();

                // Build a more meaningful text representation
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

        log.info("Storing {} documents...", documents.size());
        vectorStore.add(documents);
        log.info("CSV ingestion completed!");
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
