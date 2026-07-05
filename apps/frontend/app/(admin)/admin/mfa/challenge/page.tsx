"use client";

import { useMutation } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";

import { AdminCard } from "@/components/admin/admin-card";
import { MfaCodeForm } from "@/components/admin/mfa-code-form";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";

export default function AdminMfaChallengePage() {
  const t = useTranslations("admin.mfa");
  const router = useRouter();

  const verify = useMutation({
    mutationFn: async (code: string) =>
      unwrap(await api.POST("/api/v1/admin/auth/mfa/verify", { body: { code } })),
    onSuccess: () => router.replace("/admin"),
    meta: { inlineError: true },
  });

  return (
    <div className="flex flex-1 flex-col items-center justify-center py-8">
      <AdminCard title={t("challengeTitle")} description={t("challengeBody")}>
        <MfaCodeForm
          label={t("codeLabel")}
          submitLabel={t("verifySubmit")}
          pending={verify.isPending}
          error={verify.error}
          onSubmit={(code) => verify.mutate(code)}
        />
      </AdminCard>
    </div>
  );
}
