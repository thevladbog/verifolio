"use client";

import { useMutation } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";
import type { components } from "@/lib/api/schema";

type ConsentTexts = components["schemas"]["ConsentTextsDto"];

/**
 * Explicit consent gate — no answer inputs exist anywhere until accept.
 * The backend records the grant against the versioned text refs it
 * returned; per-cell consent copy is keyed by that textId.
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

  return (
    <Card className="w-full max-w-2xl">
      <CardHeader>
        <CardTitle>{t("consent.title")}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="rounded-card bg-paper/60 p-4 text-sm leading-relaxed text-ink">
          <p>{t("consent.processingText")}</p>
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
            {t("consent.crossBorderText")}
          </label>
        )}

        <div className="flex gap-3 border-t border-border-light pt-4">
          <Button
            variant="success"
            onClick={() => decide.mutate(true)}
            disabled={decide.isPending || (crossBorderRelevant && !crossBorder)}
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
