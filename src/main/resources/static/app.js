const form = document.querySelector("#chat-form");
const input = document.querySelector("#chat-input");
const messages = document.querySelector("#chat-messages");
const sendButton = form.querySelector("button");
const modeSelect = document.querySelector("#chat-mode");
const ragTopicSelect = document.querySelector("#rag-topic");
const ragTopicControls = document.querySelector("#rag-topic-controls");
const indexButton = document.querySelector("#index-knowledge");
const knowledgeStatus = document.querySelector("#knowledge-status");
const modeHelp = document.querySelector("#mode-help");
const newChatButton = document.querySelector("#new-chat");
const tutorStatus = document.querySelector(".tutor-status");
const modeHistory = new Map();
let activeMode = modeSelect.value;
let knowledgeIndexed = false;

const modeDescriptions = {
  tutor: "Ask freely with session memory. No retrieval or tools are used in this mode.",
  rag: "Answers search the learning notes. The first question prepares the index automatically; Ollama and nomic-embed-text must be available.",
  tools: "The model can request the current time or lesson outlines. Requires a tool-capable model such as llama3.1."
};

const modeSuggestions = {
  tutor: ["What is a token?", "Why are LLMs stateless?", "Where does Spring AI help?"],
  rag: ["Why should I split documents into chunks?", "How do embeddings find related text?", "How is RAG different from tool calling?"],
  tools: ["What is the current date and time?", "Look up the rag lesson outline", "Look up the tools lesson outline"]
};

function updateModeHelp() {
  modeHelp.textContent = modeDescriptions[activeMode];
  ragTopicControls.hidden = activeMode !== "rag";
  tutorStatus.lastChild.textContent = activeMode === "rag"
    ? " RAG · local Ollama"
    : activeMode === "tools" ? " Java tools · local Ollama" : " General tutor · no retrieval";
  input.placeholder = activeMode === "rag" ? "Ask the learning notes…" : "Message your AI tutor…";
  document.querySelectorAll(".suggestion").forEach((button, index) => {
    button.textContent = modeSuggestions[activeMode][index];
  });
}

function showKnowledgeStatus(status) {
  knowledgeIndexed = status.indexed;
  knowledgeStatus.textContent = status.indexed
    ? `${status.documentCount} notes · ${status.chunkCount} chunks · ${status.embeddingModel}`
    : `Notes prepare automatically on your first question · ${status.embeddingModel}`;
}

async function refreshKnowledgeStatus() {
  try {
    const response = await fetch("/api/knowledge");
    if (!response.ok) throw new Error("Could not read knowledge status.");
    showKnowledgeStatus(await response.json());
  } catch {
    knowledgeStatus.textContent = "Knowledge status unavailable. Check that the app is running.";
  }
}

function addMessage(text, kind) {
  const message = document.createElement("div");
  message.className = `message message-${kind}`;

  const avatar = document.createElement("span");
  avatar.className = "message-avatar";
  avatar.setAttribute("aria-hidden", "true");
  avatar.textContent = kind === "user" ? "YOU" : "✳";

  const content = document.createElement("p");
  content.textContent = text;
  message.append(avatar, content);
  messages.append(message);
  messages.scrollTop = messages.scrollHeight;
  return message;
}

function handleServerEvent(frame, assistantMessage) {
  let eventName = "message";
  const data = [];
  frame.split("\n").forEach((line) => {
    if (line.startsWith("event:")) {
      eventName = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      data.push(line.slice(5));
    }
  });

  if (eventName === "token") {
    const content = assistantMessage.querySelector("p");
    if (assistantMessage.classList.contains("typing")) {
      assistantMessage.classList.remove("typing");
      content.textContent = "";
    }
    content.textContent += data.join("\n");
    messages.scrollTop = messages.scrollHeight;
  } else if (eventName === "error") {
    assistantMessage.remove();
    addMessage(data.join("\n") || "Ollama could not complete the request.", "error");
  }
}

async function askTutor(question) {
  const trimmedQuestion = question.trim();
  if (!trimmedQuestion || sendButton.disabled) return;
  const requestMode = activeMode;

  addMessage(trimmedQuestion, "user");
  input.value = "";
  input.style.height = "auto";
  sendButton.disabled = true;
  modeSelect.disabled = true;
  ragTopicSelect.disabled = true;
  newChatButton.disabled = true;
  const pendingText = requestMode === "rag"
    ? knowledgeIndexed ? "Searching the learning notes…" : "Preparing notes and searching…"
    : "Thinking through that…";
  const pendingMessage = addMessage(pendingText, "assistant typing");

  try {
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept": "text/event-stream"
      },
      body: JSON.stringify({
        message: trimmedQuestion,
        mode: activeMode,
        ...(activeMode === "rag" ? { topic: ragTopicSelect.value } : {})
      })
    });
    if (!response.ok) {
      pendingMessage.remove();
      const result = await response.json();
      addMessage(result.error || "The request could not be completed.", "error");
      return;
    }
    if (!response.body) {
      throw new Error("Streaming response body is unavailable.");
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let finished = false;
    while (!finished) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      buffer = buffer.replace(/\r\n/g, "\n");
      let frameEnd = buffer.indexOf("\n\n");
      while (frameEnd !== -1) {
        handleServerEvent(buffer.slice(0, frameEnd), pendingMessage);
        buffer = buffer.slice(frameEnd + 2);
        frameEnd = buffer.indexOf("\n\n");
      }
      if (done) {
        if (buffer.trim()) handleServerEvent(buffer, pendingMessage);
        finished = true;
      }
    }
    if (pendingMessage.classList.contains("typing")) {
      pendingMessage.remove();
    }
  } catch {
    pendingMessage.remove();
    addMessage("The chat stream was interrupted. Check that the Spring AI app and Ollama are running, then try again.", "error");
  } finally {
    if (requestMode === "rag") void refreshKnowledgeStatus();
    sendButton.disabled = false;
    modeSelect.disabled = false;
    ragTopicSelect.disabled = false;
    newChatButton.disabled = false;
    input.focus();
  }
}

form.addEventListener("submit", (event) => {
  event.preventDefault();
  askTutor(input.value);
});

input.addEventListener("input", () => {
  input.style.height = "auto";
  input.style.height = `${Math.min(input.scrollHeight, 100)}px`;
});

document.querySelectorAll(".suggestion").forEach((button) => {
  button.addEventListener("click", () => askTutor(button.textContent));
});

newChatButton.addEventListener("click", async () => {
  if (sendButton.disabled) return;

  newChatButton.disabled = true;
  sendButton.disabled = true;
  modeSelect.disabled = true;
  ragTopicSelect.disabled = true;
  try {
    const response = await fetch("/api/chat", { method: "DELETE" });
    if (!response.ok) {
      addMessage("Could not clear this conversation. Please try again.", "error");
      return;
    }

    messages.replaceChildren();
    modeHistory.clear();
    addMessage(activeMode === "rag"
      ? "New conversation started. I’ll search the learning notes for your next question."
      : "New conversation started. What would you like to learn?", "assistant");
    input.value = "";
    input.style.height = "auto";
    input.focus();
  } catch {
    addMessage("Could not reach the Spring AI app. Check that it is running, then try again.", "error");
  } finally {
    newChatButton.disabled = false;
    sendButton.disabled = false;
    modeSelect.disabled = false;
    ragTopicSelect.disabled = false;
  }
});

modeSelect.addEventListener("change", () => {
  modeHistory.set(activeMode, Array.from(messages.children));
  activeMode = modeSelect.value;
  messages.replaceChildren(...(modeHistory.get(activeMode) || []));
  if (!messages.children.length) {
    addMessage(modeDescriptions[activeMode], "assistant");
  }
  updateModeHelp();
  input.focus();
});

indexButton.addEventListener("click", async () => {
  indexButton.disabled = true;
  knowledgeStatus.textContent = "Indexing learning notes… This may take a moment while the embedding model loads.";
  try {
    const response = await fetch("/api/knowledge/index", { method: "POST" });
    const result = await response.json();
    if (!response.ok) {
      knowledgeStatus.textContent = result.error || "Indexing failed. Check Ollama and the embedding model.";
      return;
    }
    showKnowledgeStatus(result);
  } catch {
    knowledgeStatus.textContent = "Could not reach the app to index the notes. Please try again.";
  } finally {
    indexButton.disabled = false;
  }
});

updateModeHelp();
void refreshKnowledgeStatus();
