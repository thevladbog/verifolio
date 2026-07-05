import {
  BadgeCheck,
  Clock,
  Lock,
  PenLine,
  TriangleAlert,
  XCircle,
  type LucideIcon,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";

export type TrustBadgeVariant =
  | "verified"
  | "signed"
  | "locked"
  | "pending"
  | "failed"
  | "expired";

const ICONS: Record<TrustBadgeVariant, LucideIcon> = {
  verified: BadgeCheck,
  signed: PenLine,
  locked: Lock,
  pending: Clock,
  failed: XCircle,
  expired: TriangleAlert,
};

/**
 * Trust badge per DESIGN_SYSTEM.md badge rules: each trust type keeps its own
 * color, readable without color alone (icon + text). Never renders a numeric
 * score — the label is always text.
 */
function BadgeStatus({
  variant,
  label,
}: {
  variant: TrustBadgeVariant;
  label: string;
}) {
  const Icon = ICONS[variant];
  return (
    <Badge variant={variant} data-trust-badge={variant}>
      <Icon aria-hidden />
      {label}
    </Badge>
  );
}

export { BadgeStatus };
