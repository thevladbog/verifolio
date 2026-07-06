import { useTranslations } from "next-intl";

import { cn } from "@/lib/utils";

/** User account lifecycle statuses shown in the admin user views (spec §User views). */
export const USER_STATUSES = ["ACTIVE", "DISABLED", "DELETED"] as const;

export type UserStatus = (typeof USER_STATUSES)[number];

/** Dark-shell tints (the light `ui/badge` reads wrong on the admin ink chrome). */
const TINTS: Record<string, string> = {
  ACTIVE: "border-verified-green/40 bg-verified-green/15 text-verified-green",
  DISABLED: "border-blue-gray/40 bg-blue-gray/10 text-blue-gray",
  DELETED: "border-danger/40 bg-danger/15 text-danger",
};

/** A status pill readable without color alone (label text is always present). */
export function UserStatusBadge({ status }: { status: string }) {
  const t = useTranslations("admin.userStatuses");
  const label = USER_STATUSES.includes(status as UserStatus) ? t(status) : status;
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
