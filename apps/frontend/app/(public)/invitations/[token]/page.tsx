"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { VerifolioWordmark } from "@/components/verifolio/wordmark";
import { api } from "@/lib/api/client";
import { RequestError, unwrap } from "@/lib/query-provider";

export default function InvitationPage() {
  const t = useTranslations();
  const router = useRouter();
  const { token } = useParams<{ token: string }>();
  const [code, setCode] = useState("");
  const [codeRequested, setCodeRequested] = useState(false);

  const invitation = useQuery({
    queryKey: ["invitation", token],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/v1/invitations/{token}", {
          params: { path: { token } },
        }),
      ),
    retry: false,
  });

  const requestCode = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST("/api/v1/invitations/{token}/email-confirmations", {
          params: { path: { token } },
        }),
      ),
    onSuccess: () => setCodeRequested(true),
  });

  const confirmEmail = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST("/api/v1/invitations/{token}/confirm-email", {
          params: { path: { token } },
          body: { code },
        }),
      ),
    onSuccess: () => router.replace("/respond"),
  });

  const invalid =
    invitation.error instanceof RequestError ||
    (!invitation.isLoading && !invitation.data);

  return (
    <main className="flex flex-1 flex-col items-center gap-8 px-4 py-16">
      <VerifolioWordmark />

      {invitation.isLoading && <Skeleton className="h-64 w-full max-w-lg" />}

      {invalid && (
        <Card className="w-full max-w-lg">
          <CardHeader>
            <CardTitle>{t("invitation.invalidTitle")}</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-slate-text">
              {t("invitation.invalidBody")}
            </p>
          </CardContent>
        </Card>
      )}

      {invitation.data && (
        <Card className="w-full max-w-lg">
          <CardHeader>
            <CardTitle className="text-xl">
              {t("invitation.title", {
                requester: invitation.data.requesterName ?? "",
              })}
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <p className="text-sm text-slate-text">
              {t("invitation.intro", {
                template: invitation.data.templateName ?? "",
              })}
            </p>
            {invitation.data.purpose && (
              <p className="rounded-card bg-paper/60 p-4 text-sm text-ink">
                {invitation.data.purpose}
              </p>
            )}

            {!codeRequested ? (
              <>
                <p className="text-sm text-muted-text">
                  {t("invitation.confirmHint", {
                    email: invitation.data.recommenderEmailMasked ?? "",
                  })}
                </p>
                <Button
                  onClick={() => requestCode.mutate()}
                  disabled={requestCode.isPending}
                >
                  {t("invitation.sendCode")}
                </Button>
              </>
            ) : (
              <form
                className="flex flex-col gap-3"
                onSubmit={(e) => {
                  e.preventDefault();
                  confirmEmail.mutate();
                }}
              >
                <p role="status" className="text-sm text-slate-text">
                  {t("invitation.codeSent", {
                    email: invitation.data.recommenderEmailMasked ?? "",
                  })}
                </p>
                <div className="flex flex-col gap-2">
                  <Label htmlFor="code">{t("invitation.codeLabel")}</Label>
                  <Input
                    id="code"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    maxLength={6}
                    value={code}
                    onChange={(e) => setCode(e.target.value.trim())}
                    className="max-w-40 text-center font-mono text-lg tracking-[0.3em]"
                  />
                </div>
                <div className="flex gap-3">
                  <Button
                    type="submit"
                    disabled={code.length < 6 || confirmEmail.isPending}
                  >
                    {t("common.continue")}
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    onClick={() => requestCode.mutate()}
                    disabled={requestCode.isPending}
                  >
                    {t("invitation.resend")}
                  </Button>
                </div>
              </form>
            )}

            <p className="border-t border-border-light pt-4 text-xs text-muted-text">
              {t("invitation.footer")}{" "}
              <Link
                href={`/invitations/${token}/decline`}
                className="text-trust-blue hover:underline"
              >
                {t("invitation.declineLink")}
              </Link>
            </p>
          </CardContent>
        </Card>
      )}
    </main>
  );
}
