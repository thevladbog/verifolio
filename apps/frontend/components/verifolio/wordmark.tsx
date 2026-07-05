import { cn } from "@/lib/utils";

/** V-folder mark + wordmark, built from simple folded-paper geometry. */
function VerifolioWordmark({
  className,
  dark = false,
}: {
  className?: string;
  dark?: boolean;
}) {
  return (
    <span className={cn("inline-flex items-center gap-2", className)}>
      <svg
        viewBox="0 0 24 24"
        aria-hidden
        className="size-7"
        fill="none"
      >
        <path
          d="M3 4.5c0-.8.9-1.4 1.7-1L12 7l7.3-3.5c.8-.4 1.7.2 1.7 1v2.1c0 .5-.3 1-.7 1.2L12 12 3.7 7.8A1.4 1.4 0 0 1 3 6.6V4.5Z"
          fill={dark ? "#F7F4EC" : "#0F1B2E"}
        />
        <path
          d="M5 10.4 12 14l7-3.6v3.1c0 .5-.3 1-.7 1.2L12 18l-6.3-3.3a1.4 1.4 0 0 1-.7-1.2v-3.1Z"
          fill={dark ? "#A5B4C4" : "#A5B4C4"}
        />
        <path d="M9.5 17.4l2.5 1.3 2.5-1.3-2.5 4.1-2.5-4.1Z" fill="#2EAD72" />
      </svg>
      <span
        className={cn(
          "text-lg font-semibold tracking-tight",
          dark ? "text-paper" : "text-ink",
        )}
      >
        Verifolio
      </span>
    </span>
  );
}

export { VerifolioWordmark };
