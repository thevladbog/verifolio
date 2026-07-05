import { QueryClient } from "@tanstack/react-query";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fail, ok, renderWithProviders } from "@/lib/test/render";

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
    isError: false,
    refetch: vi.fn(),
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

  it("shows an error+retry (never a false '0') when the pending count fails to load", async () => {
    mockGet.mockResolvedValue(fail(500, "INTERNAL") as never);
    renderWithProviders(<AdminDashboardPage />);

    await waitFor(() =>
      expect(
        screen.getByText("Couldn't load the pending count."),
      ).toBeInTheDocument(),
    );
    // A dangerous false "0" must never be rendered on failure.
    expect(screen.queryByText("0")).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Try again" }),
    ).toBeInTheDocument();
  });

  it("clears the query cache on logout before redirecting to /admin/login", async () => {
    const clearSpy = vi.spyOn(QueryClient.prototype, "clear");
    mockGet.mockResolvedValue(ok({ dsrPendingTotal: 0 }) as never);
    mockGet.mockImplementation((path: string) => {
      if (path === "/api/v1/admin/dashboard") {
        return Promise.resolve(ok({ dsrPendingTotal: 3 })) as never;
      }
      return Promise.resolve(ok({})) as never;
    });
    renderWithProviders(<AdminDashboardPage />);

    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(clearSpy).toHaveBeenCalled());
    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith("/admin/login"),
    );
    clearSpy.mockRestore();
  });

  it("renders an error+retry instead of spinning forever on a non-401 session error", async () => {
    const refetch = vi.fn();
    useAdminSession.mockReturnValue({
      admin: undefined,
      isLoading: false,
      isError: true,
      refetch,
    });
    renderWithProviders(<AdminDashboardPage />);

    const retry = await screen.findByRole("button", { name: "Try again" });
    expect(retry).toBeInTheDocument();
    // It must not be stuck on the loading text.
    expect(screen.queryByText("Loading…")).not.toBeInTheDocument();

    await userEvent.click(retry);
    expect(refetch).toHaveBeenCalled();
  });
});
