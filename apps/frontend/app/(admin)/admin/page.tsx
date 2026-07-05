"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, LogOut } from "lucide-react";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { AdminError } from "@/components/admin/admin-error";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api/client";
import { useAdminSession } from "@/lib/admin/use-admin-session";
import { unwrap } from "@/lib/query-provider";

export default function AdminDashboardPage() {
  const t = useTranslations("admin");
  const router = useRouter();
  const queryClient = useQueryClient();
  const {
    admin,
    isLoading,
    isError: sessionError,
    refetch: refetchSession,
  } = useAdminSession();

  const dashboard = useQuery({
    queryKey: ["admin-dashboard"],
    queryFn: async () => unwrap(await api.GET("/api/v1/admin/dashboard")),
    enabled: !!admin,
  });

  const logout = useMutation({
    mutationFn: async () =>
      unwrap(await api.POST("/api/v1/admin/auth/logout")),
    onSettled: () => {
      // Drop every cached admin query (the admin-session has a 5m staleTime) so a
      // subsequent login in the same browser can't render the previous admin.
      queryClient.clear();
      router.replace("/admin/login");
    },
    meta: { inlineError: true },
  });

  if (sessionError) {
    return <AdminError onRetry={() => refetchSession()} />;
  }

  if (isLoading || !admin) {
    return <p className="text-sm text-blue-gray">{t("common.loading")}</p>;
  }

  return (
    <div className="flex flex-col gap-8">
      <div className="flex items-start justify-between gap-4">
        <div className="flex flex-col gap-2">
          <h1 className="text-2xl font-semibold text-paper">
            {t("dashboard.greeting", { email: admin.email ?? "" })}
          </h1>
          <div className="flex items-center gap-2 text-sm text-blue-gray">
            <span className="rounded-full border border-blue-gray/40 px-2 py-0.5 text-xs font-semibold uppercase tracking-wide text-blue-gray">
              {admin.role ? t(`roles.${admin.role}`) : "—"}
            </span>
            {admin.region && <span>{admin.region}</span>}
          </div>
        </div>
        <Button
          variant="ghost"
          className="text-blue-gray hover:text-paper"
          onClick={() => logout.mutate()}
          disabled={logout.isPending}
        >
          <LogOut aria-hidden />
          {t("dashboard.logout")}
        </Button>
      </div>

      <section className="rounded-card border border-navy bg-navy/40 p-6">
        <h2 className="text-sm font-medium uppercase tracking-wide text-blue-gray">
          {t("dashboard.pendingTitle")}
        </h2>
        {dashboard.isError ? (
          <div className="mt-3">
            <AdminError
              message={t("dashboard.pendingError")}
              onRetry={() => dashboard.refetch()}
            />
          </div>
        ) : (
          <p className="mt-2 text-4xl font-semibold text-paper">
            {dashboard.isLoading ? "—" : dashboard.data?.dsrPendingTotal ?? 0}
          </p>
        )}
        <p className="mt-1 text-sm text-blue-gray">
          {t("dashboard.pendingHint")}
        </p>
        <Button asChild variant="secondary" className="mt-5">
          <Link href="/admin/data-requests">
            {t("dashboard.openQueue")}
            <ArrowRight aria-hidden />
          </Link>
        </Button>
      </section>
    </div>
  );
}
