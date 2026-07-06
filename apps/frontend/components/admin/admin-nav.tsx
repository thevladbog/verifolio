"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { usePathname } from "next/navigation";

import { api } from "@/lib/api/client";
import { canViewAudit, canViewUsers } from "@/lib/admin/permissions";
import { unwrap } from "@/lib/query-provider";
import { cn } from "@/lib/utils";

/**
 * Role-gated admin shell navigation (spec §Frontend). Reads the session from
 * `/admin/me` (the same cached query as `useAdminSession`, so no extra request)
 * and shows a link only when the admin holds the backing permission — the
 * server still enforces 403, this just avoids offering a dead-end.
 *
 * It deliberately does NOT redirect on 401: on the unauthenticated admin auth
 * pages (login / MFA) it simply renders nothing, so the shared shell stays safe.
 */
export function AdminNav() {
  const t = useTranslations("admin.nav");
  const pathname = usePathname();
  const { data: admin } = useQuery({
    queryKey: ["admin-session"],
    queryFn: async () => unwrap(await api.GET("/api/v1/admin/me")),
    retry: false,
    staleTime: 5 * 60_000,
  });

  if (!admin) return null;

  const links = [
    { href: "/admin/data-requests", label: t("dataRequests"), show: true },
    { href: "/admin/users", label: t("users"), show: canViewUsers(admin.role) },
    { href: "/admin/audit", label: t("audit"), show: canViewAudit(admin.role) },
  ].filter((link) => link.show);

  if (links.length === 0) return null;

  return (
    <nav aria-label={t("label")} className="ml-4 flex items-center gap-1">
      {links.map((link) => {
        const active =
          pathname === link.href || pathname.startsWith(`${link.href}/`);
        return (
          <Link
            key={link.href}
            href={link.href}
            aria-current={active ? "page" : undefined}
            className={cn(
              "rounded-full px-3 py-1.5 text-sm font-medium transition-colors",
              active
                ? "bg-paper/10 text-paper"
                : "text-blue-gray hover:text-paper",
            )}
          >
            {link.label}
          </Link>
        );
      })}
    </nav>
  );
}
