"use client";

import { ArrowLeft, Search } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";
import Link from "next/link";
import { useEffect, useState } from "react";

import { AdminError } from "@/components/admin/admin-error";
import { UserStatusBadge } from "@/components/admin/user-status-badge";
import { UserStatusFilter } from "@/components/admin/user-status-filter";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api/client";
import { useAdminSession } from "@/lib/admin/use-admin-session";
import { useCursorList } from "@/lib/hooks/use-cursor-list";
import { unwrap } from "@/lib/query-provider";
import type { components } from "@/lib/api/schema";

type UserItem = components["schemas"]["AdminUserListItemResponse"];

export default function AdminUsersPage() {
  const t = useTranslations("admin");
  const format = useFormatter();
  const {
    admin,
    isLoading: sessionLoading,
    isError: sessionError,
    refetch: refetchSession,
  } = useAdminSession();

  const [status, setStatus] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");

  // Debounce so a keystroke doesn't fire a request per character.
  useEffect(() => {
    const handle = setTimeout(() => setDebouncedSearch(search.trim()), 300);
    return () => clearTimeout(handle);
  }, [search]);

  const list = useCursorList<UserItem>(
    ["admin-user-list", status, debouncedSearch],
    async (cursor) =>
      unwrap(
        await api.GET("/api/v1/admin/users", {
          params: {
            query: {
              query: debouncedSearch || undefined,
              status: status ?? undefined,
              cursor,
            },
          },
        }),
      ),
  );

  if (sessionError) {
    return <AdminError onRetry={() => refetchSession()} />;
  }

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
        <h1 className="text-2xl font-semibold text-paper">{t("users.title")}</h1>
        <p className="text-sm text-blue-gray">{t("users.subtitle")}</p>
      </div>

      <div className="flex flex-col gap-3">
        <label className="relative block">
          <span className="sr-only">{t("users.searchLabel")}</span>
          <Search
            aria-hidden
            className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-blue-gray"
          />
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder={t("users.searchPlaceholder")}
            className="h-10 w-full rounded-card border border-navy bg-navy/40 pl-9 pr-3 text-sm text-paper placeholder:text-blue-gray/70 focus:border-blue-gray/60 focus:outline-none"
          />
        </label>
        <UserStatusFilter value={status} onChange={setStatus} />
      </div>

      <div className="overflow-hidden rounded-card border border-navy bg-navy/30">
        <div className="hidden grid-cols-[minmax(0,2fr)_minmax(0,1.5fr)_auto_auto] gap-4 border-b border-navy px-4 py-2.5 text-xs font-medium uppercase tracking-wide text-blue-gray sm:grid">
          <span>{t("users.colUser")}</span>
          <span>{t("users.colName")}</span>
          <span>{t("users.colRegion")}</span>
          <span>{t("users.colStatus")}</span>
        </div>
        {list.isLoading ? (
          <div className="flex flex-col gap-2 p-4">
            {[0, 1, 2].map((i) => (
              <div
                key={i}
                className="h-10 animate-pulse rounded-card bg-navy/60"
              />
            ))}
          </div>
        ) : list.items.length === 0 ? (
          <p className="p-4 text-sm text-blue-gray">{t("users.empty")}</p>
        ) : (
          <ul>
            {list.items.map((user) => (
              <li key={user.id}>
                <Link
                  href={`/admin/users/${user.id}`}
                  className="grid grid-cols-[minmax(0,1fr)_auto] gap-x-4 gap-y-1 border-b border-navy px-4 py-3 transition-colors hover:bg-navy/40 sm:grid-cols-[minmax(0,2fr)_minmax(0,1.5fr)_auto_auto] sm:items-center"
                >
                  <span className="truncate text-sm font-medium text-paper">
                    {user.email}
                  </span>
                  <span className="truncate text-sm text-blue-gray sm:order-none sm:col-auto">
                    {user.displayName ?? "—"}
                  </span>
                  <span className="text-xs text-blue-gray">{user.region}</span>
                  <span className="flex items-center gap-2 justify-self-start sm:justify-self-auto">
                    {user.status && <UserStatusBadge status={user.status} />}
                    {user.createdAt && (
                      <span className="text-xs text-blue-gray">
                        {format.dateTime(new Date(user.createdAt), {
                          dateStyle: "medium",
                        })}
                      </span>
                    )}
                  </span>
                </Link>
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
              {t("users.loadMore")}
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}
