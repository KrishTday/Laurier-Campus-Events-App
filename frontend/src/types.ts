export interface CampusEvent {
  id: number;
  title: string;
  category: string;
  description: string;
  location: string;
  organizer: string;
  eventStart: string; // ISO local date-time, e.g. 2026-09-20T11:00:00
  eventEnd: string | null;
  rsvpDeadline: string | null;
  createdAt: string;
  trackedCount: number;
  trackedByMe: boolean;
}

export interface NewEventInput {
  title: string;
  category: string;
  description: string;
  location: string;
  organizer: string;
  eventStart: string;
  eventEnd: string;
  rsvpDeadline: string;
}

export const CATEGORIES = [
  "Orientation",
  "Academic",
  "Career",
  "Clubs",
  "Sports",
  "Social",
] as const;

export type Category = (typeof CATEGORIES)[number];
