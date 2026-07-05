"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { LogOut, UserRound } from "lucide-react";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { LocaleSwitcher } from "@/components/verifolio/locale-switcher";
import { VerifolioWordmark } from "@/components/verifolio/wordmark";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";
import { useSession } from "@/lib/use-session";
import { cn } from "@/lib/utils";

const NAV = [
  { href: "/dashboard", key: "dashboard" },
  { href: "/requests", key: "requests" },
  { href: "/documents", key: "documents" },
  { href: "/contacts", key: "contacts" },
] as const;

function initials(email?: string): string {
  if (!email) return "•";
  return email.slice(0, 2).toUpperCase();
}

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
    <div className="flex min-h-screen flex-1 flex-col bg-white">
      <header className="flex h-[60px] items-center gap-6 border-b border-border-light bg-white px-8">
        <Link href="/dashboard">
          <VerifolioWordmark />
        </Link>
        <nav className="flex gap-1 text-sm">
          {NAV.map(({ href, key }) => (
            <Link
              key={href}
              href={href}
              className={cn(
                "rounded-[9px] px-3.5 py-2 font-semibold transition-colors",
                pathname.startsWith(href)
                  ? "bg-border-soft font-bold text-ink"
                  : "text-muted-text hover:text-ink",
              )}
            >
              {t(`nav.${key}`)}
            </Link>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-3">
          <LocaleSwitcher />
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                aria-label={t("nav.profile")}
                className="flex size-8 items-center justify-center rounded-full bg-ink text-xs font-extrabold text-paper"
              >
                {initials(session?.email)}
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              {session?.email && (
                <p className="truncate px-2 py-1.5 text-xs text-muted-text">
                  {session.email}
                </p>
              )}
              <DropdownMenuItem onSelect={() => router.push("/profile")}>
                <UserRound />
                {t("nav.profile")}
              </DropdownMenuItem>
              <DropdownMenuItem onSelect={() => logout.mutate()}>
                <LogOut />
                {t("nav.logout")}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>
      <main className="mx-auto w-full max-w-[1100px] flex-1 px-8 py-8">
        {children}
      </main>
    </div>
  );
}
