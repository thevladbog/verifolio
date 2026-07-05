"use client";

import { useLocale } from "next-intl";
import { useRouter } from "next/navigation";

import { cn } from "@/lib/utils";

const LOCALES = ["en", "ru"] as const;

function persistLocale(next: string) {
  document.cookie = `NEXT_LOCALE=${next}; path=/; max-age=31536000; samesite=lax`;
}

function LocaleSwitcher({ className }: { className?: string }) {
  const locale = useLocale();
  const router = useRouter();

  const switchTo = (next: string) => {
    persistLocale(next);
    router.refresh();
  };

  return (
    <div className={cn("inline-flex gap-1", className)} role="group">
      {LOCALES.map((l) => (
        <button
          key={l}
          type="button"
          onClick={() => switchTo(l)}
          aria-pressed={locale === l}
          className={cn(
            "rounded-control px-2 py-1 text-xs font-medium uppercase transition-colors",
            locale === l
              ? "bg-blue-gray-light/50 text-ink"
              : "text-muted-text hover:text-ink",
          )}
        >
          {l}
        </button>
      ))}
    </div>
  );
}

export { LocaleSwitcher };
