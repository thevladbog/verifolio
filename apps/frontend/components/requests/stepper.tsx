import { Check } from "lucide-react";

import { cn } from "@/lib/utils";

/** Wizard progress rail per design canvas 6a/7b (numbered pills, ✓ done). */
export function Stepper({
  steps,
  current,
}: {
  steps: string[];
  current: number;
}) {
  return (
    <ol className="flex flex-wrap items-center gap-2">
      {steps.map((label, index) => {
        const done = index < current;
        const active = index === current;
        return (
          <li key={label} className="flex items-center gap-2">
            <span
              className={cn(
                "flex size-6 items-center justify-center rounded-full text-xs font-extrabold",
                done && "bg-verified-green text-white",
                active && "bg-ink text-white",
                !done && !active && "bg-border-soft text-muted-text",
              )}
              aria-hidden
            >
              {done ? <Check className="size-3.5" /> : index + 1}
            </span>
            <span
              className={cn(
                "text-sm font-semibold",
                active ? "text-ink" : "text-muted-text",
              )}
              aria-current={active ? "step" : undefined}
            >
              {label}
            </span>
            {index < steps.length - 1 && (
              <span className="mx-1 h-px w-6 bg-border-light" aria-hidden />
            )}
          </li>
        );
      })}
    </ol>
  );
}
