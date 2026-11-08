# Spring AI Fundamentals

An interactive learning app for the concepts behind modern AI and the Spring AI programming model. Read the lessons, inspect Java examples, then ask a local Spring AI-powered tutor follow-up questions.

## What’s implemented

- **Fundamentals guide:** explains the nested AI → machine learning → deep learning → generative AI relationship, why generative AI took off, how LLMs generate text, and common limitations.
- **Spring AI lessons:** introduces the `ChatModel` and `ChatClient` abstractions, provider starters and BOM configuration, prompts and message roles, `PromptTemplate`, model options and response usage metadata, blocking versus streaming, multimodal messages, structured output into Java types, and the Advisors API.
- **Advisor concepts in code:** registers Spring AI’s `MessageChatMemoryAdvisor` on the chat client and supplies the current server WebSession ID as the conversation ID for each reactive call.
- **Session chat memory:** follow-up prompts in one browser session include earlier turns from a bounded in-memory message window. The “New chat” button clears that session’s memory. Memory is process-local and lost when the app restarts.
- **Reactive streaming tutor:** uses Spring AI `ChatClient.stream().content()` with Ollama’s streaming chat API. The WebFlux endpoint emits Server-Sent Events, and the browser reads the response stream and displays text chunks as they arrive.
- **Educational Java examples:** the lesson snippets illustrate Spring AI APIs. Reactive text streaming is implemented in this app; multimodal prompts and structured-output conversion are covered as concepts but are not wired into the interactive chat.
- **Interactive tutor:** sends a question to a Spring AI `ChatClient` backed by the local Ollama model `llama3:latest`. The tutor prompt encourages beginner-friendly explanations and calls out uncertainty and model limitations.
- **Chat-first interface:** the local AI tutor is the first section on the page, with a large conversation area, suggested prompts, and an always-visible message composer.
- **Reactive chat API:** `POST /api/chat` accepts one message and streams token chunks, while the memory advisor provides session-scoped context to the model.
- **Responsive interface:** suggested questions, loading and error feedback, and a mobile-friendly layout. Conversation content is rendered as text, not HTML.
- **Input guardrails:** the API rejects blank messages and messages longer than 2,000 characters.
- **Java package structure:** controllers, request/response models, chat services, exception handling, and configuration are separated into `controller`, `model`, `service`, `exception`, and `config` packages.
- **Boilerplate reduction:** Lombok supplies constructor injection and logging where appropriate; Apache Commons Lang handles blank-input checks and exception-cause lookup.

## Requirements

- Java 21 or newer
- Maven 3.9+
- [Ollama](https://ollama.com/) installed and running locally

## Run locally

Download the model and make sure Ollama is running:

```bash
ollama pull llama3:latest
ollama serve
```

In another terminal, start the app:

```bash
mvn spring-boot:run
```

Open [http://localhost:8080](http://localhost:8080). The default model is `llama3:latest`, served from Ollama at `http://localhost:11434`. Set `OLLAMA_BASE_URL` to use an Ollama server at a different address, or change `spring.ai.ollama.chat.options.model` in `src/main/resources/application.yml` to select another locally available model.

The page navigation links to the chat, AI learning path, and the Spring AI lessons. The tutor can answer questions about all of these topics. Browser cookies maintain a server WebSession so the advisor can scope conversation history without accepting a conversation ID from the request body.

The Ollama HTTP response timeout defaults to 5 minutes to allow for model loading and slower local inference. Override it with `OLLAMA_RESPONSE_TIMEOUT`, using a Spring duration such as `2m` or `90s`.

### Troubleshooting Ollama connections

The Reactor Netty message `The connection observed an error` is a connection-level warning. Check the exception that follows it: `ReadTimeoutException` means Ollama did not return data before the client timeout, while connection-refused errors usually mean Ollama is not listening at the configured address. The app allows 5 minutes by default for inference. Confirm Ollama is reachable and the model is installed:

```bash
curl http://localhost:11434/api/tags
ollama list
```

If needed, start Ollama with `ollama serve`, verify `llama3:latest` is listed, and check that `OLLAMA_BASE_URL` points to the same host and port. If you still see a read timeout, check Ollama’s logs and machine resource usage, or increase `OLLAMA_RESPONSE_TIMEOUT`.

## API

### `POST /api/chat` (Server-Sent Events)

Request:

```json
{
  "message": "What is the difference between deep learning and generative AI?"
}
```

Successful response uses `Content-Type: text/event-stream`. Text arrives in `token` events, followed by a `done` event:

```text
event:token
data:Hello

event:token
data: there!

event:done
data:
```

Try the streaming endpoint with curl:

```bash
curl -N http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"message":"Explain what a token is"}'
```

Invalid input returns `400 Bad Request` with a JSON error before the stream starts. Ollama failures after streaming begins are delivered as an `error` event with a user-safe message, followed by `done`; provider exception details are logged by the server, not sent to the browser.

The endpoint uses the server-managed WebSession cookie to identify conversation memory. Keep the cookie between requests to continue the same chat.

### `DELETE /api/chat`

Clears the current WebSession’s in-memory chat history. The browser’s “New chat” button calls this endpoint before starting a fresh conversation.

## Run tests

```bash
mvn test
```

## Important limitations

- The tutor is an LLM, so its answers can be incorrect or out of date. Verify important claims.
- Chat memory uses an in-memory repository. It is bounded, local to the running app process, lost on restart, and not a production persistence or multi-user deployment solution.
- The app has no document retrieval or external tools. The tutor cannot access private data or take actions.
- Multimodal prompts, provider switching, and structured-output conversion are educational examples only; the interactive chat currently streams text from one local Ollama model.
- The active chat uses the memory advisor; other advisor-chain examples (such as logging, safety, retrieval, and recursive validation) are educational only.
- Live responses require the Ollama service and model to be available locally. Model inference consumes local compute resources.
- Prompts are limited to 2,000 characters; this is a small demo, not a production security boundary or cost-control system.
