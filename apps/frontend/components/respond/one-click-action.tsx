"use client";

import { useMutation } from "@tanstack/react-query";
import { CheckCircle2 } from "lucide-react";
import { useTranslations } from "next-intl";
import { useParams } from "next/navigation";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { VerifolioWordmark } from "@/components/verifolio/wordmark";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";

type Action = "decline" | "report-abuse" | "stop-reminders";

const PATHS = {
  decline: "/api/v1/invitations/{token}/decline",
  "report-abuse": "/api/v1/invitations/{token}/report-abuse",
  "stop-reminders": "/api/v1/invitations/{token}/stop-reminders",
} as const;

const DECLINE_REASONS = [
  "DONT_KNOW_REQUESTER",
  "TOO_BUSY",
  "NOT_COMFORTABLE",
  "OTHER",
] as const;
type DeclineReason = (typeof DECLINE_REASONS)[number];

/** Radix Select items cannot carry an empty value — sentinel for "omit". */
const NO_REASON = "PREFER_NOT_TO_SAY";

/**
 * One-click email actions: a confirm button (never fire on GET — mail
 * scanners prefetch links), then a terminal thank-you state. Idempotent.
 * Decline optionally carries a reason category; declining never requires one.
 */
export function OneClickAction({ action }: { action: Action }) {
  const t = useTranslations();
  const { token } = useParams<{ token: string }>();
  const [reason, setReason] = useState<DeclineReason | typeof NO_REASON>(
    NO_REASON,
  );

  const perform = useMutation({
    mutationFn: async () => {
      if (action === "decline" && reason !== NO_REASON) {
        return unwrap(
          await api.POST("/api/v1/invitations/{token}/decline", {
            params: { path: { token } },
            body: { reasonCategory: reason },
          }),
        );
      }
      // "Prefer not to say" omits the body entirely — nothing is recorded.
      return unwrap(
        await api.POST(PATHS[action], { params: { path: { token } } }),
      );
    },
  });

  return (
    <main className="flex flex-1 flex-col items-center gap-8 px-4 py-16">
      <VerifolioWordmark />
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>{t(`oneClick.${action}.title`)}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {perform.isSuccess ? (
            <div className="flex items-start gap-3" role="status">
              <CheckCircle2
                className="mt-0.5 size-5 shrink-0 text-verified-green"
                aria-hidden
              />
              <p className="text-sm text-slate-text">
                {t(`oneClick.${action}.done`)}
              </p>
            </div>
          ) : (
            <>
              <p className="text-sm text-slate-text">
                {t(`oneClick.${action}.body`)}
              </p>
              {action === "decline" && (
                <div className="flex flex-col gap-2">
                  <p className="text-sm text-muted-text">
                    {t("oneClick.decline.reasonLabel")}
                  </p>
                  <Select
                    value={reason}
                    onValueChange={(v) =>
                      setReason(v as DeclineReason | typeof NO_REASON)
                    }
                  >
                    <SelectTrigger
                      className="w-full"
                      aria-label={t("oneClick.decline.reasonLabel")}
                    >
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value={NO_REASON}>
                        {t("oneClick.decline.reasons.PREFER_NOT_TO_SAY")}
                      </SelectItem>
                      {DECLINE_REASONS.map((r) => (
                        <SelectItem key={r} value={r}>
                          {t(`oneClick.decline.reasons.${r}`)}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}
              <Button
                variant={action === "stop-reminders" ? "primary" : "danger"}
                onClick={() => perform.mutate()}
                disabled={perform.isPending}
                className="self-start"
              >
                {t(`oneClick.${action}.confirm`)}
              </Button>
            </>
          )}
        </CardContent>
      </Card>
    </main>
  );
}
