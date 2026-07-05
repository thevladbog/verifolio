"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Loader2 } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { BadgeStatus } from "@/components/verifolio/badge-status";
import { StatusTimeline } from "@/components/requests/status-timeline";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";
import { useContactNames, useTemplates } from "@/lib/requests/queries";
import {
  canCancel,
  canReview,
  canSend,
  statusBadgeVariant,
  TERMINAL_STATUSES,
} from "@/lib/requests/status";
import { useLocale } from "next-intl";

export default function RequestDetailPage() {
  const t = useTranslations();
  const format = useFormatter();
  const locale = useLocale();
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [correctionOpen, setCorrectionOpen] = useState(false);
  const [correctionMessage, setCorrectionMessage] = useState("");
  const [acceptedDocumentId, setAcceptedDocumentId] = useState<string>();

  const request = useQuery({
    queryKey: ["requests", "detail", id],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/v1/reference-requests/{id}", {
          params: { path: { id } },
        }),
      ),
  });

  const contacts = useContactNames();
  const templates = useTemplates(locale);

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["requests"] });
  };

  const send = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST("/api/v1/reference-requests/{id}/send", {
          params: { path: { id } },
        }),
      ),
    onSuccess: invalidate,
  });

  const cancel = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST("/api/v1/reference-requests/{id}/cancel", {
          params: { path: { id } },
        }),
      ),
    onSuccess: invalidate,
  });

  const accept = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST("/api/v1/reference-requests/{id}/accept", {
          params: { path: { id } },
        }),
      ),
    onSuccess: (data) => {
      setAcceptedDocumentId(data.documentId);
      invalidate();
    },
  });

  const requestCorrection = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST("/api/v1/reference-requests/{id}/request-correction", {
          params: { path: { id } },
          body: { message: correctionMessage || null },
        }),
      ),
    onSuccess: () => {
      setCorrectionOpen(false);
      setCorrectionMessage("");
      invalidate();
    },
  });

  if (request.isLoading) {
    return <Skeleton className="h-64" />;
  }
  if (!request.data) return null;

  const data = request.data;
  const status = data.status ?? "";
  const contact = data.recommenderContactId
    ? contacts.data?.get(data.recommenderContactId)
    : undefined;
  const template = templates.data?.items?.find(
    (tpl) => tpl.id === data.templateId,
  );
  const isTerminalSideExit =
    TERMINAL_STATUSES.has(status) && status !== "COMPLETED";

  // Accept in-flight / just-accepted state (design 9e).
  if (accept.isPending || acceptedDocumentId) {
    return (
      <div className="mx-auto flex max-w-md flex-col items-center gap-4 py-24 text-center">
        {accept.isPending ? (
          <Loader2 className="size-8 animate-spin text-ink" aria-hidden />
        ) : (
          <CheckCircle2 className="size-8 text-verified-green" aria-hidden />
        )}
        <h1 className="text-xl font-extrabold text-ink">
          {accept.isPending
            ? t("review.generating")
            : t("review.generated")}
        </h1>
        <p className="text-sm text-muted-text">
          {accept.isPending
            ? t("review.generatingHint")
            : t("review.generatedHint")}
        </p>
        {acceptedDocumentId && (
          <Button asChild>
            <Link href={`/documents/${acceptedDocumentId}`}>
              {t("review.openDocument")}
            </Link>
          </Button>
        )}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm text-muted-text">
            <Link href="/requests" className="hover:text-ink">
              {t("requests.title")}
            </Link>{" "}
            ›
          </p>
          <h1 className="mt-1 text-2xl font-extrabold text-ink">
            {template?.name ?? t("requests.title")}
          </h1>
          <p className="mt-1 text-sm text-muted-text">
            {contact
              ? `${contact.name} · ${contact.email}`
              : t("requests.unknownRecommender")}
          </p>
        </div>
        <BadgeStatus
          variant={statusBadgeVariant(status)}
          label={t(`requests.statuses.${status}`)}
        />
      </div>

      {isTerminalSideExit ? (
        <Card className="border-warning/40 bg-warning/5 p-4 text-sm text-slate-text shadow-none">
          {t(`detail.terminal.${status}`)}
        </Card>
      ) : (
        <StatusTimeline status={status} />
      )}

      <Card className="max-w-2xl shadow-none">
        <CardHeader>
          <CardTitle>{t("detail.aboutTitle")}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2 text-sm">
          {data.purpose && (
            <p>
              <span className="text-muted-text">{t("builder.purpose")}: </span>
              <span className="text-ink">{data.purpose}</span>
            </p>
          )}
          {data.expiresAt && (
            <p>
              <span className="text-muted-text">{t("detail.expiresAt")}: </span>
              <span className="text-ink">
                {format.dateTime(new Date(data.expiresAt), {
                  dateStyle: "long",
                })}
              </span>
            </p>
          )}
        </CardContent>
      </Card>

      {canReview(status) && (
        <Card className="max-w-2xl border-ink/20 shadow-card">
          <CardHeader>
            <CardTitle>{t("review.title")}</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <p className="text-sm text-slate-text">{t("review.hint")}</p>
            <div className="flex gap-3">
              <Button
                variant="success"
                onClick={() => accept.mutate()}
                disabled={accept.isPending}
              >
                {t("review.accept")}
              </Button>
              <Button
                variant="secondary"
                onClick={() => setCorrectionOpen(true)}
              >
                {t("review.requestCorrection")}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      <div className="flex gap-3">
        {canSend(status) && (
          <Button onClick={() => send.mutate()} disabled={send.isPending}>
            {t("detail.send")}
          </Button>
        )}
        {canCancel(status) && (
          <Button
            variant="danger"
            onClick={() => cancel.mutate()}
            disabled={cancel.isPending}
          >
            {t("detail.cancel")}
          </Button>
        )}
      </div>

      <Dialog open={correctionOpen} onOpenChange={setCorrectionOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("review.correctionTitle")}</DialogTitle>
            <DialogDescription>{t("review.correctionHint")}</DialogDescription>
          </DialogHeader>
          <Textarea
            value={correctionMessage}
            maxLength={2000}
            onChange={(e) => setCorrectionMessage(e.target.value)}
            placeholder={t("review.correctionPlaceholder")}
            aria-label={t("review.correctionTitle")}
          />
          <DialogFooter>
            <Button
              variant="secondary"
              onClick={() => setCorrectionOpen(false)}
            >
              {t("common.cancel")}
            </Button>
            <Button
              onClick={() => requestCorrection.mutate()}
              disabled={requestCorrection.isPending}
            >
              {t("review.requestCorrection")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
