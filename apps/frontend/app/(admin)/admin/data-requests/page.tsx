"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { useState } from "react";
import { toast } from "sonner";

import { DsrDetail } from "@/components/admin/dsr-detail";
import { DsrRejectDialog } from "@/components/admin/dsr-reject-dialog";
import { DsrRow } from "@/components/admin/dsr-row";
import { DsrStatusFilter } from "@/components/admin/dsr-status-filter";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api/client";
import { errorMessage } from "@/lib/api/errors";
import { canDecide, canExecute } from "@/lib/admin/permissions";
import { useAdminSession } from "@/lib/admin/use-admin-session";
import { useCursorList } from "@/lib/hooks/use-cursor-list";
import { RequestError, unwrap } from "@/lib/query-provider";
import type { components } from "@/lib/api/schema";

type DsrItem = components["schemas"]["AdminDsrItemResponse"];

export default function AdminDataRequestsPage() {
  const t = useTranslations("admin");
  const queryClient = useQueryClient();
  const { admin, isLoading: sessionLoading } = useAdminSession();

  const [status, setStatus] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [execNotAutomated, setExecNotAutomated] = useState(false);

  const decide = canDecide(admin?.role);
  const execute = canExecute(admin?.role);

  const list = useCursorList<DsrItem>(
    ["admin-dsr-list", status],
    async (cursor) =>
      unwrap(
        await api.GET("/api/v1/admin/data-subject-requests", {
          params: { query: { status: status ?? undefined, cursor } },
        }),
      ),
  );

  const detail = useQuery({
    queryKey: ["admin-dsr", selectedId],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/v1/admin/data-subject-requests/{id}", {
          params: { path: { id: selectedId as string } },
        }),
      ),
    enabled: !!selectedId,
  });

  const refetchAll = () => {
    queryClient.invalidateQueries({ queryKey: ["admin-dsr-list"] });
    queryClient.invalidateQueries({ queryKey: ["admin-dsr", selectedId] });
    queryClient.invalidateQueries({ queryKey: ["admin-dashboard"] });
  };

  const approve = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST("/api/v1/admin/data-subject-requests/{id}/approve", {
          params: { path: { id: selectedId as string } },
        }),
      ),
    onSuccess: refetchAll,
  });

  const reject = useMutation({
    mutationFn: async (notes: string) =>
      unwrap(
        await api.POST("/api/v1/admin/data-subject-requests/{id}/reject", {
          params: { path: { id: selectedId as string } },
          body: { notes },
        }),
      ),
    onSuccess: () => {
      setRejectOpen(false);
      refetchAll();
    },
  });

  const executeMutation = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST("/api/v1/admin/data-subject-requests/{id}/execute", {
          params: { path: { id: selectedId as string } },
        }),
      ),
    onSuccess: refetchAll,
    onError: (error) => {
      if (
        error instanceof RequestError &&
        error.body?.code === "EXECUTION_NOT_AUTOMATED"
      ) {
        // Not a failure the admin caused — surface the calm "manual required" state.
        setExecNotAutomated(true);
        return;
      }
      toast.error(
        error instanceof RequestError
          ? errorMessage(error.body, t)
          : t("errors.UNKNOWN", { code: "NETWORK" }),
      );
    },
    meta: { inlineError: true },
  });

  const actionPending =
    approve.isPending || reject.isPending || executeMutation.isPending;

  const select = (id: string) => {
    setSelectedId(id);
    setExecNotAutomated(false);
  };

  if (sessionLoading || !admin) {
    return <p className="text-sm text-blue-gray">{t("common.loading")}</p>;
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <Link
          href="/admin"
          className="inline-flex items-center gap-1 text-sm text-blue-gray hover:text-paper"
        >
          <ArrowLeft aria-hidden className="size-4" />
          {t("queue.backToDashboard")}
        </Link>
        <h1 className="text-2xl font-semibold text-paper">{t("queue.title")}</h1>
      </div>

      <DsrStatusFilter value={status} onChange={setStatus} />

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
        <div className="overflow-hidden rounded-card border border-navy bg-navy/30">
          {list.isLoading ? (
            <p className="p-4 text-sm text-blue-gray">{t("common.loading")}</p>
          ) : list.items.length === 0 ? (
            <p className="p-4 text-sm text-blue-gray">{t("queue.empty")}</p>
          ) : (
            <ul>
              {list.items.map((item) => (
                <li key={item.id}>
                  <DsrRow
                    item={item}
                    selected={item.id === selectedId}
                    onSelect={() => select(item.id as string)}
                  />
                </li>
              ))}
            </ul>
          )}
          {list.hasNext && (
            <div className="p-3">
              <Button
                variant="ghost"
                size="sm"
                className="w-full text-blue-gray hover:text-paper"
                onClick={() => list.loadMore()}
                disabled={list.isLoadingMore}
              >
                {t("queue.loadMore")}
              </Button>
            </div>
          )}
        </div>

        <div className="rounded-card border border-navy bg-navy/30 p-6">
          {selectedId ? (
            <DsrDetail
              detail={detail.data}
              isLoading={detail.isLoading}
              canDecide={decide}
              canExecute={execute}
              actionPending={actionPending}
              executionNotAutomated={execNotAutomated}
              onApprove={() => approve.mutate()}
              onReject={() => setRejectOpen(true)}
              onExecute={() => executeMutation.mutate()}
            />
          ) : (
            <p className="text-sm text-blue-gray">{t("queue.selectHint")}</p>
          )}
        </div>
      </div>

      <DsrRejectDialog
        open={rejectOpen}
        pending={reject.isPending}
        onOpenChange={setRejectOpen}
        onConfirm={(notes) => reject.mutate(notes)}
      />
    </div>
  );
}
