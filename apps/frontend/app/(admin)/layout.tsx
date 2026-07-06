import Link from "next/link";
import { getTranslations } from "next-intl/server";

import { AdminNav } from "@/components/admin/admin-nav";
import { VerifolioWordmark } from "@/components/verifolio/wordmark";

/**
 * The admin console shell (design 5a): a dark ink/navy chrome, deliberately
 * distinct from the light user app shell. Auth pages under this group are
 * unauthenticated, so the shell stays presentational — authenticated pages guard
 * themselves via `useAdminSession` (/admin/me).
 */
export default async function AdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const t = await getTranslations("admin");
  return (
    <div className="flex min-h-screen flex-1 flex-col bg-ink text-paper">
      <header className="flex h-[60px] items-center gap-3 border-b border-navy bg-navy px-8">
        <Link href="/admin" className="flex items-center gap-2">
          <VerifolioWordmark dark />
          <span className="rounded-full border border-blue-gray/40 px-2 py-0.5 text-xs font-semibold uppercase tracking-wide text-blue-gray">
            {t("shell.badge")}
          </span>
        </Link>
        <AdminNav />
      </header>
      <main className="mx-auto w-full max-w-[1100px] flex-1 px-8 py-10">
        {children}
      </main>
    </div>
  );
}
