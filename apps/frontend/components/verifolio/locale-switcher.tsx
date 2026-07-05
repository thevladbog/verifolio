"use client";

import { useLocale } from "next-intl";
import { useRouter } from "next/navigation";

import { cn } from "@/lib/utils";

const LOCALES = ["ru", "en"] as const;

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
    <div
      className={cn(
        "inline-flex items-center gap-0.5 rounded-full bg-border-soft p-[3px] text-[11px] font-extrabold",
        className,
      )}
      role="group"
    >
      {LOCALES.map((l) => (
        <button
          key={l}
          type="button"
          onClick={() => switchTo(l)}
          aria-pressed={locale === l}
          className={cn(
            "rounded-full px-2.5 py-1 uppercase transition-colors",
            locale === l
              ? "bg-white text-ink shadow-[0_1px_2px_rgba(15,27,46,.12)]"
              : "text-blue-gray hover:text-ink",
          )}
        >
          {l}
        </button>
      ))}
    </div>
  );
}

export { LocaleSwitcher };
