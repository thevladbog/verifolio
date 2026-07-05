import { useTranslations } from "next-intl";

import { cn } from "@/lib/utils";

/** DSR lifecycle statuses shown in the admin queue (spec §DSR review queue). */
export const DSR_STATUSES = [
  "RECEIVED",
  "IN_REVIEW",
  "APPROVED",
  "EXECUTED",
  "REJECTED",
] as const;

export type DsrStatus = (typeof DSR_STATUSES)[number];

/** Dark-shell tints (the light `ui/badge` reads wrong on the admin ink chrome). */
const TINTS: Record<string, string> = {
  RECEIVED: "border-blue-gray/40 bg-blue-gray/10 text-blue-gray",
  IN_REVIEW: "border-trust-blue/40 bg-trust-blue/15 text-trust-blue",
  APPROVED: "border-verified-green/40 bg-verified-green/15 text-verified-green",
  EXECUTED: "border-verified-green/50 bg-verified-green/20 text-verified-green",
  REJECTED: "border-danger/40 bg-danger/15 text-danger",
};

/** A status pill readable without color alone (label text is always present). */
export function DsrStatusBadge({ status }: { status: string }) {
  const t = useTranslations("admin.statuses");
  const label = DSR_STATUSES.includes(status as DsrStatus)
    ? t(status)
    : status;
  return (
    <span
      className={cn(
        "inline-flex h-6 items-center rounded-full border px-2.5 text-xs font-medium",
        TINTS[status] ?? "border-blue-gray/40 bg-blue-gray/10 text-blue-gray",
      )}
    >
      {label}
    </span>
  );
}
