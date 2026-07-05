"use client";

import { useMutation } from "@tanstack/react-query";
import { CheckCircle2 } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { VerifolioWordmark } from "@/components/verifolio/wordmark";
import { api } from "@/lib/api/client";
import { RequestError } from "@/lib/query-provider";

export default function DataRequestsPage() {
  const t = useTranslations();
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);

  const request = useMutation({
    mutationFn: async () => {
      // Anti-enumeration: the backend always answers 202 with an empty body.
      const { error, response } = await api.POST(
        "/api/v1/privacy/recommender-requests",
        { body: { email: email.trim() } },
      );
      if (error !== undefined || !response.ok) {
        throw new RequestError(response.status, error);
      }
    },
    onSuccess: () => setSent(true),
  });

  return (
    <main className="flex flex-1 flex-col items-center gap-8 px-4 py-12">
      <VerifolioWordmark />
      <Card className="w-full max-w-lg">
        {sent ? (
          <CardContent className="flex items-start gap-3 p-6" role="status">
            <CheckCircle2
              className="mt-0.5 size-5 shrink-0 text-verified-green"
              aria-hidden
            />
            <p className="text-sm text-slate-text">
              {t("dataRequests.sentBody")}
            </p>
          </CardContent>
        ) : (
          <>
            <CardHeader>
              <CardTitle>{t("dataRequests.title")}</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="mb-4 text-sm text-slate-text">
                {t("dataRequests.intro")}
              </p>
              <form
                className="flex flex-col gap-4"
                onSubmit={(e) => {
                  e.preventDefault();
                  request.mutate();
                }}
                noValidate
              >
                <div className="flex flex-col gap-2">
                  <Label htmlFor="dr-email">{t("dataRequests.emailLabel")}</Label>
                  <Input
                    id="dr-email"
                    type="email"
                    required
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                  />
                </div>
                <Button
                  type="submit"
                  disabled={request.isPending || email.trim().length === 0}
                  className="self-start"
                >
                  {t("dataRequests.sendCode")}
                </Button>
              </form>
            </CardContent>
          </>
        )}
      </Card>
    </main>
  );
}
