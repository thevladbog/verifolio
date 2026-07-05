"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Trash2, Upload } from "lucide-react";
import { useTranslations } from "next-intl";
import { useRef, useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { BadgeStatus } from "@/components/verifolio/badge-status";
import { api } from "@/lib/api/client";
import { errorMessage } from "@/lib/api/errors";
import { RequestError, unwrap } from "@/lib/query-provider";

const KINDS = ["SCAN", "SIGNED_PDF", "DETACHED_SIGNATURE"] as const;
type Kind = (typeof KINDS)[number];

export function UploadsPanel() {
  const t = useTranslations();
  const queryClient = useQueryClient();
  const fileInput = useRef<HTMLInputElement>(null);
  const [kind, setKind] = useState<Kind>("SCAN");
  const [sharedPublicly, setSharedPublicly] = useState(false);
  const [targetUploadId, setTargetUploadId] = useState<string>();

  const uploads = useQuery({
    queryKey: ["recommender-uploads"],
    queryFn: async () => unwrap(await api.GET("/api/v1/recommender/uploads")),
  });

  const refresh = () =>
    queryClient.invalidateQueries({ queryKey: ["recommender-uploads"] });

  const upload = useMutation({
    mutationFn: async (file: File) => {
      const created = unwrap(
        await api.POST("/api/v1/recommender/uploads", {
          body: {
            kind,
            filename: file.name,
            mimeType: file.type,
            sizeBytes: file.size,
            sharedPublicly,
            targetUploadId:
              kind === "DETACHED_SIGNATURE" ? targetUploadId : null,
          },
        }),
      );
      // Constrained presigned PUT: content-type and length are signed.
      const put = await fetch(created.uploadUrl!, {
        method: "PUT",
        body: file,
        headers: { "Content-Type": file.type },
      });
      if (!put.ok) throw new RequestError(put.status, undefined);
      const confirmed = unwrap(
        await api.POST("/api/v1/recommender/uploads/{id}/confirm", {
          params: { path: { id: created.uploadId! } },
        }),
      );
      if (confirmed.status === "REJECTED") {
        throw new RequestError(400, {
          code: "VALIDATION_ERROR",
          message: confirmed.reason ?? "rejected",
        });
      }
      return confirmed;
    },
    onSuccess: () => {
      setSharedPublicly(false);
      refresh();
    },
    onError: (error) => {
      if (error instanceof RequestError) {
        toast.error(errorMessage(error.body, t));
      }
    },
  });

  const remove = useMutation({
    mutationFn: async (id: string) =>
      unwrap(
        await api.DELETE("/api/v1/recommender/uploads/{id}", {
          params: { path: { id } },
        }),
      ),
    onSuccess: refresh,
  });

  const readyTargets = (uploads.data?.items ?? []).filter(
    (u) => u.status === "READY" && u.kind !== "DETACHED_SIGNATURE",
  );
  const signatureNeedsTarget =
    kind === "DETACHED_SIGNATURE" && !targetUploadId;

  return (
    <Card className="shadow-none">
      <CardHeader>
        <CardTitle>{t("uploads.title")}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p className="text-sm text-muted-text">{t("uploads.hint")}</p>

        <div className="flex flex-col gap-2">
          {uploads.data?.items?.map((item) => (
            <div
              key={item.uploadId}
              className="flex items-center gap-3 rounded-control border border-border-light p-3 text-sm"
            >
              <span className="min-w-0 flex-1 truncate font-semibold text-ink">
                {item.filename}
              </span>
              <BadgeStatus
                variant={item.status === "READY" ? "verified" : "pending"}
                label={t(`uploads.kinds.${item.kind}`)}
              />
              {item.sharedPublicly && (
                <span className="text-xs text-muted-text">
                  {t("uploads.public")}
                </span>
              )}
              <Button
                variant="ghost"
                size="icon"
                aria-label={t("common.delete")}
                onClick={() => remove.mutate(item.uploadId!)}
              >
                <Trash2 className="text-danger" />
              </Button>
            </div>
          ))}
        </div>

        <div className="flex flex-col gap-3 rounded-card border border-dashed border-border-light p-4">
          <div className="flex flex-wrap items-center gap-3">
            <Select value={kind} onValueChange={(v) => setKind(v as Kind)}>
              <SelectTrigger
                className="w-56"
                aria-label={t("uploads.kindLabel")}
              >
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {KINDS.map((k) => (
                  <SelectItem key={k} value={k}>
                    {t(`uploads.kinds.${k}`)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>

            {kind === "DETACHED_SIGNATURE" && (
              <Select
                value={targetUploadId}
                onValueChange={setTargetUploadId}
              >
                <SelectTrigger
                  className="w-56"
                  aria-label={t("uploads.targetLabel")}
                >
                  <SelectValue placeholder={t("uploads.targetLabel")} />
                </SelectTrigger>
                <SelectContent>
                  {readyTargets.map((u) => (
                    <SelectItem key={u.uploadId} value={u.uploadId!}>
                      {u.filename}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          </div>

          <label className="flex items-start gap-3 text-sm text-ink">
            <Checkbox
              checked={sharedPublicly}
              onCheckedChange={(v) => setSharedPublicly(v === true)}
              className="mt-0.5"
            />
            <span>
              {t("uploads.shareToggle")}
              <span className="block text-xs text-muted-text">
                {t("uploads.shareConsent")}
              </span>
            </span>
          </label>

          <input
            ref={fileInput}
            type="file"
            className="hidden"
            aria-label={t("uploads.choose")}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) upload.mutate(file);
              e.target.value = "";
            }}
          />
          <Button
            type="button"
            variant="secondary"
            className="self-start"
            disabled={upload.isPending || signatureNeedsTarget}
            onClick={() => fileInput.current?.click()}
          >
            <Upload />
            {upload.isPending ? t("uploads.uploading") : t("uploads.choose")}
          </Button>
          {signatureNeedsTarget && (
            <p className="text-xs text-warning">{t("uploads.targetRequired")}</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
