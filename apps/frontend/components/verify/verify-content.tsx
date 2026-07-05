"use client";

import { useFormatter, useTranslations } from "next-intl";
import Link from "next/link";

import { BadgeStatus } from "@/components/verifolio/badge-status";
import { VerifolioWordmark } from "@/components/verifolio/wordmark";
import { publicBadgeVariant } from "@/components/verify/badge-variant";
import { DownloadsPanel } from "@/components/verify/downloads-panel";
import type { components } from "@/lib/api/schema";

type PageData = components["schemas"]["VerificationPageResponse"];

export function VerifyContent({
  page,
  token,
}: {
  page: PageData;
  token: string;
}) {
  const t = useTranslations();
  const format = useFormatter();

  // Tombstoned: content erased at the data subject's request. Header + notice
  // only — no signals, downloads, persons, or version details.
  if (page.status === "TOMBSTONED") {
    return (
      <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-8 px-4 py-10">
        <header className="flex items-center justify-between">
          <VerifolioWordmark />
        </header>
        <section
          className="rounded-card border border-border-light bg-white p-6 shadow-card"
          role="status"
        >
          <h1 className="text-2xl font-extrabold text-ink">
            {t("verify.removedTitle")}
          </h1>
          <p className="mt-3 text-sm text-slate-text">
            {page.notice ?? t("verify.removedBody")}
          </p>
        </section>
        <footer className="border-t border-border-light pt-6 text-xs text-muted-text">
          <p>
            {t("verify.dataRequestsPrompt")}{" "}
            <Link href="/data-requests" className="text-trust-blue hover:underline">
              {t("verify.dataRequestsLink")}
            </Link>
          </p>
        </footer>
      </main>
    );
  }

  const trustEntries = Object.entries(page.trustSummary ?? {});
  const retractedAt = page.version?.retractedAt;

  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-8 px-4 py-10">
      <header className="flex items-center justify-between">
        <VerifolioWordmark />
        <span className="font-mono text-xs text-muted-text">
          {page.header?.verificationId}
        </span>
      </header>

      {retractedAt && (
        <div
          className="rounded-card border border-danger/30 bg-danger/5 p-4 text-sm font-semibold text-danger"
          role="alert"
        >
          {t("verify.retractedBanner", {
            date: format.dateTime(new Date(retractedAt), {
              dateStyle: "long",
            }),
          })}
        </div>
      )}

      <section className="rounded-card border border-border-light bg-white p-6 shadow-card">
        <h1 className="text-2xl font-extrabold text-ink">
          {t("verify.title")}
        </h1>
        <p className="mt-1 text-sm text-muted-text">
          {page.header?.documentType}
        </p>

        <dl className="mt-6 grid gap-4 sm:grid-cols-2">
          {page.recipient && (
            <div>
              <dt className="text-xs font-semibold uppercase tracking-wide text-muted-text">
                {t("verify.recipient")}
              </dt>
              <dd className="mt-1 font-bold text-ink">{page.recipient.name}</dd>
            </div>
          )}
          {page.recommender && (
            <div>
              <dt className="text-xs font-semibold uppercase tracking-wide text-muted-text">
                {t("verify.recommender")}
              </dt>
              <dd className="mt-1 font-bold text-ink">
                {page.recommender.name}
              </dd>
              {page.recommender.relationshipType && (
                <dd className="text-sm text-slate-text">
                  {page.recommender.relationshipType}
                </dd>
              )}
              <dd className="mt-0.5 text-xs text-muted-text">
                {t("verify.statedByRecommender")}
              </dd>
            </div>
          )}
        </dl>

        {page.version && (
          <p className="mt-6 border-t border-border-light pt-4 text-sm text-slate-text">
            {t("verify.versionLine", {
              version: page.version.versionNumber ?? 1,
              date: format.dateTime(new Date(page.version.lockedAt ?? ""), {
                dateStyle: "long",
              }),
            })}
            {page.version.supersededByNewerVersion && (
              <span className="mt-1 block text-warning">
                {t("verify.superseded")}
              </span>
            )}
          </p>
        )}
      </section>

      <section>
        <h2 className="mb-3 text-lg font-bold text-ink">
          {t("verify.badgesTitle")}
        </h2>
        <div className="flex flex-wrap gap-2">
          {page.badges?.map((badge) => (
            <BadgeStatus
              key={`${badge.signalType}-${badge.date}`}
              variant={publicBadgeVariant(badge.signalType, badge.status)}
              label={badge.title ?? badge.signalType ?? ""}
            />
          ))}
        </div>
      </section>

      {trustEntries.length > 0 && (
        <section>
          <h2 className="mb-3 text-lg font-bold text-ink">
            {t("verify.trustTitle")}
          </h2>
          {/* Counts per category only — never a single number or percentage. */}
          <dl className="grid gap-3 sm:grid-cols-3">
            {trustEntries.map(([category, count]) => (
              <div
                key={category}
                className="rounded-card border border-border-light bg-white p-4"
              >
                <dt className="text-xs font-semibold uppercase tracking-wide text-muted-text">
                  {category}
                </dt>
                <dd className="mt-1 text-sm font-bold text-ink">
                  {t("verify.confirmedCount", { count })}
                </dd>
              </div>
            ))}
          </dl>
        </section>
      )}

      {(page.downloads?.length ?? 0) > 0 && (
        <section>
          <h2 className="mb-3 text-lg font-bold text-ink">
            {t("verify.downloadsTitle")}
          </h2>
          <DownloadsPanel token={token} downloads={page.downloads ?? []} />
        </section>
      )}

      {(page.timeline?.length ?? 0) > 0 && (
        <section>
          <h2 className="mb-3 text-lg font-bold text-ink">
            {t("verify.timelineTitle")}
          </h2>
          <ol className="flex flex-col gap-2 border-l-2 border-border-light pl-4">
            {page.timeline?.map((entry, index) => (
              <li key={index} className="text-sm">
                <span className="font-semibold text-ink">{entry.event}</span>
                {entry.at && (
                  <span className="ml-2 text-xs text-muted-text">
                    {format.dateTime(new Date(entry.at), {
                      dateStyle: "medium",
                      timeStyle: "short",
                    })}
                  </span>
                )}
              </li>
            ))}
          </ol>
        </section>
      )}

      <footer className="flex flex-col gap-2 border-t border-border-light pt-6 text-xs text-muted-text">
        {page.disclaimer && <p>{page.disclaimer}</p>}
        {page.privacyNotice && <p>{page.privacyNotice}</p>}
        <p>
          {t("verify.dataRequestsPrompt")}{" "}
          <Link href="/data-requests" className="text-trust-blue hover:underline">
            {t("verify.dataRequestsLink")}
          </Link>
        </p>
      </footer>
    </main>
  );
}
