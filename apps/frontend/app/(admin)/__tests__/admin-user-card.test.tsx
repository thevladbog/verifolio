import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fail, ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/admin/users/u1",
  useParams: () => ({ id: "u1" }),
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
import AdminUserCardPage from "../admin/users/[id]/page";

const mockGet = vi.mocked(api.GET);
const CARD_PATH = "/api/v1/admin/users/{id}";

const card = {
  account: {
    email: "alice@example.com",
    region: "EU",
    status: "ACTIVE",
    createdAt: "2026-01-02T00:00:00Z",
    deletedAt: null,
  },
  profile: {
    displayName: "Alice A.",
    legalName: "Alice Anderson",
    preferredLocale: "en-GB",
  },
  documentCount: 4,
  lockedDocumentCount: 2,
  consentCount: 3,
  sessionCount: 1,
  consents: [
    {
      consentType: "MARKETING",
      status: "GRANTED",
      policyTextVersion: "v3",
      grantedAt: "2026-01-03T00:00:00Z",
      withdrawnAt: null,
      createdAt: "2026-01-03T00:00:00Z",
    },
  ],
  sessions: [
    {
      createdAt: "2026-01-05T00:00:00Z",
      lastSeenAt: null,
      expiresAt: "2026-02-05T00:00:00Z",
      revokedAt: null,
    },
  ],
  dsrCountsByStatus: { RECEIVED: 1, EXECUTED: 2 },
};

beforeEach(() => {
  mockGet.mockReset();
  useAdminSession.mockReturnValue({
    admin: { id: "a1", email: "admin@example.com", role: "SUPPORT_L2", region: "EU" },
    isLoading: false,
  });
});

describe("AdminUserCardPage", () => {
  it("renders every card section, the footer note, and no action buttons", async () => {
    mockGet.mockResolvedValue(ok(card) as never);
    renderWithProviders(<AdminUserCardPage />);

    await waitFor(() =>
      expect(screen.getByText("alice@example.com")).toBeInTheDocument(),
    );

    // Header + status + profile
    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByText("Alice Anderson")).toBeInTheDocument();
    expect(screen.getByText("en-GB")).toBeInTheDocument();

    // Count cards
    expect(screen.getByText("4")).toBeInTheDocument(); // documents
    expect(screen.getByText("Consents")).toBeInTheDocument();
    expect(screen.getByText("2 locked versions")).toBeInTheDocument();
    expect(screen.getByText(/Received: 1/)).toBeInTheDocument(); // dsr breakdown

    // Consent + session history
    expect(screen.getByText("MARKETING")).toBeInTheDocument();
    expect(screen.getByText("Consent history")).toBeInTheDocument();
    expect(screen.getByText("Started")).toBeInTheDocument();

    // Footer note
    expect(
      screen.getByText(/Every view of personal data is audited/),
    ).toBeInTheDocument();

    // Read-only: no mutation controls
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("shows a not-found state on a 404", async () => {
    mockGet.mockResolvedValue(fail(404, "NOT_FOUND") as never);
    renderWithProviders(<AdminUserCardPage />);

    expect(await screen.findByText("User not found")).toBeInTheDocument();
  });
});
