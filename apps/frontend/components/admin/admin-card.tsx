import { cn } from "@/lib/utils";

/** A dark-shell card (design 5a) for the admin auth flow — the light `ui/card` reads wrong on ink. */
export function AdminCard({
  title,
  description,
  className,
  children,
}: {
  title: string;
  description?: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div
      className={cn(
        "w-full max-w-md rounded-card border border-navy bg-navy/60 p-6 shadow-card",
        className,
      )}
    >
      <h1 className="text-xl font-semibold text-paper">{title}</h1>
      {description && (
        <p className="mt-1.5 text-sm text-blue-gray">{description}</p>
      )}
      <div className="mt-5">{children}</div>
    </div>
  );
}
