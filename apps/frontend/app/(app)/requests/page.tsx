"use client";

import { Plus } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";
import Link from "next/link";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { BadgeStatus } from "@/components/verifolio/badge-status";
import { api } from "@/lib/api/client";
import { useCursorList } from "@/lib/hooks/use-cursor-list";
import { unwrap } from "@/lib/query-provider";
import {
  ReferenceRequest,
  useContactNames,
} from "@/lib/requests/queries";
import { statusBadgeVariant } from "@/lib/requests/status";
import { cn } from "@/lib/utils";

const FILTERS = [
  { key: "all", status: undefined },
  { key: "needsReview", status: "NEEDS_REVIEW" },
  { key: "inProgress", status: "IN_PROGRESS" },
  { key: "sent", status: "SENT" },
  { key: "completed", status: "COMPLETED" },
  { key: "declined", status: "DECLINED" },
] as const;

export default function RequestsPage() {
  const t = useTranslations();
  const format = useFormatter();
  const [filter, setFilter] = useState<(typeof FILTERS)[number]>(FILTERS[0]);
  const contactNames = useContactNames();

  const list = useCursorList<ReferenceRequest>(
    ["requests", filter.key],
    async (cursor) =>
      unwrap(
        await api.GET("/api/v1/reference-requests", {
          params: {
            query: {
              ...(cursor ? { cursor } : {}),
              ...(filter.status ? { status: filter.status } : {}),
            },
          },
        }),
      ),
  );

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-end justify-between gap-4">
        <h1 className="text-2xl font-extrabold text-ink">
          {t("requests.title")}
        </h1>
        <Button asChild>
          <Link href="/requests/new">
            <Plus />
            {t("requests.new")}
          </Link>
        </Button>
      </div>

      <div className="flex flex-wrap gap-2">
        {FILTERS.map((f) => (
          <button
            key={f.key}
            type="button"
            onClick={() => setFilter(f)}
            aria-pressed={filter.key === f.key}
            className={cn(
              "rounded-full px-3.5 py-1.5 text-sm font-semibold transition-colors",
              filter.key === f.key
                ? "bg-ink text-white"
                : "bg-border-soft text-slate-text hover:text-ink",
            )}
          >
            {t(`requests.filters.${f.key}`)}
          </button>
        ))}
      </div>

      {list.isLoading && (
        <div className="flex flex-col gap-3">
          <Skeleton className="h-20" />
          <Skeleton className="h-20" />
        </div>
      )}

      {!list.isLoading && list.items.length === 0 && (
        <Card className="p-10 text-center text-sm text-muted-text shadow-none">
          {t("requests.empty")}
        </Card>
      )}

      <div className="flex flex-col gap-3">
        {list.items.map((request) => {
          const contact = request.recommenderContactId
            ? contactNames.data?.get(request.recommenderContactId)
            : undefined;
          return (
            <Link key={request.id} href={`/requests/${request.id}`}>
              <Card className="flex items-center gap-4 p-4 shadow-none transition-shadow hover:shadow-card">
                <div className="min-w-0 flex-1">
                  <p className="truncate font-bold text-ink">
                    {contact?.name ?? t("requests.unknownRecommender")}
                  </p>
                  <p className="truncate text-sm text-muted-text">
                    {request.purpose || t("requests.noPurpose")}
                  </p>
                </div>
                {request.expiresAt && (
                  <span className="hidden text-xs text-muted-text sm:block">
                    {t("requests.expires", {
                      date: format.dateTime(new Date(request.expiresAt), {
                        dateStyle: "medium",
                      }),
                    })}
                  </span>
                )}
                <BadgeStatus
                  variant={statusBadgeVariant(request.status ?? "")}
                  label={t(`requests.statuses.${request.status}`)}
                />
              </Card>
            </Link>
          );
        })}
      </div>

      {list.hasNext && (
        <Button
          variant="secondary"
          onClick={list.loadMore}
          disabled={list.isLoadingMore}
          className="self-center"
        >
          {t("common.loadMore")}
        </Button>
      )}
    </div>
  );
}
