"use client";

import { useMutation } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api/client";
import { consentParagraphs, useConsentText } from "@/lib/hooks/use-consent-text";
import { unwrap } from "@/lib/query-provider";
import type { components } from "@/lib/api/schema";

type ConsentTexts = components["schemas"]["ConsentTextsDto"];

/**
 * Explicit consent gate — no answer inputs exist anywhere until accept.
 * The consent copy comes from the backend (the same deployment that
 * records the grant against its versioned text refs), never from i18n.
 */
export function ConsentGate({
  consents,
  onDecided,
}: {
  consents?: ConsentTexts;
  onDecided: (declined: boolean) => void;
}) {
  const t = useTranslations();
  const [crossBorder, setCrossBorder] = useState(false);
  const [crossBorderRelevant, setCrossBorderRelevant] = useState(false);

  const processingText = useConsentText("RECOMMENDER_PROCESSING_CONSENT");
  const crossBorderText = useConsentText("CROSS_BORDER_TRANSFER_CONSENT");

  const decide = useMutation({
    mutationFn: async (accepted: boolean) =>
      unwrap(
        await api.POST("/api/v1/recommender/consent", {
          body: {
            accepted,
            crossBorderAccepted:
              accepted && crossBorderRelevant ? crossBorder : null,
          },
        }),
      ),
    onSuccess: (_, accepted) => onDecided(!accepted),
  });

  const processingRef = consents?.processing;
  // The recommender must have seen the actual consent text before agreeing.
  const textsShown =
    !!processingText.data && (!crossBorderRelevant || !!crossBorderText.data);

  return (
    <Card className="w-full max-w-2xl">
      <CardHeader>
        <CardTitle>
          {processingText.data?.title ??
            (processingText.isError ? (
              <span className="text-danger">{t("consent.loadError")}</span>
            ) : (
              <Skeleton className="h-6 w-64" />
            ))}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="rounded-card bg-paper/60 p-4 text-sm leading-relaxed text-ink">
          {processingText.data ? (
            consentParagraphs(processingText.data.body).map((paragraph, i) => (
              <p key={i} className={i > 0 ? "mt-2" : undefined}>
                {paragraph}
              </p>
            ))
          ) : processingText.isError ? (
            <div className="flex flex-col items-start gap-2 text-danger">
              <p>{t("consent.loadError")}</p>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => processingText.refetch()}
              >
                {t("common.retry")}
              </Button>
            </div>
          ) : (
            <div className="flex flex-col gap-2">
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-3/4" />
            </div>
          )}
          {processingRef && (
            <p className="mt-2 text-xs text-muted-text">
              {t("consent.textRef", {
                id: processingRef.textId ?? "",
                version: processingRef.version ?? 1,
              })}
            </p>
          )}
        </div>

        <label className="flex items-start gap-3 text-sm text-ink">
          <Checkbox
            checked={crossBorderRelevant}
            onCheckedChange={(v) => setCrossBorderRelevant(v === true)}
            className="mt-0.5"
          />
          {t("consent.crossBorderQuestion")}
        </label>

        {crossBorderRelevant && (
          <label className="ml-8 flex items-start gap-3 rounded-card border border-border-light p-3 text-sm text-ink">
            <Checkbox
              checked={crossBorder}
              onCheckedChange={(v) => setCrossBorder(v === true)}
              className="mt-0.5"
            />
            {crossBorderText.data ? (
              <span>
                {consentParagraphs(crossBorderText.data.body).map(
                  (paragraph, i) => (
                    <span key={i} className={i > 0 ? "mt-2 block" : "block"}>
                      {paragraph}
                    </span>
                  ),
                )}
              </span>
            ) : crossBorderText.isError ? (
              <span className="flex flex-col items-start gap-2 text-danger">
                {t("consent.loadError")}
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => crossBorderText.refetch()}
                >
                  {t("common.retry")}
                </Button>
              </span>
            ) : (
              <Skeleton className="h-4 w-full" />
            )}
          </label>
        )}

        <div className="flex gap-3 border-t border-border-light pt-4">
          <Button
            variant="success"
            onClick={() => decide.mutate(true)}
            disabled={
              decide.isPending ||
              !textsShown ||
              (crossBorderRelevant && !crossBorder)
            }
          >
            {t("consent.accept")}
          </Button>
          <Button
            variant="danger"
            onClick={() => decide.mutate(false)}
            disabled={decide.isPending}
          >
            {t("consent.decline")}
          </Button>
        </div>
        <p className="text-xs text-muted-text">{t("consent.declineHint")}</p>
      </CardContent>
    </Card>
  );
}
