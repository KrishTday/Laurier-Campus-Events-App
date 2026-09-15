import { CampusEvent, NewEventInput } from "./types.js";
import { API_BASE } from "./config.js";

const STUDENT_KEY_STORAGE = "laurier-events:student-key";

export function getStudentKey(): string {
  let key = localStorage.getItem(STUDENT_KEY_STORAGE);
  if (!key) {
    key = crypto.randomUUID();
    localStorage.setItem(STUDENT_KEY_STORAGE, key);
  }
  return key;
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(API_BASE + path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      "X-Student-Key": getStudentKey(),
      ...(options.headers || {}),
    },
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error || `Request failed: ${res.status}`);
  }
  return res.json() as Promise<T>;
}

export const api = {
  listEvents(params: { category?: string; search?: string } = {}): Promise<CampusEvent[]> {
    const qs = new URLSearchParams();
    if (params.category) qs.set("category", params.category);
    if (params.search) qs.set("search", params.search);
    const suffix = qs.toString() ? `?${qs.toString()}` : "";
    return request<CampusEvent[]>(`/api/events${suffix}`);
  },

  createEvent(input: NewEventInput): Promise<CampusEvent> {
    return request<CampusEvent>("/api/events", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },

  deleteEvent(id: number): Promise<{ deleted: boolean }> {
    return request(`/api/events/${id}`, { method: "DELETE" });
  },

  track(eventId: number): Promise<{ tracked: boolean }> {
    return request(`/api/students/${encodeURIComponent(getStudentKey())}/tracked/${eventId}`, {
      method: "POST",
    });
  },

  untrack(eventId: number): Promise<{ tracked: boolean }> {
    return request(`/api/students/${encodeURIComponent(getStudentKey())}/tracked/${eventId}`, {
      method: "DELETE",
    });
  },
};
