import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ok, renderWithProviders } from "@/lib/test/render";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/admin",
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/api/client", () => ({
  api: { GET: vi.fn(), POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

const useAdminSession = vi.fn();
vi.mock("@/lib/admin/use-admin-session", () => ({
  useAdminSession: () => useAdminSession(),
}));

import { api } from "@/lib/api/client";
import AdminDashboardPage from "../admin/page";

const mockGet = vi.mocked(api.GET);

beforeEach(() => {
  mockGet.mockReset();
  replace.mockReset();
  useAdminSession.mockReturnValue({
    admin: {
      id: "a1",
      email: "admin@example.com",
      role: "SUPPORT_L2",
      region: "EU",
    },
    isLoading: false,
  });
});

describe("AdminDashboardPage", () => {
  it("renders the pending DSR count and the admin identity", async () => {
    mockGet.mockResolvedValue(
      ok({ dsrPendingTotal: 7, dsrByStatus: { RECEIVED: 5, IN_REVIEW: 2 } }) as never,
    );
    renderWithProviders(<AdminDashboardPage />);

    expect(screen.getByText(/admin@example.com/)).toBeInTheDocument();
    expect(screen.getByText("Support L2")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("7")).toBeInTheDocument());
  });
});
