import { api } from "./api.js";
import { ChatReply } from "./types.js";

type Role = "user" | "assistant";

interface ChatEls {
  toggle: HTMLButtonElement;
  panel: HTMLDivElement;
  messages: HTMLDivElement;
  form: HTMLFormElement;
  input: HTMLInputElement;
}

/**
 * Wires up the "Ask about events" widget. Calls the RAG endpoint at
 * /api/chat and renders the events it cites as clickable chips; clicking one
 * hands its id to onFocusEvent so main.ts can jump the existing search/filter
 * state straight to it.
 */
export function initChat(onFocusEvent: (eventId: number) => void): void {
  const els: ChatEls = {
    toggle: document.getElementById("chat-toggle") as HTMLButtonElement,
    panel: document.getElementById("chat-panel") as HTMLDivElement,
    messages: document.getElementById("chat-messages") as HTMLDivElement,
    form: document.getElementById("chat-form") as HTMLFormElement,
    input: document.getElementById("chat-input") as HTMLInputElement,
  };

  if (!els.toggle || !els.panel || !els.messages || !els.form || !els.input) return;

  els.toggle.addEventListener("click", () => {
    els.panel.classList.toggle("hidden");
    if (!els.panel.classList.contains("hidden")) els.input.focus();
  });

  els.form.addEventListener("submit", (e) => {
    e.preventDefault();
    void onSubmit(els, onFocusEvent);
  });
}

async function onSubmit(els: ChatEls, onFocusEvent: (eventId: number) => void): Promise<void> {
  const question = els.input.value.trim();
  if (!question) return;
  els.input.value = "";
  els.input.disabled = true;

  appendMessage(els.messages, "user", question);
  const pending = appendMessage(els.messages, "assistant", "Thinking...");

  try {
    const reply = await api.chat(question);
    pending.remove();
    appendAnswer(els.messages, reply, onFocusEvent);
  } catch (err) {
    pending.remove();
    appendMessage(els.messages, "assistant", err instanceof Error ? err.message : "Something went wrong.");
  } finally {
    els.input.disabled = false;
    els.input.focus();
  }
}

function appendMessage(container: HTMLDivElement, role: Role, text: string): HTMLDivElement {
  const bubble = document.createElement("div");
  bubble.className = `chat-bubble chat-${role}`;
  bubble.textContent = text;
  container.appendChild(bubble);
  container.scrollTop = container.scrollHeight;
  return bubble;
}

function appendAnswer(
  container: HTMLDivElement,
  reply: ChatReply,
  onFocusEvent: (eventId: number) => void
): void {
  const bubble = document.createElement("div");
  bubble.className = "chat-bubble chat-assistant";

  const text = document.createElement("p");
  text.className = "chat-answer-text";
  text.textContent = reply.answer;
  bubble.appendChild(text);

  if (reply.events.length > 0) {
    const list = document.createElement("div");
    list.className = "chat-sources";
    for (const ev of reply.events) {
      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = "chat-source-chip";
      chip.textContent = ev.title;
      chip.addEventListener("click", () => onFocusEvent(ev.id));
      list.appendChild(chip);
    }
    bubble.appendChild(list);
  }

  container.appendChild(bubble);
  container.scrollTop = container.scrollHeight;
}
