"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

/** Reject a DSR with optional resolution notes (design 5d). */
export function DsrRejectDialog({
  open,
  pending,
  onOpenChange,
  onConfirm,
}: {
  open: boolean;
  pending: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: (notes: string) => void;
}) {
  const t = useTranslations("admin.queue");
  const [notes, setNotes] = useState("");

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("rejectTitle")}</DialogTitle>
          <DialogDescription>{t("rejectBody")}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-2">
          <Label htmlFor="reject-notes">{t("notesLabel")}</Label>
          <Textarea
            id="reject-notes"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder={t("notesPlaceholder")}
          />
        </div>
        <DialogFooter>
          <Button
            variant="secondary"
            onClick={() => onOpenChange(false)}
            disabled={pending}
          >
            {t("cancel")}
          </Button>
          <Button
            variant="danger"
            onClick={() => onConfirm(notes)}
            disabled={pending}
          >
            {t("confirmReject")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
