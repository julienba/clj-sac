# Architecture

## Overview
`clj-sac` is a Clojure low-level toolbox for developing with LLMs, providing centralized client boilerplate for accessing various LLM providers.

## Core Components

### HTTP Client (`clj-sac.http`)
- Centralized HTTP client using `hato` for making requests
- Built-in JSON parsing and schema validation using Malli
- Support for both synchronous and streaming (SSE) requests
- Automatic request/response validation with detailed error messages

### LLM Clients (`clj-sac.llm.http.*`)
LLM clients are organized in the `llm/http` directory, with each provider having:
- **Client implementation** (`provider.clj`) - Contains the actual API calls
- **Schema definitions** (`provider-schema.clj`) - Malli schemas for request/response validation

#### Current Implementations:
- **Mistral**
  - Chat completions (sync and streaming)
  - Function calling support
  - Details Completion and Response schema
- **Gemini**
  - Chat completions
  - Detailed response schemas including usage metadata

#### Schema Benefits:
- **Documentation**: Schemas serve as living documentation of API contracts
- **Debugging**: Automatic validation catches malformed requests/responses early
- **Type Safety**: Malli schemas provide runtime type checking
- **Error Messages**: Detailed validation errors help identify issues quickly

### Agent Namespace (`clj-sac.agent`)
- **Naive implementation _not production ready_**
- Implements OODA Loop (Observe-Orient-Decide-Act) pattern
- Tool integration for function calling
- Conversation memory management
- Configurable iteration and memory limits

### Tool Namespace (`clj-sac.tool`)
- **Naive implementation _not production ready_**

## Design Principles

1. **Schema-First**: Every LLM client should include comprehensive schemas for validation and documentation
2. **Provider Abstraction**: Each provider is self-contained with its own client and schema files
3. **Streaming Support**: Built-in support for Server-Sent Events where available