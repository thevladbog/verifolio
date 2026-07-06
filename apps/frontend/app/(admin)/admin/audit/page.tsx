"use client";

import { ArrowLeft, Download, Lock } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";
import Link from "next/link";
import { useState } from "react";
import { toast } from "sonner";

import { AdminError } from "@/components/admin/admin-error";
import { AuditCodeBadge } from "@/components/admin/audit-action-badge";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api/client";
import { canExportAudit } from "@/lib/admin/permissions";
import { useAdminSession } from "@/lib/admin/use-admin-session";
import { useCursorList } from "@/lib/hooks/use-cursor-list";
import { unwrap } from "@/lib/query-provider";
import type { components } from "@/lib/api/schema";

type AuditItem = components["schemas"]["AdminAuditLogResponse"];

/** Actor types from docs/AUDIT_EVENTS.md § Actor Types. */
const ACTOR_TYPES = [
  "USER",
  "RECOMMENDER",
  "PUBLIC_VIEWER",
  "SYSTEM",
  "ADMIN",
  "WORKFLOW",
] as const;

/** Day-precision inputs → inclusive UTC instant range the backend can parse. */
function toInstant(day: string, endOfDay: boolean): string | undefined {
  if (!day) return undefined;
  return new Date(`${day}T${endOfDay ? "23:59:59" : "00:00:00"}Z`).toISOString();
}

export default function AdminAuditPage() {
  const t = useTranslations("admin");
  const format = useFormatter();
  const {
    admin,
    isLoading: sessionLoading,
    isError: sessionError,
    refetch: refetchSession,
  } = useAdminSession();

  const [actorType, setActorType] = useState("");
  const [action, setAction] = useState("");
  const [entityType, setEntityType] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [exporting, setExporting] = useState(false);

  const fromInstant = toInstant(from, false);
  const toInstant_ = toInstant(to, true);

  const filters = {
    actorType: actorType || undefined,
    action: action.trim() || undefined,
    entityType: entityType.trim() || undefined,
    from: fromInstant,
    to: toInstant_,
  };

  const list = useCursorList<AuditItem>(
    ["admin-audit-list", actorType, action, entityType, from, to],
    async (cursor) =>
      unwrap(
        await api.GET("/api/v1/admin/audit-logs", {
          params: { query: { ...filters, cursor } },
        }),
      ),
  );

  const canExport = canExportAudit(admin?.role);

  async function downloadCsv() {
    setExporting(true);
    try {
      const params = new URLSearchParams();
      if (filters.actorType) params.set("actorType", filters.actorType);
      if (filters.action) params.set("action", filters.action);
      if (filters.entityType) params.set("entityType", filters.entityType);
      if (filters.from) params.set("from", filters.from);
      if (filters.to) params.set("to", filters.to);
      const qs = params.toString();
      const res = await fetch(
        `/api/v1/admin/audit-logs/export${qs ? `?${qs}` : ""}`,
        { credentials: "include" },
      );
      if (!res.ok) {
        toast.error(t("audit.exportError"));
        return;
      }
      const blob = await res.blob();
      const objectUrl = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = objectUrl;
      anchor.download = `audit-logs-${new Date().toISOString().slice(0, 10)}.csv`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(objectUrl);
    } catch {
      toast.error(t("audit.exportError"));
    } finally {
      setExporting(false);
    }
  }

  if (sessionError) {
    return <AdminError onRetry={() => refetchSession()} />;
  }

  if (sessionLoading || !admin) {
    return <p className="text-sm text-blue-gray">{t("common.loading")}</p>;
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex flex-col gap-2">
          <Link
            href="/admin"
            className="inline-flex items-center gap-1 text-sm text-blue-gray hover:text-paper"
          >
            <ArrowLeft aria-hidden className="size-4" />
            {t("queue.backToDashboard")}
          </Link>
          <h1 className="text-2xl font-semibold text-paper">
            {t("audit.title")}
          </h1>
          <p className="text-sm text-blue-gray">{t("audit.subtitle")}</p>
        </div>
        {canExport && (
          <Button
            variant="secondary"
            onClick={() => downloadCsv()}
            disabled={exporting}
          >
            <Download aria-hidden />
            {t("audit.exportCsv")}
          </Button>
        )}
      </div>

      {/* Filter bar */}
      <div className="grid grid-cols-1 gap-3 rounded-card border border-navy bg-navy/30 p-4 sm:grid-cols-2 lg:grid-cols-5">
        <label className="flex flex-col gap-1 text-xs text-blue-gray">
          {t("audit.actorType")}
          <select
            value={actorType}
            onChange={(event) => setActorType(event.target.value)}
            className="h-9 rounded-card border border-navy bg-navy/40 px-2 text-sm text-paper focus:border-blue-gray/60 focus:outline-none"
          >
            <option value="">{t("audit.allActors")}</option>
            {ACTOR_TYPES.map((value) => (
              <option key={value} value={value}>
                {t.has(`actorTypes.${value}`) ? t(`actorTypes.${value}`) : value}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-xs text-blue-gray">
          {t("audit.action")}
          <input
            type="text"
            value={action}
            onChange={(event) => setAction(event.target.value)}
            placeholder={t("audit.actionPlaceholder")}
            className="h-9 rounded-card border border-navy bg-navy/40 px-2 text-sm text-paper placeholder:text-blue-gray/70 focus:border-blue-gray/60 focus:outline-none"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs text-blue-gray">
          {t("audit.entityType")}
          <input
            type="text"
            value={entityType}
            onChange={(event) => setEntityType(event.target.value)}
            placeholder={t("audit.entityPlaceholder")}
            className="h-9 rounded-card border border-navy bg-navy/40 px-2 text-sm text-paper placeholder:text-blue-gray/70 focus:border-blue-gray/60 focus:outline-none"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs text-blue-gray">
          {t("audit.from")}
          <input
            type="date"
            value={from}
            onChange={(event) => setFrom(event.target.value)}
            className="h-9 rounded-card border border-navy bg-navy/40 px-2 text-sm text-paper focus:border-blue-gray/60 focus:outline-none"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs text-blue-gray">
          {t("audit.to")}
          <input
            type="date"
            value={to}
            onChange={(event) => setTo(event.target.value)}
            className="h-9 rounded-card border border-navy bg-navy/40 px-2 text-sm text-paper focus:border-blue-gray/60 focus:outline-none"
          />
        </label>
      </div>

      <p className="flex items-center gap-2 text-xs text-blue-gray">
        <Lock aria-hidden className="size-3.5" />
        {t("audit.appendOnlyNote")}
      </p>

      {/* Table */}
      <div className="overflow-hidden rounded-card border border-navy bg-navy/30">
        <div className="hidden grid-cols-[auto_minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)] gap-4 border-b border-navy px-4 py-2.5 text-xs font-medium uppercase tracking-wide text-blue-gray md:grid">
          <span>{t("audit.colTime")}</span>
          <span>{t("audit.colActor")}</span>
          <span>{t("audit.colAction")}</span>
          <span>{t("audit.colEntity")}</span>
        </div>
        {list.isLoading ? (
          <div className="flex flex-col gap-2 p-4">
            {[0, 1, 2].map((i) => (
              <div
                key={i}
                className="h-9 animate-pulse rounded-card bg-navy/60"
              />
            ))}
          </div>
        ) : list.items.length === 0 ? (
          <p className="p-4 text-sm text-blue-gray">{t("audit.empty")}</p>
        ) : (
          <ul>
            {list.items.map((row) => (
              <li
                key={row.id}
                className="grid grid-cols-1 gap-1 border-b border-navy px-4 py-3 text-sm md:grid-cols-[auto_minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)] md:items-center md:gap-4"
              >
                <span className="text-xs text-blue-gray">
                  {row.createdAt
                    ? format.dateTime(new Date(row.createdAt), {
                        dateStyle: "medium",
                        timeStyle: "short",
                      })
                    : "—"}
                </span>
                <span className="flex flex-col">
                  <span className="text-paper">{row.actorType}</span>
                  {row.actorId && (
                    <span className="truncate font-mono text-[11px] text-blue-gray">
                      {row.actorId}
                    </span>
                  )}
                </span>
                <span>
                  {row.action && <AuditCodeBadge code={row.action} />}
                </span>
                <span className="flex flex-col gap-0.5">
                  {row.entityType && (
                    <AuditCodeBadge code={row.entityType} variant="muted" />
                  )}
                  {row.entityId && (
                    <span className="truncate font-mono text-[11px] text-blue-gray">
                      {row.entityId}
                    </span>
                  )}
                </span>
              </li>
            ))}
          </ul>
        )}
        {list.hasNext && (
          <div className="p-3">
            <Button
              variant="ghost"
              size="sm"
              className="w-full text-blue-gray hover:text-paper"
              onClick={() => list.loadMore()}
              disabled={list.isLoadingMore}
            >
              {t("audit.loadMore")}
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}
