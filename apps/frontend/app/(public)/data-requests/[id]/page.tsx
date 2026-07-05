"use client";

import { useMutation } from "@tanstack/react-query";
import { CheckCircle2 } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";
import { useParams } from "next/navigation";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { VerifolioWordmark } from "@/components/verifolio/wordmark";
import { api } from "@/lib/api/client";
import { errorMessage } from "@/lib/api/errors";
import type { components } from "@/lib/api/schema";
import { RequestError } from "@/lib/query-provider";

type VerifyResponse = components["schemas"]["RecommenderDsrVerifyResponse"];
type DsrType = NonNullable<
  components["schemas"]["RecommenderDsrVerifyRequest"]["type"]
>;

// Consent withdrawal is the primary recommender action, so it leads the list.
const TYPES: DsrType[] = [
  "CONSENT_WITHDRAWAL",
  "DELETION",
  "CORRECTION",
  "EXPORT",
  "REGION_MIGRATION",
];

export default function DataRequestVerifyPage() {
  const t = useTranslations();
  const format = useFormatter();
  const id = useParams<{ id: string }>().id;
  const [code, setCode] = useState("");
  const [type, setType] = useState<DsrType>("CONSENT_WITHDRAWAL");
  const [done, setDone] = useState<VerifyResponse | null>(null);

  const verify = useMutation({
    mutationFn: async (): Promise<VerifyResponse> => {
      const { data, error, response } = await api.POST(
        "/api/v1/privacy/recommender-requests/{id}/verify",
        {
          params: { path: { id } },
          body: { code: code.trim(), type },
        },
      );
      if (error !== undefined || !response.ok) {
        throw new RequestError(response.status, error);
      }
      return data as VerifyResponse;
    },
    onSuccess: (data) => setDone(data),
  });

  const wrap = (children: React.ReactNode) => (
    <main className="flex flex-1 flex-col items-center gap-8 px-4 py-12">
      <VerifolioWordmark />
      <Card className="w-full max-w-lg">{children}</Card>
    </main>
  );

  if (done) {
    return wrap(
      <CardContent className="flex items-start gap-3 p-6" role="status">
        <CheckCircle2
          className="mt-0.5 size-5 shrink-0 text-verified-green"
          aria-hidden
        />
        <p className="text-sm text-slate-text">
          {done.executed
            ? t("dataRequests.doneWithdrawal")
            : t("dataRequests.doneRecorded", {
                date: done.dueAt
                  ? format.dateTime(new Date(done.dueAt), {
                      dateStyle: "long",
                    })
                  : "",
              })}
        </p>
      </CardContent>,
    );
  }

  const inlineError =
    verify.error instanceof RequestError
      ? errorMessage(verify.error.body, t)
      : null;

  return wrap(
    <>
      <CardHeader>
        <CardTitle>{t("dataRequests.verifyTitle")}</CardTitle>
      </CardHeader>
      <CardContent>
        <p className="mb-4 text-sm text-slate-text">
          {t("dataRequests.verifyIntro")}
        </p>
        <form
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            verify.mutate();
          }}
          noValidate
        >
          <div className="flex flex-col gap-2">
            <Label htmlFor="dr-code">{t("dataRequests.codeLabel")}</Label>
            <Input
              id="dr-code"
              inputMode="numeric"
              autoComplete="one-time-code"
              required
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label>{t("dataRequests.typeLabel")}</Label>
            <Select value={type} onValueChange={(v) => setType(v as DsrType)}>
              <SelectTrigger
                className="w-full"
                aria-label={t("dataRequests.typeLabel")}
              >
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {TYPES.map((tp) => (
                  <SelectItem key={tp} value={tp}>
                    {t(`dataRequests.types.${tp}`)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          {inlineError && (
            <p className="text-sm text-danger" role="alert">
              {inlineError}
            </p>
          )}
          <Button
            type="submit"
            disabled={verify.isPending || code.trim().length === 0}
            className="self-start"
          >
            {t("dataRequests.verifySubmit")}
          </Button>
        </form>
      </CardContent>
    </>,
  );
}
