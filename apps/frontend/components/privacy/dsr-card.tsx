"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useFormatter, useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { useCursorList } from "@/lib/hooks/use-cursor-list";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";
import { unwrap } from "@/lib/query-provider";

type Dsr = components["schemas"]["DataSubjectRequestResponse"];
type DsrType = NonNullable<
  components["schemas"]["CreateDataSubjectRequestRequest"]["type"]
>;

// Owner-initiated types only. Consent withdrawal for recommenders is the emailed
// channel (/data-requests), not this form.
const OWNER_TYPES = ["DELETION", "EXPORT", "CORRECTION"] as const;

const STATUS_VARIANT: Record<
  string,
  "neutral" | "info" | "verified" | "failed" | "pending"
> = {
  RECEIVED: "info",
  IN_REVIEW: "pending",
  APPROVED: "info",
  EXECUTED: "verified",
  REJECTED: "failed",
};

const DSR_QUERY_KEY = ["data-subject-requests"] as const;

export function DsrCard() {
  const t = useTranslations();
  const format = useFormatter();
  const queryClient = useQueryClient();
  const [type, setType] = useState<DsrType>("DELETION");
  const [comment, setComment] = useState("");

  const list = useCursorList<Dsr>(DSR_QUERY_KEY, async (cursor) =>
    unwrap(
      await api.GET("/api/v1/privacy/data-subject-requests", {
        params: { query: cursor ? { cursor } : {} },
      }),
    ),
  );

  const submit = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST("/api/v1/privacy/data-subject-requests", {
          body: { type, comment: comment.trim() || null },
        }),
      ),
    onSuccess: () => {
      toast.success(t("privacy.dsr.submitted"));
      setComment("");
      queryClient.invalidateQueries({ queryKey: DSR_QUERY_KEY });
    },
  });

  return (
    <Card className="max-w-xl">
      <CardHeader>
        <CardTitle>{t("privacy.dsr.title")}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <form
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            submit.mutate();
          }}
        >
          <div className="flex flex-col gap-2">
            <Label>{t("privacy.dsr.typeLabel")}</Label>
            <Select
              value={type}
              onValueChange={(v) => setType(v as DsrType)}
            >
              <SelectTrigger
                className="w-full"
                aria-label={t("privacy.dsr.typeLabel")}
              >
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {OWNER_TYPES.map((tp) => (
                  <SelectItem key={tp} value={tp}>
                    {t(`privacy.dsr.types.${tp}`)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="dsr-comment">{t("privacy.dsr.commentLabel")}</Label>
            <Textarea
              id="dsr-comment"
              value={comment}
              rows={3}
              onChange={(e) => setComment(e.target.value)}
            />
          </div>
          <p className="text-xs text-muted-text">
            {t("privacy.dsr.recommenderNote")}
          </p>
          <Button
            type="submit"
            disabled={submit.isPending}
            className="self-start"
          >
            {t("privacy.dsr.submit")}
          </Button>
        </form>

        <div className="flex flex-col gap-3 border-t border-border-light pt-4">
          <h3 className="text-sm font-bold text-ink">
            {t("privacy.dsr.listTitle")}
          </h3>
          {list.isLoading ? (
            <Skeleton className="h-16 w-full" />
          ) : list.items.length === 0 ? (
            <p className="text-sm text-muted-text">{t("privacy.dsr.empty")}</p>
          ) : (
            <ul className="flex flex-col gap-2">
              {list.items.map((dsr) => (
                <li
                  key={dsr.id}
                  className="flex items-center justify-between gap-3 rounded-card border border-border-light p-3"
                >
                  <div className="flex flex-col">
                    <span className="text-sm font-semibold text-ink">
                      {t(`privacy.dsr.types.${dsr.type}`)}
                    </span>
                    {dsr.dueAt && (
                      <span className="text-xs text-muted-text">
                        {t("privacy.dsr.dueBy", {
                          date: format.dateTime(new Date(dsr.dueAt), {
                            dateStyle: "medium",
                          }),
                        })}
                      </span>
                    )}
                  </div>
                  <Badge variant={STATUS_VARIANT[dsr.status ?? ""] ?? "neutral"}>
                    {t(`privacy.dsr.status.${dsr.status}`)}
                  </Badge>
                </li>
              ))}
            </ul>
          )}
          {list.hasNext && (
            <Button
              variant="ghost"
              size="sm"
              className="self-start"
              disabled={list.isLoadingMore}
              onClick={() => list.loadMore()}
            >
              {t("common.loadMore")}
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
