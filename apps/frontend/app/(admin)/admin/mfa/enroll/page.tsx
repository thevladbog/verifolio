"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import QRCode from "react-qr-code";

import { AdminCard } from "@/components/admin/admin-card";
import { MfaCodeForm } from "@/components/admin/mfa-code-form";
import { api } from "@/lib/api/client";
import { RequestError, unwrap } from "@/lib/query-provider";

export default function AdminMfaEnrollPage() {
  const t = useTranslations("admin.mfa");
  const router = useRouter();

  const enrollment = useQuery({
    queryKey: ["admin-mfa-enrollment"],
    queryFn: async () =>
      unwrap(await api.GET("/api/v1/admin/auth/mfa/enrollment")),
    retry: false,
    staleTime: Infinity,
  });

  const enroll = useMutation({
    mutationFn: async (code: string) =>
      unwrap(await api.POST("/api/v1/admin/auth/mfa/enroll", { body: { code } })),
    onSuccess: () => router.replace("/admin"),
    meta: { inlineError: true },
  });

  // No pending-MFA cookie (expired / direct hit) → send back to login.
  if (enrollment.error instanceof RequestError) {
    return (
      <div className="flex flex-1 items-center justify-center py-8">
        <AdminCard title={t("expiredTitle")} description={t("expiredBody")}>
          <a
            href="/admin/login"
            className="text-sm font-semibold text-blue-gray underline underline-offset-4"
          >
            {t("backToLogin")}
          </a>
        </AdminCard>
      </div>
    );
  }

  return (
    <div className="flex flex-1 flex-col items-center justify-center py-8">
      <AdminCard title={t("enrollTitle")} description={t("enrollBody")}>
        {enrollment.data?.otpauthUri && (
          <div className="mb-5 flex flex-col items-center gap-4">
            <div className="rounded-card bg-white p-3">
              <QRCode value={enrollment.data.otpauthUri} size={160} />
            </div>
            <div className="w-full text-center">
              <p className="text-xs uppercase tracking-wide text-blue-gray">
                {t("secretLabel")}
              </p>
              <code
                data-testid="mfa-secret"
                className="mt-1 block break-all font-mono text-sm text-paper"
              >
                {enrollment.data.secretBase32}
              </code>
            </div>
          </div>
        )}
        <MfaCodeForm
          label={t("codeLabel")}
          submitLabel={t("enrollSubmit")}
          pending={enroll.isPending}
          error={enroll.error}
          onSubmit={(code) => enroll.mutate(code)}
        />
      </AdminCard>
    </div>
  );
}
