"use client";

import { AlertTriangle } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

import { DsrStatusBadge } from "@/components/admin/dsr-status-badge";
import { dsrTypeLabel } from "@/components/admin/dsr-type";
import { Button } from "@/components/ui/button";
import type { components } from "@/lib/api/schema";

type DsrDetail = components["schemas"]["AdminDsrDetailResponse"];

function Field({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs uppercase tracking-wide text-blue-gray">{label}</dt>
      <dd className="text-sm text-paper">{value}</dd>
    </div>
  );
}

/**
 * The DSR detail panel (design 5d) with role-gated decision actions. The parent
 * owns the mutations; this component only renders and dispatches callbacks.
 * `executionNotAutomated` renders the calm "manual execution required" state
 * instead of a scary error when the backend returns 409 EXECUTION_NOT_AUTOMATED.
 */
export function DsrDetail({
  detail,
  isLoading,
  canDecide,
  canExecute,
  actionPending,
  executionNotAutomated,
  onApprove,
  onReject,
  onExecute,
}: {
  detail: DsrDetail | undefined;
  isLoading: boolean;
  canDecide: boolean;
  canExecute: boolean;
  actionPending: boolean;
  executionNotAutomated: boolean;
  onApprove: () => void;
  onReject: () => void;
  onExecute: () => void;
}) {
  const t = useTranslations("admin");
  const format = useFormatter();

  if (isLoading) {
    return <p className="text-sm text-blue-gray">{t("common.loading")}</p>;
  }
  if (!detail) {
    return <p className="text-sm text-blue-gray">{t("queue.selectHint")}</p>;
  }

  const dateFmt = (iso?: string) =>
    iso
      ? format.dateTime(new Date(iso), {
          dateStyle: "medium",
          timeStyle: "short",
        })
      : "—";

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-lg font-semibold text-paper">
          {dsrTypeLabel(detail.type, t)}
        </h2>
        {detail.status && <DsrStatusBadge status={detail.status} />}
      </div>

      <dl className="grid grid-cols-2 gap-4">
        <Field label={t("queue.subject")} value={detail.subjectEmail ?? "—"} />
        <Field label={t("queue.region")} value={detail.region ?? "—"} />
        <Field label={t("queue.due")} value={dateFmt(detail.dueAt)} />
        <Field label={t("queue.created")} value={dateFmt(detail.createdAt)} />
        <Field
          label={t("queue.verified")}
          value={detail.verifiedAt ? dateFmt(detail.verifiedAt) : t("queue.notVerified")}
        />
        {detail.resolutionNotes && (
          <Field label={t("queue.notesLabel")} value={detail.resolutionNotes} />
        )}
      </dl>

      {executionNotAutomated && (
        <div
          role="status"
          className="flex items-start gap-2 rounded-card border border-warning/40 bg-warning/10 p-3 text-sm text-warning"
        >
          <AlertTriangle aria-hidden className="mt-0.5 size-4 shrink-0" />
          <div>
            <p className="font-medium">{t("execNotAutomated.title")}</p>
            <p className="text-warning/80">{t("execNotAutomated.body")}</p>
          </div>
        </div>
      )}

      {(canDecide || canExecute) && (
        <div className="flex flex-wrap gap-2 border-t border-navy pt-4">
          {canDecide && (
            <>
              <Button
                variant="success"
                onClick={onApprove}
                disabled={actionPending}
              >
                {t("actions.approve")}
              </Button>
              <Button
                variant="danger"
                onClick={onReject}
                disabled={actionPending}
              >
                {t("actions.reject")}
              </Button>
            </>
          )}
          {canExecute && (
            <Button
              variant="secondary"
              onClick={onExecute}
              disabled={actionPending}
            >
              {t("actions.execute")}
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
