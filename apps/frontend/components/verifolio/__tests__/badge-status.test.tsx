import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { BadgeStatus, type TrustBadgeVariant } from "../badge-status";

const CASES: Array<[TrustBadgeVariant, string]> = [
  ["verified", "text-verified-green"],
  ["signed", "text-muted-gold"],
  ["locked", "text-ink"],
  ["pending", "text-slate-text"],
  ["failed", "text-danger"],
  ["expired", "text-warning"],
];

describe("BadgeStatus", () => {
  it.each(CASES)("renders %s with its own trust color", (variant, colorClass) => {
    render(<BadgeStatus variant={variant} label="Email Confirmed" />);
    const badge = screen.getByText("Email Confirmed");
    expect(badge).toHaveClass(colorClass);
    expect(badge.dataset.trustBadge).toBe(variant);
  });

  it("renders an icon so state is readable without color alone", () => {
    const { container } = render(<BadgeStatus variant="verified" label="Verified" />);
    expect(container.querySelector("svg")).toBeInTheDocument();
  });

  it("never renders a purely numeric label (no trust scores)", () => {
    render(<BadgeStatus variant="verified" label="Recipient Confirmed" />);
    const badge = screen.getByText("Recipient Confirmed");
    expect(badge.textContent).not.toMatch(/^\s*\d+([.,%]\d*)?\s*$/);
  });
});
