import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/admin/audit",
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

vi.mock("@/lib/api/client", () => ({
  api: { GET: vi.fn(), POST: vi.fn() },
}));

const useAdminSession = vi.fn();
vi.mock("@/lib/admin/use-admin-session", () => ({
  useAdminSession: () => useAdminSession(),
}));

import { api } from "@/lib/api/client";
import AdminAuditPage from "../admin/audit/page";

const mockGet = vi.mocked(api.GET);
const LIST_PATH = "/api/v1/admin/audit-logs";

const items = [
  {
    id: "e1",
    createdAt: "2026-03-01T10:00:00Z",
    actorType: "ADMIN",
    actorId: "admin-1",
    action: "ADMIN_USER_DETAIL_VIEWED",
    entityType: "USER_ACCOUNT",
    entityId: "u1",
    metadata: { region: "EU" },
  },
];

function setRole(role: string) {
  useAdminSession.mockReturnValue({
    admin: { id: "a1", email: "admin@example.com", role, region: "EU" },
    isLoading: false,
  });
}

beforeEach(() => {
  mockGet.mockReset();
  setRole("SUPPORT_L2");
  mockGet.mockResolvedValue(ok({ items, nextCursor: null }) as never);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("AdminAuditPage", () => {
  it("renders rows with the append-only note", async () => {
    renderWithProviders(<AdminAuditPage />);

    await waitFor(() =>
      expect(
        screen.getByText("ADMIN_USER_DETAIL_VIEWED"),
      ).toBeInTheDocument(),
    );
    expect(screen.getByText("USER_ACCOUNT")).toBeInTheDocument();
    expect(screen.getByText(/Append-only/)).toBeInTheDocument();
  });

  it("filters by actor type and action", async () => {
    renderWithProviders(<AdminAuditPage />);
    await screen.findByText("ADMIN_USER_DETAIL_VIEWED");

    await userEvent.selectOptions(
      screen.getByLabelText("Actor type"),
      "ADMIN",
    );
    await userEvent.type(screen.getByLabelText("Action"), "ADMIN_USER");

    await waitFor(() =>
      expect(mockGet).toHaveBeenCalledWith(
        LIST_PATH,
        expect.objectContaining({
          params: expect.objectContaining({
            query: expect.objectContaining({
              actorType: "ADMIN",
              action: "ADMIN_USER",
            }),
          }),
        }),
      ),
    );
  });

  it("hides the Export CSV button from a non-SUPERADMIN (AUDIT_EXPORT)", async () => {
    renderWithProviders(<AdminAuditPage />);
    await screen.findByText("ADMIN_USER_DETAIL_VIEWED");

    expect(
      screen.queryByRole("button", { name: "Export CSV" }),
    ).not.toBeInTheDocument();
  });

  it("lets a SUPERADMIN export the current filters as a CSV download", async () => {
    setRole("SUPERADMIN");
    const blob = new Blob(["id,action\n"], { type: "text/csv" });
    // Return a plain response-like object (not a real Response): jsdom's Response.blob()
    // reads the body via Blob.stream(), which isn't implemented in the CI jsdom build.
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, status: 200, blob: async () => blob });
    vi.stubGlobal("fetch", fetchMock);
    const createUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:mock");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    const clickSpy = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});

    renderWithProviders(<AdminAuditPage />);
    await screen.findByText("ADMIN_USER_DETAIL_VIEWED");

    await userEvent.selectOptions(
      screen.getByLabelText("Actor type"),
      "ADMIN",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Export CSV" }),
    );

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toContain("/api/v1/admin/audit-logs/export");
    expect(url).toContain("actorType=ADMIN");
    expect(fetchMock.mock.calls[0][1]).toMatchObject({
      credentials: "include",
    });
    expect(createUrl).toHaveBeenCalled();
    expect(clickSpy).toHaveBeenCalled();
  });
});
