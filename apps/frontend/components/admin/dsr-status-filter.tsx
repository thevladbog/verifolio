"use client";

import { useTranslations } from "next-intl";

import { cn } from "@/lib/utils";
import { DSR_STATUSES } from "@/components/admin/dsr-status-badge";

/**
 * Status filter chips for the DSR queue (design 5d). `null` = all statuses.
 */
export function DsrStatusFilter({
  value,
  onChange,
}: {
  value: string | null;
  onChange: (status: string | null) => void;
}) {
  const t = useTranslations("admin");
  const chips: Array<{ key: string; label: string; value: string | null }> = [
    { key: "ALL", label: t("queue.allFilter"), value: null },
    ...DSR_STATUSES.map((s) => ({
      key: s,
      label: t(`statuses.${s}`),
      value: s as string | null,
    })),
  ];

  return (
    <div role="group" aria-label={t("queue.filterLabel")} className="flex flex-wrap gap-2">
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
