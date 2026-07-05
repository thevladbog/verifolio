"use client";

import { AlertTriangle } from "lucide-react";
import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";

/**
 * Shared admin error+retry block. Rendered instead of an infinite spinner when
 * an admin query fails for a non-401 reason (session load, dashboard count, …).
 */
export function AdminError({
  message,
  onRetry,
}: {
  message?: string;
  onRetry?: () => void;
}) {
  const t = useTranslations("admin.error");
  return (
    <div
      role="alert"
      className="flex flex-col items-start gap-3 rounded-card border border-danger/40 bg-danger/10 p-4 text-sm text-danger"
    >
      <div className="flex items-start gap-2">
        <AlertTriangle aria-hidden className="mt-0.5 size-4 shrink-0" />
        <p>{message ?? t("title")}</p>
      </div>
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry}>
          {t("retry")}
        </Button>
      )}
    </div>
  );
}
