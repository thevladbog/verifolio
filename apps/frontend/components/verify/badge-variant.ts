import type { TrustBadgeVariant } from "@/components/verifolio/badge-status";

/** DESIGN_SYSTEM badge color rules: each trust type keeps its own tone. */
export function publicBadgeVariant(
  signalType: string | undefined,
  status: string | undefined,
): TrustBadgeVariant {
  if (status === "REVOKED") return "failed";
  if (status === "EXPIRED") return "expired";
  if (status !== "VERIFIED") return "pending";
  if (signalType?.startsWith("SIGNATURE")) return "signed";
  if (signalType === "VERSION_LOCKED" || signalType === "DOCUMENT_HASH_LOCKED")
    return "locked";
  return "verified";
}
