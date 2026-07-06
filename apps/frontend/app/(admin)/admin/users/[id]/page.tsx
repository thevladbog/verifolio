"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, FileText, KeyRound, ShieldCheck, UserRound } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";
import Link from "next/link";
import { useParams } from "next/navigation";

import { AdminError } from "@/components/admin/admin-error";
import { UserStatusBadge } from "@/components/admin/user-status-badge";
import { api } from "@/lib/api/client";
import { useAdminSession } from "@/lib/admin/use-admin-session";
import { RequestError, unwrap } from "@/lib/query-provider";
import { cn } from "@/lib/utils";
import type { components } from "@/lib/api/schema";

type Card = components["schemas"]["AdminUserCardResponse"];

function CountCard({
  icon,
  label,
  value,
  hint,
}: {
  icon: React.ReactNode;
  label: string;
  value: number | string;
  hint?: string;
}) {
  return (
    <div className="flex flex-col gap-1 rounded-card border border-navy bg-navy/40 p-4">
      <div className="flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-blue-gray">
        {icon}
        {label}
      </div>
      <p className="text-2xl font-semibold text-paper">{value}</p>
      {hint && <p className="text-xs text-blue-gray">{hint}</p>}
    </div>
  );
}

function DefinitionRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs uppercase tracking-wide text-blue-gray">{label}</dt>
      <dd className="text-sm text-paper">{value}</dd>
    </div>
  );
}

export default function AdminUserCardPage() {
  const t = useTranslations("admin");
  const format = useFormatter();
  const params = useParams<{ id: string }>();
  const id = params.id;
  const {
    admin,
    isLoading: sessionLoading,
    isError: sessionError,
    refetch: refetchSession,
  } = useAdminSession();

  const card = useQuery({
    queryKey: ["admin-user-card", id],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/v1/admin/users/{id}", {
          params: { path: { id } },
        }),
      ),
    enabled: !!admin && !!id,
    retry: false,
  });

  if (sessionError) {
    return <AdminError onRetry={() => refetchSession()} />;
  }

  if (sessionLoading || !admin) {
    return <p className="text-sm text-blue-gray">{t("common.loading")}</p>;
  }

  const backLink = (
    <Link
      href="/admin/users"
      className="inline-flex items-center gap-1 text-sm text-blue-gray hover:text-paper"
    >
      <ArrowLeft aria-hidden className="size-4" />
      {t("card.back")}
    </Link>
  );

  if (card.isError) {
    const notFound =
      card.error instanceof RequestError && card.error.status === 404;
    return (
      <div className="flex flex-col gap-6">
        {backLink}
        {notFound ? (
          <div
            role="alert"
            className="rounded-card border border-navy bg-navy/40 p-6 text-sm text-blue-gray"
          >
            <p className="font-medium text-paper">{t("card.notFoundTitle")}</p>
            <p className="mt-1">{t("card.notFoundBody")}</p>
          </div>
        ) : (
          <AdminError onRetry={() => card.refetch()} />
        )}
      </div>
    );
  }

  if (card.isLoading || !card.data) {
    return (
      <div className="flex flex-col gap-6">
        {backLink}
        <p className="text-sm text-blue-gray">{t("common.loading")}</p>
      </div>
    );
  }

  const data: Card = card.data;
  const account = data.account;
  const profile = data.profile;
  const dsrTotal = Object.values(data.dsrCountsByStatus ?? {}).reduce(
    (sum, n) => sum + n,
    0,
  );
  const dsrBreakdown = Object.entries(data.dsrCountsByStatus ?? {})
    .filter(([, n]) => n > 0)
    .map(([s, n]) => `${t.has(`statuses.${s}`) ? t(`statuses.${s}`) : s}: ${n}`)
    .join(" · ");
  const fmtDate = (iso: string | null | undefined) =>
    iso ? format.dateTime(new Date(iso), { dateStyle: "medium" }) : "—";
  const fmtDateTime = (iso: string | null | undefined) =>
    iso
      ? format.dateTime(new Date(iso), {
          dateStyle: "medium",
          timeStyle: "short",
        })
      : "—";

  return (
    <div className="flex flex-col gap-6">
      {backLink}

      {/* Header */}
      <div className="flex flex-col gap-3 rounded-card border border-navy bg-navy/40 p-6">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-xl font-semibold text-paper">{account?.email}</h1>
          {account?.status && <UserStatusBadge status={account.status} />}
        </div>
        <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3">
          <DefinitionRow
            label={t("card.region")}
            value={account?.region ?? "—"}
          />
          <DefinitionRow
            label={t("card.createdAt")}
            value={fmtDate(account?.createdAt)}
          />
          {account?.deletedAt && (
            <DefinitionRow
              label={t("card.deletedAt")}
              value={fmtDate(account.deletedAt)}
            />
          )}
        </dl>
      </div>

      {/* Profile */}
      <section className="flex flex-col gap-3 rounded-card border border-navy bg-navy/40 p-6">
        <h2 className="text-sm font-medium uppercase tracking-wide text-blue-gray">
          {t("card.profileTitle")}
        </h2>
        {profile ? (
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3">
            <DefinitionRow
              label={t("card.displayName")}
              value={profile.displayName ?? "—"}
            />
            <DefinitionRow
              label={t("card.legalName")}
              value={profile.legalName ?? "—"}
            />
            <DefinitionRow
              label={t("card.locale")}
              value={profile.preferredLocale ?? "—"}
            />
          </dl>
        ) : (
          <p className="text-sm text-blue-gray">{t("card.noProfile")}</p>
        )}
      </section>

      {/* Count cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <CountCard
          icon={<FileText aria-hidden className="size-4" />}
          label={t("card.documents")}
          value={data.documentCount ?? 0}
          hint={t("card.lockedDocuments", {
            count: data.lockedDocumentCount ?? 0,
          })}
        />
        <CountCard
          icon={<ShieldCheck aria-hidden className="size-4" />}
          label={t("card.dsrs")}
          value={dsrTotal}
          hint={dsrBreakdown || undefined}
        />
        <CountCard
          icon={<UserRound aria-hidden className="size-4" />}
          label={t("card.consents")}
          value={data.consentCount ?? 0}
        />
        <CountCard
          icon={<KeyRound aria-hidden className="size-4" />}
          label={t("card.sessions")}
          value={data.sessionCount ?? 0}
        />
      </div>

      {/* Consent history */}
      <section className="flex flex-col gap-3 rounded-card border border-navy bg-navy/40 p-6">
        <h2 className="text-sm font-medium uppercase tracking-wide text-blue-gray">
          {t("card.consentHistory")}
        </h2>
        {data.consents && data.consents.length > 0 ? (
          <ul className="flex flex-col divide-y divide-navy">
            {data.consents.map((consent, i) => (
              <li
                key={`${consent.consentType}-${consent.policyTextVersion}-${i}`}
                className="flex flex-wrap items-center justify-between gap-2 py-2.5"
              >
                <div className="flex flex-col gap-0.5">
                  <span className="text-sm font-medium text-paper">
                    {consent.consentType}
                  </span>
                  <span className="text-xs text-blue-gray">
                    {t("card.policyVersion", {
                      version: consent.policyTextVersion ?? "—",
                    })}
                  </span>
                </div>
                <div className="flex flex-col items-end gap-0.5 text-xs text-blue-gray">
                  <span
                    className={cn(
                      "font-medium",
                      consent.status === "GRANTED"
                        ? "text-verified-green"
                        : "text-blue-gray",
                    )}
                  >
                    {consent.status}
                  </span>
                  <span>
                    {consent.grantedAt
                      ? t("card.grantedOn", {
                          date: fmtDateTime(consent.grantedAt),
                        })
                      : consent.withdrawnAt
                        ? t("card.withdrawnOn", {
                            date: fmtDateTime(consent.withdrawnAt),
                          })
                        : fmtDateTime(consent.createdAt)}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-blue-gray">{t("card.noConsents")}</p>
        )}
      </section>

      {/* Sessions */}
      <section className="flex flex-col gap-3 rounded-card border border-navy bg-navy/40 p-6">
        <h2 className="text-sm font-medium uppercase tracking-wide text-blue-gray">
          {t("card.sessionsTitle")}
        </h2>
        {data.sessions && data.sessions.length > 0 ? (
          <ul className="flex flex-col divide-y divide-navy">
            {data.sessions.map((session, i) => (
              <li
                key={`${session.createdAt}-${i}`}
                className="grid grid-cols-2 gap-2 py-2.5 text-xs sm:grid-cols-3"
              >
                <div className="flex flex-col gap-0.5">
                  <span className="uppercase tracking-wide text-blue-gray">
                    {t("card.sessionCreated")}
                  </span>
                  <span className="text-paper">
                    {fmtDateTime(session.createdAt)}
                  </span>
                </div>
                <div className="flex flex-col gap-0.5">
                  <span className="uppercase tracking-wide text-blue-gray">
                    {t("card.sessionExpires")}
                  </span>
                  <span className="text-paper">
                    {fmtDateTime(session.expiresAt)}
                  </span>
                </div>
                <div className="flex flex-col gap-0.5">
                  <span className="uppercase tracking-wide text-blue-gray">
                    {t("card.sessionRevoked")}
                  </span>
                  <span className="text-paper">
                    {session.revokedAt ? fmtDateTime(session.revokedAt) : "—"}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-blue-gray">{t("card.noSessions")}</p>
        )}
      </section>

      <p className="rounded-card border border-navy bg-navy/20 p-4 text-xs text-blue-gray">
        {t("card.footerNote")}
      </p>
    </div>
  );
}
