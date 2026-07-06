import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fail, ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  usePathname: () => "/admin",
}));

vi.mock("@/lib/api/client", () => ({
  api: { GET: vi.fn(), POST: vi.fn() },
}));

import { api } from "@/lib/api/client";
import { AdminNav } from "@/components/admin/admin-nav";

const mockGet = vi.mocked(api.GET);

function setRole(role: string | null) {
  if (role === null) {
    mockGet.mockResolvedValue(fail(401, "UNAUTHENTICATED") as never);
  } else {
    mockGet.mockResolvedValue(
      ok({ id: "a1", email: "a@example.com", role, region: "EU" }) as never,
    );
  }
}

beforeEach(() => {
  mockGet.mockReset();
});

describe("AdminNav permission gating", () => {
  it("shows only Data requests to SUPPORT_L1 (no USER_VIEW / AUDIT_VIEW)", async () => {
    setRole("SUPPORT_L1");
    renderWithProviders(<AdminNav />);

    await waitFor(() =>
      expect(
        screen.getByRole("link", { name: "Data requests" }),
      ).toBeInTheDocument(),
    );
    expect(screen.queryByRole("link", { name: "Users" })).not.toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "Audit log" }),
    ).not.toBeInTheDocument();
  });

  it("shows Users and Audit log to SUPPORT_L2", async () => {
    setRole("SUPPORT_L2");
    renderWithProviders(<AdminNav />);

    await waitFor(() =>
      expect(screen.getByRole("link", { name: "Users" })).toBeInTheDocument(),
    );
    expect(
      screen.getByRole("link", { name: "Audit log" }),
    ).toBeInTheDocument();
  });

  it("renders nothing when there is no admin session", async () => {
    setRole(null);
    const { container } = renderWithProviders(<AdminNav />);

    await waitFor(() => expect(mockGet).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
