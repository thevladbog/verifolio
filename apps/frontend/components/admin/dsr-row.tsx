"use client";

import { useFormatter, useTranslations } from "next-intl";

import { DsrStatusBadge } from "@/components/admin/dsr-status-badge";
import { dsrTypeLabel } from "@/components/admin/dsr-type";
import { cn } from "@/lib/utils";
import type { components } from "@/lib/api/schema";

type DsrItem = components["schemas"]["AdminDsrItemResponse"];

/** A single DSR queue row (design 5d): type, subject email, status, due date. */
export function DsrRow({
  item,
  selected,
  onSelect,
}: {
  item: DsrItem;
  selected: boolean;
  onSelect: () => void;
}) {
  const t = useTranslations("admin");
  const format = useFormatter();
  const due = item.dueAt
    ? format.dateTime(new Date(item.dueAt), { dateStyle: "medium" })
    : "—";

  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className={cn(
        "flex w-full flex-col gap-1 border-b border-navy px-4 py-3 text-left transition-colors",
        selected ? "bg-navy/60" : "hover:bg-navy/30",
      )}
    >
      <div className="flex items-center justify-between gap-3">
        <span className="text-sm font-medium text-paper">
          {dsrTypeLabel(item.type, t)}
        </span>
        {item.status && <DsrStatusBadge status={item.status} />}
      </div>
      <div className="flex items-center justify-between gap-3 text-xs text-blue-gray">
        <span className="truncate">{item.subjectEmail}</span>
        <span className="shrink-0">{t("queue.dueShort", { date: due })}</span>
      </div>
    </button>
  );
}
