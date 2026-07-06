import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/admin/users",
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/api/client", () => ({
  api: { GET: vi.fn(), POST: vi.fn() },
}));

const useAdminSession = vi.fn();
vi.mock("@/lib/admin/use-admin-session", () => ({
  useAdminSession: () => useAdminSession(),
}));

import { api } from "@/lib/api/client";
import AdminUsersPage from "../admin/users/page";

const mockGet = vi.mocked(api.GET);
const LIST_PATH = "/api/v1/admin/users";

const items = [
  {
    id: "u1",
    email: "alice@example.com",
    displayName: "Alice A.",
    region: "EU",
    status: "ACTIVE",
    createdAt: "2026-01-02T00:00:00Z",
  },
  {
    id: "u2",
    email: "bob@example.com",
    displayName: null,
    region: "EU",
    status: "DELETED",
    createdAt: "2026-02-02T00:00:00Z",
  },
];

beforeEach(() => {
  mockGet.mockReset();
  useAdminSession.mockReturnValue({
    admin: { id: "a1", email: "admin@example.com", role: "SUPPORT_L2", region: "EU" },
    isLoading: false,
  });
  mockGet.mockResolvedValue(ok({ items, nextCursor: null }) as never);
});

describe("AdminUsersPage", () => {
  it("renders users with status badges and links each row to its card", async () => {
    renderWithProviders(<AdminUsersPage />);

    await waitFor(() =>
      expect(screen.getByText("alice@example.com")).toBeInTheDocument(),
    );
    expect(screen.getByText("Alice A.")).toBeInTheDocument();

    const aliceRow = screen.getByRole("link", { name: /alice@example.com/ });
    expect(aliceRow).toHaveAttribute("href", "/admin/users/u1");
    // The status badge (not the filter chip) renders inside the row.
    expect(within(aliceRow).getByText("Active")).toBeInTheDocument();
    expect(
      within(
        screen.getByRole("link", { name: /bob@example.com/ }),
      ).getByText("Deleted"),
    ).toBeInTheDocument();
  });

  it("filters by status", async () => {
    renderWithProviders(<AdminUsersPage />);
    await screen.findByText("alice@example.com");

    await userEvent.click(screen.getByRole("button", { name: "Disabled" }));

    await waitFor(() =>
      expect(mockGet).toHaveBeenCalledWith(
        LIST_PATH,
        expect.objectContaining({
          params: expect.objectContaining({
            query: expect.objectContaining({ status: "DISABLED" }),
          }),
        }),
      ),
    );
  });

  it("passes the debounced search query to the list endpoint", async () => {
    renderWithProviders(<AdminUsersPage />);
    await screen.findByText("alice@example.com");

    await userEvent.type(
      screen.getByPlaceholderText("Search by email or name"),
      "alice",
    );

    await waitFor(() =>
      expect(mockGet).toHaveBeenCalledWith(
        LIST_PATH,
        expect.objectContaining({
          params: expect.objectContaining({
            query: expect.objectContaining({ query: "alice" }),
          }),
        }),
      ),
    );
  });

  it("surfaces an error+retry on a non-401 session error", async () => {
    const refetch = vi.fn();
    useAdminSession.mockReturnValue({
      admin: undefined,
      isLoading: false,
      isError: true,
      refetch,
    });
    renderWithProviders(<AdminUsersPage />);

    await userEvent.click(await screen.findByRole("button", { name: "Try again" }));
    expect(refetch).toHaveBeenCalled();
  });
});
