import * as React from "react";

import { cn } from "@/lib/utils";

function Textarea({ className, ...props }: React.ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(
        "flex min-h-24 w-full rounded-control border border-border-light bg-white px-3 py-2 text-sm text-ink placeholder:text-muted-text focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-trust-blue disabled:cursor-not-allowed disabled:opacity-50",
        className,
      )}
      {...props}
    />
  );
}

export { Textarea };
