export function formatDateTime(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  return d.toLocaleString(undefined, {
    weekday: "short",
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

/** "in 3 days", "in 4 hours", "starting now", "ended" */
export function countdown(iso: string | null): string {
  if (!iso) return "";
  const diffMs = new Date(iso).getTime() - Date.now();
  if (diffMs <= 0) return "passed";

  const minutes = Math.round(diffMs / 60000);
  if (minutes < 60) return `in ${minutes} min${minutes === 1 ? "" : "s"}`;

  const hours = Math.round(minutes / 60);
  if (hours < 24) return `in ${hours} hour${hours === 1 ? "" : "s"}`;

  const days = Math.round(hours / 24);
  return `in ${days} day${days === 1 ? "" : "s"}`;
}

export function isUrgent(iso: string | null): boolean {
  if (!iso) return false;
  const diffMs = new Date(iso).getTime() - Date.now();
  return diffMs > 0 && diffMs < 1000 * 60 * 60 * 48; // within 48h
}
