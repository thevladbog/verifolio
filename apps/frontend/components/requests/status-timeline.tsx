import { Check } from "lucide-react";
import { useTranslations } from "next-intl";

import { TIMELINE, TERMINAL_STATUSES } from "@/lib/requests/status";
import { cn } from "@/lib/utils";

/**
 * Happy-path progress rail; terminal side-exits (DECLINED/EXPIRED/CANCELLED)
 * are rendered as a banner by the parent, not on the rail.
 */
export function StatusTimeline({ status }: { status: string }) {
  const t = useTranslations();
  const currentIndex = TIMELINE.indexOf(status as (typeof TIMELINE)[number]);
  const isTerminalSideExit =
    TERMINAL_STATUSES.has(status) && status !== "COMPLETED";
  // CORRECTION_REQUESTED sits between review and a new response cycle.
  const effectiveIndex =
    status === "CORRECTION_REQUESTED" ? TIMELINE.indexOf("IN_PROGRESS") : currentIndex;

  if (isTerminalSideExit) return null;

  return (
    <ol className="flex flex-wrap items-center gap-1.5">
      {TIMELINE.map((step, index) => {
        const done = index < effectiveIndex;
        const active = index === effectiveIndex;
        return (
          <li key={step} className="flex items-center gap-1.5">
            <span
              className={cn(
                "flex size-5 items-center justify-center rounded-full text-[10px] font-extrabold",
                done && "bg-verified-green text-white",
                active && "bg-ink text-white",
                !done && !active && "bg-border-soft text-muted-text",
              )}
              aria-hidden
            >
              {done ? <Check className="size-3" /> : index + 1}
            </span>
            <span
              className={cn(
                "text-xs font-semibold",
                active ? "text-ink" : "text-muted-text",
              )}
              aria-current={active ? "step" : undefined}
            >
              {t(`requests.statuses.${step}`)}
            </span>
            {index < TIMELINE.length - 1 && (
              <span className="mx-0.5 h-px w-4 bg-border-light" aria-hidden />
            )}
          </li>
        );
      })}
    </ol>
  );
}
