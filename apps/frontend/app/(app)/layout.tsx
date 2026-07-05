"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  FileText,
  LayoutDashboard,
  LogOut,
  Send,
  UserRound,
  Users,
} from "lucide-react";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";

import { LocaleSwitcher } from "@/components/verifolio/locale-switcher";
import { VerifolioWordmark } from "@/components/verifolio/wordmark";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";
import { useSession } from "@/lib/use-session";
import { cn } from "@/lib/utils";

const NAV = [
  { href: "/dashboard", key: "dashboard", icon: LayoutDashboard },
  { href: "/requests", key: "requests", icon: Send },
  { href: "/contacts", key: "contacts", icon: Users },
  { href: "/documents", key: "documents", icon: FileText },
  { href: "/profile", key: "profile", icon: UserRound },
] as const;

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const t = useTranslations();
  const pathname = usePathname();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { session } = useSession();

  const logout = useMutation({
    mutationFn: async () =>
      unwrap(await api.DELETE("/api/v1/auth/sessions/current")),
    onSuccess: () => {
      queryClient.clear();
      router.replace("/login");
    },
  });

  return (
    <div className="flex min-h-screen flex-1">
      <aside className="flex w-60 shrink-0 flex-col bg-ink px-4 py-6">
        <Link href="/dashboard" className="px-2">
          <VerifolioWordmark dark />
        </Link>
        <nav className="mt-8 flex flex-1 flex-col gap-1">
          {NAV.map(({ href, key, icon: Icon }) => (
            <Link
              key={href}
              href={href}
              className={cn(
                "flex items-center gap-3 rounded-control px-3 py-2 text-sm font-medium transition-colors",
                pathname.startsWith(href)
                  ? "bg-white/10 text-paper"
                  : "text-blue-gray hover:bg-white/5 hover:text-paper",
              )}
            >
              <Icon className="size-4" />
              {t(`nav.${key}`)}
            </Link>
          ))}
        </nav>
        <div className="flex flex-col gap-3 px-2">
          {session?.email && (
            <p className="truncate text-xs text-blue-gray">{session.email}</p>
          )}
          <button
            type="button"
            onClick={() => logout.mutate()}
            className="flex items-center gap-3 rounded-control px-1 py-2 text-sm font-medium text-blue-gray transition-colors hover:text-paper"
          >
            <LogOut className="size-4" />
            {t("nav.logout")}
          </button>
        </div>
      </aside>
      <div className="flex flex-1 flex-col">
        <header className="flex items-center justify-end border-b border-border-light bg-white px-8 py-3">
          <LocaleSwitcher />
        </header>
        <main className="flex-1 px-8 py-8">{children}</main>
      </div>
    </div>
  );
}
