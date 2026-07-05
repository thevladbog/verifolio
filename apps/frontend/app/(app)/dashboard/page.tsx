"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowRight, FileText, Send, Users } from "lucide-react";
import { useTranslations } from "next-intl";
import Link from "next/link";

import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { BadgeStatus } from "@/components/verifolio/badge-status";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";
import { useContactNames } from "@/lib/requests/queries";
import { statusBadgeVariant, TERMINAL_STATUSES } from "@/lib/requests/status";
import { useSession } from "@/lib/use-session";

export default function DashboardPage() {
  const t = useTranslations();
  const { session } = useSession();
  const contactNames = useContactNames();

  // MVP has no dashboard aggregate; compose from list endpoints client-side.
  const requests = useQuery({
    queryKey: ["requests", "dashboard"],
    queryFn: async () =>
      unwrap(await api.GET("/api/v1/reference-requests", { params: {} })),
  });
  const documents = useQuery({
    queryKey: ["documents", "dashboard"],
    queryFn: async () =>
      unwrap(await api.GET("/api/v1/documents", { params: {} })),
  });

  const pending = (requests.data?.items ?? []).filter(
    (r) => !TERMINAL_STATUSES.has(r.status ?? ""),
  );
  const documentCount = documents.data?.items?.length ?? 0;
  const isLoading = requests.isLoading || documents.isLoading;
  const isEmpty =
    !isLoading && (requests.data?.items?.length ?? 0) === 0 && documentCount === 0;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-extrabold text-ink">
          {t("dashboard.welcome")}
        </h1>
        <p className="mt-1 text-sm text-muted-text">
          {isEmpty ? t("dashboard.emptyHint") : session?.email}
        </p>
      </div>

      {isLoading && (
        <div className="grid gap-4 sm:grid-cols-3">
          <Skeleton className="h-28" />
          <Skeleton className="h-28" />
          <Skeleton className="h-28" />
        </div>
      )}

      {isEmpty && (
        <div className="grid gap-4 sm:grid-cols-3">
          <Card className="flex flex-col gap-2 p-5 shadow-none">
            <Users className="size-6 text-blue-gray" aria-hidden />
            <p className="font-bold text-ink">{t("dashboard.step1Title")}</p>
            <p className="text-sm text-muted-text">{t("dashboard.step1Hint")}</p>
            <Button asChild variant="secondary" size="sm" className="mt-auto self-start">
              <Link href="/contacts">
                {t("contacts.add")} <ArrowRight />
              </Link>
            </Button>
          </Card>
          <Card className="flex flex-col gap-2 p-5 shadow-none">
            <Send className="size-6 text-blue-gray" aria-hidden />
            <p className="font-bold text-ink">{t("dashboard.step2Title")}</p>
            <p className="text-sm text-muted-text">{t("dashboard.step2Hint")}</p>
            <Button asChild size="sm" className="mt-auto self-start">
              <Link href="/requests/new">
                {t("requests.new")} <ArrowRight />
              </Link>
            </Button>
          </Card>
          <Card className="flex flex-col gap-2 p-5 shadow-none">
            <FileText className="size-6 text-blue-gray" aria-hidden />
            <p className="font-bold text-ink">{t("dashboard.step3Title")}</p>
            <p className="text-sm text-muted-text">{t("dashboard.step3Hint")}</p>
          </Card>
        </div>
      )}

      {!isEmpty && !isLoading && (
        <>
          <section className="flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-bold text-ink">
                {t("dashboard.pendingTitle", { count: pending.length })}
              </h2>
              <Link
                href="/requests"
                className="text-sm font-semibold text-trust-blue hover:underline"
              >
                {t("dashboard.allRequests")}
              </Link>
            </div>
            {pending.length === 0 && (
              <p className="text-sm text-muted-text">
                {t("dashboard.noPending")}
              </p>
            )}
            {pending.slice(0, 5).map((request) => {
              const contact = request.recommenderContactId
                ? contactNames.data?.get(request.recommenderContactId)
                : undefined;
              return (
                <Link key={request.id} href={`/requests/${request.id}`}>
                  <Card className="flex items-center gap-4 p-4 shadow-none transition-shadow hover:shadow-card">
                    <p className="min-w-0 flex-1 truncate font-semibold text-ink">
                      {contact?.name ?? t("requests.unknownRecommender")}
                    </p>
                    <BadgeStatus
                      variant={statusBadgeVariant(request.status ?? "")}
                      label={t(`requests.statuses.${request.status}`)}
                    />
                  </Card>
                </Link>
              );
            })}
          </section>

          <section className="flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-bold text-ink">
                {t("dashboard.documentsTitle", { count: documentCount })}
              </h2>
              <Link
                href="/documents"
                className="text-sm font-semibold text-trust-blue hover:underline"
              >
                {t("dashboard.allDocuments")}
              </Link>
            </div>
            {documentCount === 0 && (
              <p className="text-sm text-muted-text">
                {t("dashboard.noDocuments")}
              </p>
            )}
          </section>
        </>
      )}
    </div>
  );
}
