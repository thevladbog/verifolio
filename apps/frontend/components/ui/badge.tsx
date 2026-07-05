import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex h-6 items-center gap-1 rounded-full px-2.5 text-xs font-medium [&_svg]:size-3.5",
  {
    variants: {
      variant: {
        neutral: "bg-blue-gray-light/50 text-slate-text",
        verified: "bg-verified-green/10 text-verified-green",
        signed: "bg-muted-gold/15 text-muted-gold",
        locked: "bg-ink/8 text-ink",
        pending: "bg-blue-gray-light/50 text-slate-text",
        failed: "bg-danger/10 text-danger",
        expired: "bg-warning/10 text-warning",
        info: "bg-trust-blue/10 text-trust-blue",
      },
    },
    defaultVariants: { variant: "neutral" },
  },
);

function Badge({
  className,
  variant,
  ...props
}: React.ComponentProps<"span"> & VariantProps<typeof badgeVariants>) {
  return (
    <span
      data-slot="badge"
      className={cn(badgeVariants({ variant }), className)}
      {...props}
    />
  );
}

export { Badge, badgeVariants };
