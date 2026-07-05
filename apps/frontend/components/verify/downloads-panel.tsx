"use client";

import { useMutation } from "@tanstack/react-query";
import { Download, EyeOff } from "lucide-react";
import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";
import type { components } from "@/lib/api/schema";

type DownloadItem = components["schemas"]["DownloadDto"];

/**
 * Client island: presigned URLs are fetched on click and opened
 * immediately — they are never part of the server-rendered HTML.
 */
export function DownloadsPanel({
  token,
  downloads,
}: {
  token: string;
  downloads: DownloadItem[];
}) {
  const t = useTranslations();

  const download = useMutation({
    mutationFn: async (item: DownloadItem) => {
      if (item.kind === "GENERATED_PDF") {
        return unwrap(
          await api.GET("/api/v1/verification-pages/{token}/download-url", {
            params: { path: { token } },
          }),
        );
      }
      return unwrap(
        await api.GET(
          "/api/v1/verification-pages/{token}/attachments/{attachmentId}/download-url",
          { params: { path: { token, attachmentId: item.id! } } },
        ),
      );
    },
    onSuccess: (data) => {
      if (data.url) window.open(data.url, "_blank", "noopener");
    },
  });

  return (
    <ul className="flex flex-col gap-2">
      {downloads.map((item) => (
        <li
          key={`${item.kind}-${item.id}`}
          className="flex items-center gap-3 rounded-control border border-border-light bg-white p-3 text-sm"
        >
          <span className="min-w-0 flex-1 truncate font-semibold text-ink">
            {item.filename ??
              (item.kind === "GENERATED_PDF"
                ? t("verify.generatedPdf")
                : t("verify.unnamedAttachment"))}
          </span>
          {item.downloadable ? (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => download.mutate(item)}
              disabled={download.isPending}
            >
              <Download />
              {t("common.download")}
            </Button>
          ) : (
            <span className="inline-flex items-center gap-1.5 text-xs text-muted-text">
              <EyeOff className="size-3.5" aria-hidden />
              {t("verify.notShared")}
            </span>
          )}
        </li>
      ))}
    </ul>
  );
}
