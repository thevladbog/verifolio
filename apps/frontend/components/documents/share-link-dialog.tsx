"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Copy } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";
import { cn } from "@/lib/utils";

const EXPIRY_OPTIONS = [
  { key: "days7", days: 7 },
  { key: "days30", days: 30 },
  { key: "never", days: null },
] as const;

export function ShareLinkDialog({
  documentId,
  open,
  onOpenChange,
}: {
  documentId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const t = useTranslations();
  const queryClient = useQueryClient();
  const [expiry, setExpiry] =
    useState<(typeof EXPIRY_OPTIONS)[number]>(EXPIRY_OPTIONS[1]);
  // The raw URL exists only in this state, only until the dialog closes.
  const [createdUrl, setCreatedUrl] = useState<string>();

  const create = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST("/api/v1/documents/{id}/share-links", {
          params: { path: { id: documentId } },
          body: { expiresInDays: expiry.days },
        }),
      ),
    onSuccess: (data) => {
      setCreatedUrl(data.url);
      queryClient.invalidateQueries({ queryKey: ["share-links", documentId] });
    },
  });

  const close = (next: boolean) => {
    if (!next) setCreatedUrl(undefined);
    onOpenChange(next);
  };

  const copy = async () => {
    if (!createdUrl) return;
    await navigator.clipboard.writeText(createdUrl);
    toast.success(t("common.copied"));
  };

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("share.title")}</DialogTitle>
          <DialogDescription>{t("share.subtitle")}</DialogDescription>
        </DialogHeader>

        {createdUrl ? (
          <div className="flex flex-col gap-3">
            <div className="rounded-card border border-verified-green/40 bg-verified-green/5 p-4">
              <p className="text-xs font-extrabold uppercase tracking-wide text-verified-green">
                {t("share.created")}
              </p>
              <p className="mt-1 break-all font-mono text-sm text-ink">
                {createdUrl}
              </p>
              <p className="mt-2 text-xs text-muted-text">
                {t("share.oneTimeNotice")}
              </p>
            </div>
            <DialogFooter>
              <Button onClick={copy}>
                <Copy />
                {t("common.copy")}
              </Button>
              <Button variant="secondary" onClick={() => close(false)}>
                {t("common.close")}
              </Button>
            </DialogFooter>
          </div>
        ) : (
          <div className="flex flex-col gap-4">
            <div>
              <p className="mb-2 text-sm font-semibold text-ink">
                {t("share.expiry")}
              </p>
              <div className="flex gap-2">
                {EXPIRY_OPTIONS.map((option) => (
                  <button
                    key={option.key}
                    type="button"
                    onClick={() => setExpiry(option)}
                    aria-pressed={expiry.key === option.key}
                    className={cn(
                      "rounded-full px-3.5 py-1.5 text-sm font-semibold transition-colors",
                      expiry.key === option.key
                        ? "bg-ink text-white"
                        : "bg-border-soft text-slate-text hover:text-ink",
                    )}
                  >
                    {t(`share.expiryOptions.${option.key}`)}
                  </button>
                ))}
              </div>
            </div>
            <p className="text-xs text-muted-text">{t("share.audienceNote")}</p>
            <DialogFooter>
              <Button
                variant="secondary"
                onClick={() => close(false)}
              >
                {t("common.cancel")}
              </Button>
              <Button
                onClick={() => create.mutate()}
                disabled={create.isPending}
              >
                {t("share.create")}
              </Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
