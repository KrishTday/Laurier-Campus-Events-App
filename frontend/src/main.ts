import { api } from "./api.js";
import { CampusEvent, CATEGORIES, NewEventInput } from "./types.js";
import { countdown, formatDateTime, isUrgent } from "./time.js";
import { initChat } from "./chat.js";

const state: {
  events: CampusEvent[];
  category: string;
  search: string;
  trackedOnly: boolean;
  organizerMode: boolean;
} = {
  events: [],
  category: "All",
  search: "",
  trackedOnly: false,
  organizerMode: false,
};

const els = {
  list: document.getElementById("event-list") as HTMLDivElement,
  empty: document.getElementById("empty-state") as HTMLDivElement,
  search: document.getElementById("search-input") as HTMLInputElement,
  category: document.getElementById("category-select") as HTMLSelectElement,
  trackedOnly: document.getElementById("tracked-only") as HTMLInputElement,
  organizerToggle: document.getElementById("organizer-toggle") as HTMLButtonElement,
  addForm: document.getElementById("add-event-form") as HTMLFormElement,
  addPanel: document.getElementById("add-event-panel") as HTMLDivElement,
  addToggle: document.getElementById("add-event-toggle") as HTMLButtonElement,
  status: document.getElementById("status-message") as HTMLDivElement,
};

function init(): void {
  for (const cat of CATEGORIES) {
    const opt = document.createElement("option");
    opt.value = cat;
    opt.textContent = cat;
    els.category.appendChild(opt);
  }

  els.search.addEventListener("input", debounce(() => {
    state.search = els.search.value.trim();
    refresh();
  }, 250));

  els.category.addEventListener("change", () => {
    state.category = els.category.value;
    refresh();
  });

  els.trackedOnly.addEventListener("change", () => {
    state.trackedOnly = els.trackedOnly.checked;
    render();
  });

  els.organizerToggle.addEventListener("click", () => {
    state.organizerMode = !state.organizerMode;
    els.organizerToggle.textContent = state.organizerMode ? "Exit organizer mode" : "Organizer mode";
    els.organizerToggle.classList.toggle("active", state.organizerMode);
    render();
  });

  els.addToggle.addEventListener("click", () => {
    els.addPanel.classList.toggle("hidden");
  });

  els.addForm.addEventListener("submit", onCreateEvent);

  initChat(onChatFocusEvent);

  refresh();
  window.setInterval(render, 60_000); // keep countdowns fresh
}

// Jumps the existing search/filter state to an event the chatbot cited,
// reusing the normal browsing pipeline instead of a separate rendering path.
function onChatFocusEvent(eventId: number): void {
  const event = state.events.find((e) => e.id === eventId);
  if (!event) return;
  state.search = event.title;
  state.category = "All";
  els.search.value = event.title;
  els.category.value = "All";
  refresh();
  els.list.scrollIntoView({ behavior: "smooth", block: "start" });
}

async function refresh(): Promise<void> {
  try {
    setStatus("Loading events...");
    state.events = await api.listEvents({ category: state.category, search: state.search });
    setStatus("");
    render();
  } catch (err) {
    setStatus(err instanceof Error ? err.message : "Failed to load events.");
  }
}

function render(): void {
  const visible = state.trackedOnly ? state.events.filter((e) => e.trackedByMe) : state.events;
  els.list.innerHTML = "";
  els.empty.classList.toggle("hidden", visible.length > 0);

  for (const event of visible) {
    els.list.appendChild(renderCard(event));
  }
}

function renderCard(event: CampusEvent): HTMLElement {
  const card = document.createElement("article");
  card.className = "event-card";

  const deadlineUrgent = isUrgent(event.rsvpDeadline);

  card.innerHTML = `
    <div class="event-card-top">
      <span class="badge badge-${slug(event.category)}">${escapeHtml(event.category)}</span>
      <span class="countdown">${escapeHtml(countdown(event.eventStart))}</span>
    </div>
    <h3>${escapeHtml(event.title)}</h3>
    <p class="event-meta">${escapeHtml(formatDateTime(event.eventStart))} &middot; ${escapeHtml(event.location)}</p>
    <p class="event-description">${escapeHtml(event.description)}</p>
    ${event.rsvpDeadline ? `
      <p class="rsvp-deadline ${deadlineUrgent ? "urgent" : ""}">
        RSVP by ${escapeHtml(formatDateTime(event.rsvpDeadline))} (${escapeHtml(countdown(event.rsvpDeadline))})
      </p>` : ""}
    <p class="event-organizer">Hosted by ${escapeHtml(event.organizer || "Laurier")}</p>
    <div class="event-actions"></div>
  `;

  const actions = card.querySelector(".event-actions") as HTMLDivElement;

  const trackBtn = document.createElement("button");
  trackBtn.className = "track-btn" + (event.trackedByMe ? " tracked" : "");
  trackBtn.type = "button";
  trackBtn.textContent = event.trackedByMe ? "\u2605 Tracking" : "\u2606 Track";
  trackBtn.addEventListener("click", () => onToggleTrack(event));
  actions.appendChild(trackBtn);

  if (state.organizerMode) {
    const deleteBtn = document.createElement("button");
    deleteBtn.className = "delete-btn";
    deleteBtn.type = "button";
    deleteBtn.textContent = "Delete";
    deleteBtn.addEventListener("click", () => onDeleteEvent(event));
    actions.appendChild(deleteBtn);
  }

  return card;
}

async function onToggleTrack(event: CampusEvent): Promise<void> {
  try {
    if (event.trackedByMe) {
      await api.untrack(event.id);
    } else {
      await api.track(event.id);
    }
    await refresh();
  } catch (err) {
    setStatus(err instanceof Error ? err.message : "Couldn't update tracking.");
  }
}

async function onDeleteEvent(event: CampusEvent): Promise<void> {
  if (!confirm(`Delete "${event.title}"?`)) return;
  try {
    await api.deleteEvent(event.id);
    await refresh();
  } catch (err) {
    setStatus(err instanceof Error ? err.message : "Couldn't delete event.");
  }
}

async function onCreateEvent(e: Event): Promise<void> {
  e.preventDefault();
  const form = new FormData(els.addForm);
  const input: NewEventInput = {
    title: String(form.get("title") || ""),
    category: String(form.get("category") || ""),
    description: String(form.get("description") || ""),
    location: String(form.get("location") || ""),
    organizer: String(form.get("organizer") || ""),
    eventStart: String(form.get("eventStart") || ""),
    eventEnd: String(form.get("eventEnd") || ""),
    rsvpDeadline: String(form.get("rsvpDeadline") || ""),
  };
  try {
    await api.createEvent(input);
    els.addForm.reset();
    els.addPanel.classList.add("hidden");
    await refresh();
  } catch (err) {
    setStatus(err instanceof Error ? err.message : "Couldn't create event.");
  }
}

function setStatus(message: string): void {
  els.status.textContent = message;
  els.status.classList.toggle("hidden", message.length === 0);
}

function slug(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, "-");
}

function escapeHtml(s: string): string {
  const div = document.createElement("div");
  div.textContent = s;
  return div.innerHTML;
}

function debounce<T extends (...args: never[]) => void>(fn: T, ms: number): T {
  let timer: number | undefined;
  return ((...args: never[]) => {
    window.clearTimeout(timer);
    timer = window.setTimeout(() => fn(...args), ms);
  }) as T;
}

init();
