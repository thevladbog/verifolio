"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, Share2 } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { BadgeStatus } from "@/components/verifolio/badge-status";
import { ShareLinkDialog } from "@/components/documents/share-link-dialog";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";

import { DOCUMENT_TYPES } from "../page";

export default function DocumentDetailPage() {
  const t = useTranslations();
  const format = useFormatter();
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [shareOpen, setShareOpen] = useState(false);

  const document = useQuery({
    queryKey: ["documents", "detail", id],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/v1/documents/{id}", {
          params: { path: { id } },
        }),
      ),
  });

  const shareLinks = useQuery({
    queryKey: ["share-links", id],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/v1/documents/{id}/share-links", {
          params: { path: { id } },
        }),
      ),
  });

  // Presigned URL: fetched on click, opened immediately, never stored.
  const download = useMutation({
    mutationFn: async (versionNumber: number) =>
      unwrap(
        await api.GET(
          "/api/v1/documents/{id}/versions/{versionNumber}/download-url",
          { params: { path: { id, versionNumber } } },
        ),
      ),
    onSuccess: (data) => {
      if (data.url) window.open(data.url, "_blank", "noopener");
    },
  });

  const revoke = useMutation({
    mutationFn: async (linkId: string) =>
      unwrap(
        await api.POST("/api/v1/share-links/{id}/revoke", {
          params: { path: { id: linkId } },
        }),
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ["share-links", id] }),
  });

  if (document.isLoading) return <Skeleton className="h-64" />;
  if (!document.data) return null;

  const doc = document.data;
  const typeLabel = DOCUMENT_TYPES.includes(
    doc.type as (typeof DOCUMENT_TYPES)[number],
  )
    ? t(`documents.types.${doc.type}`)
    : doc.type;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm text-muted-text">
            <Link href="/documents" className="hover:text-ink">
              {t("documents.title")}
            </Link>{" "}
            ›
          </p>
          <h1 className="mt-1 text-2xl font-extrabold text-ink">{typeLabel}</h1>
          <div className="mt-2 flex flex-wrap gap-2">
            <BadgeStatus
              variant="locked"
              label={t("documents.lockedVersion", {
                version: doc.currentVersionNumber ?? 1,
              })}
            />
          </div>
        </div>
        <div className="flex gap-2">
          {doc.currentVersionNumber != null && (
            <Button
              variant="secondary"
              onClick={() => download.mutate(doc.currentVersionNumber!)}
              disabled={download.isPending}
            >
              <Download />
              {t("documents.downloadPdf")}
            </Button>
          )}
          <Button onClick={() => setShareOpen(true)}>
            <Share2 />
            {t("share.open")}
          </Button>
        </div>
      </div>

      <Card className="max-w-2xl shadow-none">
        <CardHeader>
          <CardTitle>{t("documents.versionsTitle")}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          {doc.versions?.map((version) => (
            <div
              key={version.versionNumber}
              className="flex items-center gap-3 rounded-control border border-border-light p-3"
            >
              <span className="font-mono text-sm font-bold text-ink">
                v{version.versionNumber}
                {version.versionNumber === doc.currentVersionNumber &&
                  ` · ${t("documents.current")}`}
              </span>
              <span className="text-xs text-muted-text">
                {t("documents.lockedAt", {
                  date: format.dateTime(new Date(version.lockedAt ?? ""), {
                    dateStyle: "medium",
                    timeStyle: "short",
                  }),
                })}
              </span>
              <span
                className="ml-auto max-w-40 truncate font-mono text-[10px] text-muted-text"
                title={t("documents.sha256")}
              >
                {version.sha256Hash}
              </span>
              <Button
                variant="ghost"
                size="icon"
                aria-label={t("documents.downloadPdf")}
                onClick={() => download.mutate(version.versionNumber!)}
              >
                <Download />
              </Button>
            </div>
          ))}
        </CardContent>
      </Card>

      <Card className="max-w-2xl shadow-none">
        <CardHeader>
          <CardTitle>{t("share.activeTitle")}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          {(shareLinks.data?.items?.length ?? 0) === 0 && (
            <p className="text-sm text-muted-text">{t("share.none")}</p>
          )}
          {shareLinks.data?.items?.map((link) => {
            const revoked = !!link.revokedAt;
            return (
              <div
                key={link.id}
                className="flex items-center gap-3 rounded-control border border-border-light p-3"
              >
                <div className="min-w-0 flex-1 text-sm">
                  <p className="font-semibold text-ink">
                    v{link.versionNumber}
                    {link.expiresAt
                      ? ` · ${t("share.until", {
                          date: format.dateTime(new Date(link.expiresAt), {
                            dateStyle: "medium",
                          }),
                        })}`
                      : ` · ${t("share.noExpiry")}`}
                  </p>
                  <p className="text-xs text-muted-text">
                    {revoked
                      ? t("share.revokedAt", {
                          date: format.dateTime(new Date(link.revokedAt!), {
                            dateStyle: "medium",
                          }),
                        })
                      : t("share.createdAt", {
                          date: format.dateTime(new Date(link.createdAt ?? ""), {
                            dateStyle: "medium",
                          }),
                        })}
                  </p>
                </div>
                {revoked ? (
                  <BadgeStatus variant="failed" label={t("share.revoked")} />
                ) : (
                  <Button
                    variant="danger"
                    size="sm"
                    onClick={() => revoke.mutate(link.id!)}
                    disabled={revoke.isPending}
                  >
                    {t("share.revoke")}
                  </Button>
                )}
              </div>
            );
          })}
          <p className="mt-2 text-xs text-muted-text">{t("share.footer")}</p>
        </CardContent>
      </Card>

      <ShareLinkDialog
        documentId={id}
        open={shareOpen}
        onOpenChange={setShareOpen}
      />
    </div>
  );
}
