import * as React from "react";

import { cn } from "@/lib/utils";

function Input({ className, type, ...props }: React.ComponentProps<"input">) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(
        "flex h-10 w-full rounded-control border border-border-light bg-white px-3 py-2 text-sm text-ink placeholder:text-muted-text focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-trust-blue disabled:cursor-not-allowed disabled:opacity-50",
        className,
      )}
      {...props}
    />
  );
}

export { Input };
