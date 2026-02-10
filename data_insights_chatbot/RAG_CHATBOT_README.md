# RAG Chatbot with Vector Search

## Overview

This application implements a Retrieval-Augmented Generation (RAG) chatbot that uses:
- **Vector Database**: PostgreSQL with pgvector extension
- **Embeddings**: AWS Bedrock Titan Embed Text v1 (1536 dimensions)
- **LLM**: AWS Bedrock Claude 3 Sonnet
- **Data Source**: CSV file with 781 documents

## Architecture

```
User Question
    ↓
Vector Similarity Search (Top 5 documents)
    ↓
Build Context from Retrieved Documents
    ↓
Send to Claude 3 Sonnet via AWS Bedrock
    ↓
Return Answer with Sources
```

## API Endpoints

### 1. Chat Endpoint

**POST** `/api/chat`

**Request:**
```json
{
  "question": "What is the count for customer 1701?"
}
```

**Response:**
```json
{
  "answer": "Based on the data, customer 1701 has...",
  "sources": [
    "Row 1 from data.csv",
    "Row 45 from data.csv"
  ],
  "documentsUsed": 5
}
```

### 2. Health Check

**GET** `/api/chat/health`

Returns: `"Chat service is running"`

## How It Works

### 1. Data Ingestion (Already Done)
- CSV file is loaded at startup
- Each row is converted to text
- Text is embedded using Titan (1536-dimensional vectors)
- Embeddings are stored in PostgreSQL vector_store table

### 2. Query Processing
1. **User asks a question** via POST to `/api/chat`
2. **Similarity Search**: Question is embedded and top 5 similar documents are retrieved
3. **Context Building**: Retrieved documents are combined as context
4. **LLM Processing**: Context + Question sent to Claude 3 Sonnet
5. **Response**: Claude generates answer based only on retrieved context
6. **Sources**: Document sources are returned with the answer

## Running the Application

### 1. Ensure Docker PostgreSQL is Running
```bash
docker ps
```

### 2. Run Spring Boot Application
```bash
./mvnw spring-boot:run
```

Or run from IntelliJ IDEA.

### 3. Access the Chat UI
Open browser: `http://localhost:8080`

### 4. Use the REST API

**Using cURL:**
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "How many files were protected for customer 722?"}'
```

**Using PowerShell:**
```powershell
$body = @{
    question = "How many files were protected for customer 722?"
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8080/api/chat" `
  -Method Post `
  -ContentType "application/json" `
  -Body $body
```

## Example Questions

Based on your CSV data, you can ask:

1. "What is the total count for customer 1701?"
2. "Which customers are using Desktop Client version 3.15.0.0?"
3. "Show me data for production environment on 2022-03-01"
4. "What file types are being protected?"
5. "Which agent versions are most common?"
6. "How many events were recorded for customer 627?"

## Configuration

### Vector Search Parameters

In `RagService.java`:
```java
SearchRequest searchRequest = SearchRequest.builder()
    .query(question)
    .topK(5)  // Number of similar documents to retrieve
    .build();
```

**Adjust `topK`** to change how many documents are used as context:
- **Lower (2-3)**: Faster, more focused answers
- **Higher (8-10)**: More comprehensive answers, but slower

### LLM Model

Current: Claude 3 Sonnet (`anthropic.claude-3-sonnet-20240229-v1:0`)

To use a different model, update `BedrockConfig.java`:
```java
return new AnthropicChatBedrockApi(
    "anthropic.claude-3-5-sonnet-20241022-v2:0",  // or other model
    awsCredentialsProvider,
    Region.of(awsRegion),
    new ObjectMapper(),
    Duration.ofMinutes(2)
);
```

## Testing the Vector Search

### Check Stored Data
```sql
-- Connect to PostgreSQL
docker exec -it <container-name> psql -U postgres -d postgres

-- Count vectors
SELECT COUNT(*) FROM vector_store;

-- View sample data
SELECT id, LEFT(content, 100), metadata FROM vector_store LIMIT 5;

-- Test similarity search manually
SELECT content, metadata 
FROM vector_store 
ORDER BY embedding <=> '[your-embedding-vector]'::vector 
LIMIT 5;
```

## Troubleshooting

### No Documents Found
If similarity search returns 0 documents:
1. Check if data is loaded: `SELECT COUNT(*) FROM vector_store;`
2. Verify embeddings exist: `SELECT COUNT(*) FROM vector_store WHERE embedding IS NOT NULL;`
3. Re-run the application to reload data

### LLM Errors
- Verify AWS credentials are set
- Check AWS Bedrock model access permissions
- Ensure Claude 3 Sonnet is enabled in your AWS region

### CORS Issues
The controller has `@CrossOrigin(origins = "*")` enabled.
For production, restrict to specific origins:
```java
@CrossOrigin(origins = "https://yourdomain.com")
```

## Next Steps

1. **Add Streaming**: Implement Server-Sent Events for real-time responses
2. **Add Chat History**: Store conversation context for follow-up questions
3. **Add Filters**: Filter vector search by metadata (customer, date, etc.)
4. **Add Authentication**: Secure the API endpoints
5. **Add Rate Limiting**: Prevent API abuse
6. **Add Caching**: Cache frequent queries

## Files Structure

```
src/main/java/com/yash/chatbot_rag/
├── controller/
│   └── ChatController.java          # REST API endpoints
├── service/
│   └── RagService.java               # RAG logic (search + LLM)
├── dto/
│   ├── ChatRequest.java              # Request DTO
│   └── ChatResponse.java             # Response DTO
├── BedrockConfig.java                # AWS Bedrock configuration
├── CsvDataLoader.java                # Data ingestion
└── ChatbotRagApplication.java        # Main application

src/main/resources/
├── static/
│   └── index.html                    # Chat UI
├── docs/
│   └── data.csv                      # Source data (781 rows)
└── application.yaml                  # Configuration
```

## Success! 🎉

Your RAG chatbot is now fully functional with:
✅ Vector similarity search
✅ AWS Bedrock Claude 3 integration
✅ REST API endpoints
✅ Beautiful chat UI
✅ Batch processing for data ingestion
✅ Source attribution for answers

Enjoy your RAG-powered chatbot!
