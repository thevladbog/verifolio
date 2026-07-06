"use client";

import { useTranslations } from "next-intl";

import { USER_STATUSES } from "@/components/admin/user-status-badge";
import { cn } from "@/lib/utils";

/**
 * Status filter chips for the admin user list (design 5b). `null` = all statuses.
 */
export function UserStatusFilter({
  value,
  onChange,
}: {
  value: string | null;
  onChange: (status: string | null) => void;
}) {
  const t = useTranslations("admin");
  const chips: Array<{ key: string; label: string; value: string | null }> = [
    { key: "ALL", label: t("users.allFilter"), value: null },
    ...USER_STATUSES.map((s) => ({
      key: s,
      label: t(`userStatuses.${s}`),
      value: s as string | null,
    })),
  ];

  return (
    <div
      role="group"
      aria-label={t("users.filterLabel")}
      className="flex flex-wrap gap-2"
    >
      {chips.map((chip) => {
        const active = chip.value === value;
        return (
          <button
            key={chip.key}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(chip.value)}
            className={cn(
              "h-8 rounded-full border px-3 text-xs font-medium transition-colors",
              active
                ? "border-paper bg-paper text-ink"
                : "border-navy bg-navy/40 text-blue-gray hover:border-blue-gray/60 hover:text-paper",
            )}
          >
            {chip.label}
          </button>
        );
      })}
    </div>
  );
}
