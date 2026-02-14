# RAG Chatbot with Vector Search and Analytical SQL

## Sample Questions to Ask the Chatbot

You can try asking the chatbot questions like:

- How many unique locations are there in the data?
- What is the average file size for records with the 'PUBLIC' label?
- Which file extension is most common for records with the 'INTERNAL' label?
- How many records were created in 2025 for each action type?

## Overview

This application implements a Retrieval-Augmented Generation (RAG) chatbot that supports both factual and analytical (aggregated/SQL) queries using:
- **Vector Database**: PostgreSQL with pgvector extension
- **Embeddings**: AWS Bedrock Titan Embed Text v1 (1536 dimensions)
- **LLM**: AWS Bedrock Claude 3 Sonnet
- **Data Source**: CSV file (ingested and available for both vector and SQL queries)

## Architecture

```
User Question
    ↓
Intent Classification (Factual or Analytical)
    ↓
If Factual: Vector Similarity Search (Top K documents)
    ↓
If Analytical: SQL Query Generation & Execution (with context-aware filters)
    ↓
Build Context from Retrieved Documents or SQL Results (with SQL query context)
    ↓
Send to Claude 3 Sonnet via AWS Bedrock with analytics prompt
    ↓
Return Answer with Sources
```

## API Endpoints

### 1. Chat Endpoint

**POST** `/api/chat`

**Request:**
```json
{
  "question": "Which action is most common for files in the 'Germany' location?"
}
```

**Response:**
```json
{
  "answer": "For files in Germany, DOWNGRADED is the most common action with 6 occurrences...",
  "sources": [
    "SQL Database: csv_data_data"
  ],
  "documentsUsed": 1
}
```

### 2. Health Check

**GET** `/api/chat/health`

Returns: `"Chat service is running"`

## How It Works

### 1. Data Ingestion
- CSV file is loaded at startup (async, idempotent)
- Each row is embedded and stored in vector DB
- Data is also stored in SQL DB for analytical queries

### 2. Query Processing
1. **User asks a question** via POST to `/api/chat`
2. **Intent Classification**: LLM determines if query is factual or analytical
3. **Factual**: Vector similarity search, retrieve top K documents
4. **Analytical**: LLM generates SQL query, executes it, and returns results (with SQL query context)
5. **Context Building**: Retrieved content or SQL results (with SQL query) are passed to the analytics prompt
6. **LLM Processing**: Claude 3 Sonnet generates answer using analytics-prompt.txt
7. **Response**: Answer and sources are returned

## Analytical Query Flow
- SQL query is generated from the user's question using a prompt file (no hardcoding)
- The executed SQL query is included in the context sent to the LLM
- The LLM is explicitly instructed (via analytics-prompt.txt) to use the SQL query and results, and to acknowledge any filters (e.g., WHERE location = 'Germany')
- The question is **not** duplicated in the data context; it is passed separately

## Example Analytical Response

**Question:**
> Which action ('DOWNGRADED', 'UPGRADED', or 'CLASSIFIED') is most common for files in the 'Germany' location?

**AI Response:**
```
Executive Insight
For files in the Germany location, DOWNGRADED is the most common action with 6 occurrences.

What the Data Shows
• DOWNGRADED: 6 files
• UPGRADED: 2 files
• CLASSIFIED: 2 files
• (Results are filtered for Germany location as shown in the SQL query context)
```

## Configuration

### Vector Search Parameters

Set in `application.yaml`:
```yaml
chatbot:
  topk: 3  # Number of similar documents to retrieve
```

### LLM Model

Set in `application.yaml` and `BedrockConfig.java`.

### Prompt Files
- All prompts (analytics, SQL generation, intent classification, etc.) are loaded from `.txt` files in `src/main/resources/prompts/`.
- No hardcoded prompts in code.

## Running the Application

1. Ensure Docker PostgreSQL is running
2. Run Spring Boot application: `./mvnw spring-boot:run`
3. Access the chat UI at `http://localhost:8080`
4. Use the REST API as shown above

## File Structure

```
src/main/java/com/yash/chatbot_rag/
├── controller/                # REST API endpoints
├── service/                   # RAG, SQL, and prompt logic
├── dto/                       # Request/Response DTOs
├── BedrockConfig.java         # AWS Bedrock config
├── CsvDataLoader.java         # Data ingestion
└── ChatbotRagApplication.java # Main app

src/main/resources/
├── prompts/                   # All prompt .txt files (no hardcoding)
├── docs/data.csv              # Source data
├── static/index.html          # Chat UI
└── application.yaml           # Config
```

## Success! 🎉

Your RAG chatbot now supports both factual and analytical queries, with:
- ✅ Vector similarity search
- ✅ Analytical SQL queries with context-aware answers
- ✅ All prompts loaded from .txt files
- ✅ REST API and chat UI
- ✅ Source attribution for answers

Enjoy your advanced RAG-powered chatbot!
