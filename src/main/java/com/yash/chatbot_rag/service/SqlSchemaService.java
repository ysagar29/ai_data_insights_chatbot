package com.yash.chatbot_rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlSchemaService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Generate SQL table schema directly from CSV headers and sample data
     */
    public String generateSchema(String tableName, List<String> headers, List<Map<String, String>> sampleData) {
        try {
            log.info("Generating schema for table: {}", tableName);

            StringBuilder schema = new StringBuilder();
            schema.append("CREATE TABLE ").append(tableName).append(" (\n");
            schema.append("  id SERIAL PRIMARY KEY,\n");

            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                String columnName = sanitizeColumnName(header);
                String dataType = inferDataType(header, sampleData);

                schema.append("  ").append(columnName).append(" ").append(dataType);
                if (i < headers.size() - 1) {
                    schema.append(",");
                }
                schema.append("\n");
            }

            schema.append(")");

            log.info("Generated schema: {}", schema);
            return schema.toString();
        } catch (Exception e) {
            log.error("Error generating schema", e);
            throw new RuntimeException("Failed to generate schema", e);
        }
    }

    /**
     * Generate SQL INSERT statement directly from data
     */
    public String generateInsertQuery(String tableName, Map<String, String> data, String tableSchema) {
        try {
            List<String> columns = new ArrayList<>();
            List<String> values = new ArrayList<>();

            for (Map.Entry<String, String> entry : data.entrySet()) {
                String columnName = sanitizeColumnName(entry.getKey());
                String value = entry.getValue();

                columns.add(columnName);
                values.add(escapeSqlValue(value));
            }

            String insertQuery = String.format(
                "INSERT INTO %s (%s) VALUES (%s)",
                tableName,
                String.join(", ", columns),
                String.join(", ", values)
            );

            log.debug("Generated INSERT query: {}", insertQuery);
            return insertQuery;
        } catch (Exception e) {
            log.error("Error generating INSERT query", e);
            throw new RuntimeException("Failed to generate INSERT query", e);
        }
    }

    /**
     * Sanitize column name for SQL (remove spaces, special chars)
     */
    private String sanitizeColumnName(String columnName) {
        return columnName.toLowerCase()
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("^_+|_+$", ""); // trim leading/trailing underscores
    }

    /**
     * Infer SQL data type from column name and sample data
     */
    private String inferDataType(String header, List<Map<String, String>> sampleData) {
        // Check sample data values for this column
        for (Map<String, String> row : sampleData) {
            String value = row.get(header);
            if (value == null || value.isBlank()) {
                continue;
            }

            // Try to infer type from value
            if (isInteger(value)) {
                return "INTEGER";
            } else if (isDouble(value)) {
                return "DOUBLE PRECISION";
            } else if (isBoolean(value)) {
                return "BOOLEAN";
            } else if (isDate(value)) {
                return "TIMESTAMP";
            }
        }

        // Default to TEXT for strings
        return "TEXT";
    }

    private boolean isInteger(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isDouble(String value) {
        try {
            Double.parseDouble(value);
            return value.contains(".");
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isBoolean(String value) {
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
    }

    private boolean isDate(String value) {
        // Simple check for ISO date format or common date patterns
        return value.matches("\\d{4}-\\d{2}-\\d{2}.*") ||
               value.matches("\\d{2}/\\d{2}/\\d{4}.*");
    }

    /**
     * Convert date from DD/MM/YYYY to YYYY-MM-DD format
     */
    private String convertDateFormat(String dateValue) {
        try {
            // Handle DD/MM/YYYY format
            if (dateValue.matches("\\d{2}/\\d{2}/\\d{4}.*")) {
                String[] parts = dateValue.split("/");
                if (parts.length >= 3) {
                    String day = parts[0];
                    String month = parts[1];
                    String year = parts[2].substring(0, 4); // Handle timestamps
                    return year + "-" + month + "-" + day;
                }
            }
            // Already in YYYY-MM-DD format
            return dateValue;
        } catch (Exception e) {
            log.warn("Failed to convert date format: {}", dateValue);
            return dateValue;
        }
    }

    /**
     * Escape SQL value for INSERT statement
     */
    private String escapeSqlValue(String value) {
        if (value == null || value.isBlank()) {
            return "NULL";
        }

        // Check if it's a number
        if (isInteger(value) || isDouble(value)) {
            return value;
        }

        // Check if it's a boolean
        if (isBoolean(value)) {
            return value.toUpperCase();
        }

        // Check if it's a date and convert format
        if (isDate(value)) {
            String convertedDate = convertDateFormat(value);
            return "'" + convertedDate + "'";
        }

        // Escape single quotes and wrap in quotes for strings
        return "'" + value.replace("'", "''") + "'";
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
     * Generate data summary
     */
    public String generateDataSummary(String tableName) {
        try {
            // Get table statistics
            String countSql = "SELECT COUNT(*) FROM " + tableName;
            Integer totalRows = jdbcTemplate.queryForObject(countSql, Integer.class);

            // Get column names
            String columnSql = "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position";
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

