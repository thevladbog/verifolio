"use client";

import { FileText } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";
import Link from "next/link";

import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { BadgeStatus } from "@/components/verifolio/badge-status";
import { api } from "@/lib/api/client";
import { useCursorList } from "@/lib/hooks/use-cursor-list";
import { unwrap } from "@/lib/query-provider";
import type { components } from "@/lib/api/schema";

type Document = components["schemas"]["DocumentResponse"];

export const DOCUMENT_TYPES = [
  "REFERENCE_LETTER",
  "EMPLOYMENT_PROOF",
  "IMMIGRATION_REFERENCE",
  "VISA_SUPPORT_LETTER",
  "ACADEMIC_RECOMMENDATION",
  "CLIENT_TESTIMONIAL",
  "CHARACTER_REFERENCE",
  "CUSTOM",
] as const;

export default function DocumentsPage() {
  const t = useTranslations();
  const format = useFormatter();

  const list = useCursorList<Document>(["documents"], async (cursor) =>
    unwrap(
      await api.GET("/api/v1/documents", {
        params: { query: cursor ? { cursor } : {} },
      }),
    ),
  );

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-extrabold text-ink">
          {t("documents.title")}
        </h1>
        <p className="mt-1 text-sm text-muted-text">
          {t("documents.subtitle")}
        </p>
      </div>

      {list.isLoading && (
        <div className="grid gap-4 sm:grid-cols-2">
          <Skeleton className="h-32" />
          <Skeleton className="h-32" />
        </div>
      )}

      {!list.isLoading && list.items.length === 0 && (
        <Card className="p-10 text-center text-sm text-muted-text shadow-none">
          {t("documents.empty")}
        </Card>
      )}

      <div className="grid gap-4 sm:grid-cols-2">
        {list.items.map((doc) => (
          <Link key={doc.id} href={`/documents/${doc.id}`}>
            <Card className="flex h-full flex-col gap-3 p-5 shadow-none transition-shadow hover:shadow-card">
              <div className="flex items-start justify-between gap-2">
                <FileText className="size-6 text-blue-gray" aria-hidden />
                <BadgeStatus
                  variant="locked"
                  label={t("documents.lockedVersion", {
                    version: doc.currentVersionNumber ?? 1,
                  })}
                />
              </div>
              <p className="font-bold text-ink">
                {DOCUMENT_TYPES.includes(
                  doc.type as (typeof DOCUMENT_TYPES)[number],
                )
                  ? t(`documents.types.${doc.type}`)
                  : doc.type}
              </p>
              {doc.createdAt && (
                <p className="text-xs text-muted-text">
                  {format.dateTime(new Date(doc.createdAt), {
                    dateStyle: "medium",
                  })}
                </p>
              )}
            </Card>
          </Link>
        ))}
      </div>

      {list.hasNext && (
        <button
          type="button"
          onClick={list.loadMore}
          className="self-center text-sm font-semibold text-trust-blue hover:underline"
        >
          {t("common.loadMore")}
        </button>
      )}
    </div>
  );
}
