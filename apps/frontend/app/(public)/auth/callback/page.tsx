"use client";

import { useMutation } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useRef } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";

function CallbackInner() {
  const t = useTranslations();
  const router = useRouter();
  const token = useSearchParams().get("token");
  const fired = useRef(false);

  const createSession = useMutation({
    mutationFn: async (raw: string) =>
      unwrap(await api.POST("/api/v1/auth/sessions", { body: { token: raw } })),
    onSuccess: async () => {
      // Fresh accounts get an auto-created empty profile — route them through
      // onboarding (design 8a) before the dashboard.
      try {
        const profile = unwrap(await api.GET("/api/v1/profile"));
        router.replace(
          profile.displayName ? "/dashboard" : "/profile?welcome=1",
        );
      } catch {
        router.replace("/dashboard");
      }
    },
  });

  useEffect(() => {
    // Strict-mode double-invoke guard: the token is single-use.
    if (token && !fired.current) {
      fired.current = true;
      createSession.mutate(token);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  if (!token || createSession.isError) {
    return (
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle className="text-xl">{t("callback.invalidTitle")}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <p className="text-sm text-slate-text">{t("callback.invalidBody")}</p>
          <Button asChild>
            <Link href="/login">{t("login.title")}</Link>
          </Button>
        </CardContent>
      </Card>
    );
  }

  return (
    <p role="status" className="text-sm text-slate-text">
      {t("callback.verifying")}
    </p>
  );
}

export default function AuthCallbackPage() {
  return (
    <main className="flex flex-1 items-center justify-center px-4 py-16">
      <Suspense>
        <CallbackInner />
      </Suspense>
    </main>
  );
}
