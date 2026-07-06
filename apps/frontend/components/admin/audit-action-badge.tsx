import { cn } from "@/lib/utils";

/**
 * A neutral monospace pill for an audit `action` or `entityType` code
 * (design 11b). Codes are stable machine identifiers, so they render verbatim
 * rather than being translated.
 */
export function AuditCodeBadge({
  code,
  variant = "default",
}: {
  code: string;
  variant?: "default" | "muted";
}) {
  return (
    <span
      className={cn(
        "inline-flex h-5 items-center rounded border px-1.5 font-mono text-[11px]",
        variant === "muted"
          ? "border-navy bg-navy/40 text-blue-gray"
          : "border-trust-blue/40 bg-trust-blue/15 text-trust-blue",
      )}
    >
      {code}
    </span>
  );
}
