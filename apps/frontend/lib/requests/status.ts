import type { TrustBadgeVariant } from "@/components/verifolio/badge-status";

export const REQUEST_STATUSES = [
  "CREATED",
  "SENT",
  "OPENED",
  "IN_PROGRESS",
  "SUBMITTED",
  "NEEDS_REVIEW",
  "CORRECTION_REQUESTED",
  "COMPLETED",
  "DECLINED",
  "EXPIRED",
  "CANCELLED",
] as const;

export type RequestStatus = (typeof REQUEST_STATUSES)[number];

export const TERMINAL_STATUSES: ReadonlySet<string> = new Set([
  "COMPLETED",
  "DECLINED",
  "EXPIRED",
  "CANCELLED",
]);

/** Badge tone per status (never green unless actually completed/confirmed). */
export function statusBadgeVariant(status: string): TrustBadgeVariant {
  switch (status) {
    case "COMPLETED":
      return "verified";
    case "DECLINED":
    case "CANCELLED":
      return "failed";
    case "EXPIRED":
      return "expired";
    case "NEEDS_REVIEW":
    case "SUBMITTED":
      return "locked";
    default:
      return "pending";
  }
}

export function canSend(status: string): boolean {
  return status === "CREATED";
}

export function canCancel(status: string): boolean {
  return !TERMINAL_STATUSES.has(status);
}

export function canReview(status: string): boolean {
  return status === "NEEDS_REVIEW";
}

/** Ordered happy-path timeline for the detail view. */
export const TIMELINE: readonly RequestStatus[] = [
  "CREATED",
  "SENT",
  "OPENED",
  "IN_PROGRESS",
  "SUBMITTED",
  "NEEDS_REVIEW",
  "COMPLETED",
];
