import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fail, ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/admin/data-requests",
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

vi.mock("@/lib/api/client", () => ({
  api: { GET: vi.fn(), POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

const useAdminSession = vi.fn();
vi.mock("@/lib/admin/use-admin-session", () => ({
  useAdminSession: () => useAdminSession(),
}));

import { api } from "@/lib/api/client";
import AdminDataRequestsPage from "../admin/data-requests/page";

const mockGet = vi.mocked(api.GET);
const mockPost = vi.mocked(api.POST);

const LIST_PATH = "/api/v1/admin/data-subject-requests";
const DETAIL_PATH = "/api/v1/admin/data-subject-requests/{id}";

const items = [
  {
    id: "d1",
    type: "DELETION",
    status: "RECEIVED",
    subjectEmail: "alice@example.com",
    dueAt: "2026-07-20T00:00:00Z",
    createdAt: "2026-07-05T00:00:00Z",
  },
  {
    id: "d2",
    type: "EXPORT",
    status: "IN_REVIEW",
    subjectEmail: "bob@example.com",
    dueAt: "2026-07-21T00:00:00Z",
    createdAt: "2026-07-06T00:00:00Z",
  },
];

const detail = {
  id: "d1",
  type: "DELETION",
  status: "RECEIVED",
  subjectEmail: "alice@example.com",
  region: "EU",
  dueAt: "2026-07-20T00:00:00Z",
  createdAt: "2026-07-05T00:00:00Z",
};

function setSession(role: string) {
  useAdminSession.mockReturnValue({
    admin: { id: "a1", email: "admin@example.com", role, region: "EU" },
    isLoading: false,
  });
}

function wireGet() {
  mockGet.mockImplementation((path: string) => {
    if (path === LIST_PATH) {
      return Promise.resolve(ok({ items, nextCursor: null })) as never;
    }
    if (path === DETAIL_PATH) {
      return Promise.resolve(ok(detail)) as never;
    }
    return Promise.resolve(ok({})) as never;
  });
}

beforeEach(() => {
  mockGet.mockReset();
  mockPost.mockReset();
  setSession("SUPPORT_L2");
  wireGet();
});

describe("AdminDataRequestsPage", () => {
  it("renders queue items and filters by status", async () => {
    renderWithProviders(<AdminDataRequestsPage />);

    await waitFor(() =>
      expect(screen.getByText("alice@example.com")).toBeInTheDocument(),
    );
    expect(screen.getByText("bob@example.com")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Received" }));

    await waitFor(() =>
      expect(mockGet).toHaveBeenCalledWith(
        LIST_PATH,
        expect.objectContaining({
          params: expect.objectContaining({
            query: expect.objectContaining({ status: "RECEIVED" }),
          }),
        }),
      ),
    );
  });

  it("hides decision actions from an L1 admin", async () => {
    setSession("SUPPORT_L1");
    renderWithProviders(<AdminDataRequestsPage />);

    await userEvent.click(
      await screen.findByRole("button", { name: /alice@example.com/ }),
    );
    await waitFor(() =>
      expect(screen.getByText("EU")).toBeInTheDocument(),
    );

    expect(
      screen.queryByRole("button", { name: "Approve" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Reject" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Execute" }),
    ).not.toBeInTheDocument();
  });

  it("lets an L2 admin approve and refetches", async () => {
    mockPost.mockResolvedValue(ok({ id: "d1", status: "APPROVED" }) as never);
    renderWithProviders(<AdminDataRequestsPage />);

    await userEvent.click(
      await screen.findByRole("button", { name: /alice@example.com/ }),
    );
    const approve = await screen.findByRole("button", { name: "Approve" });
    const listCallsBefore = mockGet.mock.calls.filter(
      (c) => c[0] === LIST_PATH,
    ).length;

    await userEvent.click(approve);

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith(
        "/api/v1/admin/data-subject-requests/{id}/approve",
        expect.objectContaining({
          params: { path: { id: "d1" } },
        }),
      ),
    );
    await waitFor(() =>
      expect(
        mockGet.mock.calls.filter((c) => c[0] === LIST_PATH).length,
      ).toBeGreaterThan(listCallsBefore),
    );
  });

  it("shows a manual-execution state on 409 EXECUTION_NOT_AUTOMATED", async () => {
    mockPost.mockResolvedValue(
      fail(409, "EXECUTION_NOT_AUTOMATED") as never,
    );
    renderWithProviders(<AdminDataRequestsPage />);

    await userEvent.click(
      await screen.findByRole("button", { name: /alice@example.com/ }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Execute" }),
    );

    const notice = await screen.findByText("Manual execution required");
    expect(notice).toBeInTheDocument();
    // The detail panel shows the calm status, not a scary generic error.
    expect(
      within(notice.closest("[role=status]") as HTMLElement).getByText(
        /automated execution/i,
      ),
    ).toBeInTheDocument();
  });
});
