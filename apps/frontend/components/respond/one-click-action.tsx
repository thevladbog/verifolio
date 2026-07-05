"use client";

import { useMutation } from "@tanstack/react-query";
import { CheckCircle2 } from "lucide-react";
import { useTranslations } from "next-intl";
import { useParams } from "next/navigation";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { VerifolioWordmark } from "@/components/verifolio/wordmark";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";

type Action = "decline" | "report-abuse" | "stop-reminders";

const PATHS = {
  decline: "/api/v1/invitations/{token}/decline",
  "report-abuse": "/api/v1/invitations/{token}/report-abuse",
  "stop-reminders": "/api/v1/invitations/{token}/stop-reminders",
} as const;

/**
 * One-click email actions: a confirm button (never fire on GET — mail
 * scanners prefetch links), then a terminal thank-you state. Idempotent.
 */
export function OneClickAction({ action }: { action: Action }) {
  const t = useTranslations();
  const { token } = useParams<{ token: string }>();

  const perform = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST(PATHS[action], { params: { path: { token } } }),
      ),
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
