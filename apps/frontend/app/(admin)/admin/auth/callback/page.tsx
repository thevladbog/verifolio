"use client";

import { useMutation } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useRef } from "react";

import { AdminCard } from "@/components/admin/admin-card";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";

function AdminCallbackInner() {
  const t = useTranslations("admin.callback");
  const router = useRouter();
  const token = useSearchParams().get("token");
  const fired = useRef(false);

  const consume = useMutation({
    mutationFn: async (raw: string) =>
      unwrap(
        await api.POST("/api/v1/admin/auth/magic-links/consume", {
          body: { token: raw },
        }),
      ),
    onSuccess: (data) => {
      // The pending-MFA cookie is now set; route to the branch the backend chose.
      router.replace(
        data.state === "ENROLL" ? "/admin/mfa/enroll" : "/admin/mfa/challenge",
      );
    },
    meta: { inlineError: true },
  });

  useEffect(() => {
    // Strict-mode double-invoke guard: the token is single-use.
    if (token && !fired.current) {
      fired.current = true;
      consume.mutate(token);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  if (!token || consume.isError) {
    return (
      <AdminCard title={t("invalidTitle")} description={t("invalidBody")}>
        <Button asChild variant="secondary">
          <Link href="/admin/login">{t("backToLogin")}</Link>
        </Button>
      </AdminCard>
    );
  }

  return (
    <p role="status" className="text-sm text-blue-gray">
      {t("verifying")}
    </p>
  );
}

export default function AdminAuthCallbackPage() {
  return (
    <div className="flex flex-1 items-center justify-center py-8">
      <Suspense>
        <AdminCallbackInner />
      </Suspense>
    </div>
  );
}
