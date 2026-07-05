import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/requests",
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import RequestsPage from "../requests/page";

const mockGet = vi.mocked(api.GET);

const REQUEST = {
  id: "req-1",
  recommenderContactId: "c1",
  templateId: "tpl-1",
  purpose: "International hiring",
  status: "SENT",
  expiresAt: "2026-07-30T10:00:00Z",
  createdAt: "2026-07-01T10:00:00Z",
};

beforeEach(() => {
  mockGet.mockReset();
  mockGet.mockImplementation(async (path: string, opts?: unknown) => {
    if (path === "/api/v1/contacts")
      return ok({
        items: [{ id: "c1", name: "Dmitry Orlov", email: "d@techflow.io" }],
        nextCursor: null,
      }) as never;
    if (path === "/api/v1/reference-requests") {
      const query = (opts as { params: { query?: { status?: string } } })
        .params.query;
      if (query?.status === "COMPLETED")
        return ok({ items: [], nextCursor: null }) as never;
      return ok({ items: [REQUEST], nextCursor: null }) as never;
    }
    throw new Error(`unexpected GET ${path}`);
  });
});

describe("RequestsPage", () => {
  it("renders request cards with the joined contact name and status badge", async () => {
    renderWithProviders(<RequestsPage />);

    expect(await screen.findByText("Dmitry Orlov")).toBeInTheDocument();
    expect(screen.getByText("International hiring")).toBeInTheDocument();
    const badge = document.querySelector('[data-trust-badge="pending"]');
    expect(badge).toHaveTextContent("Sent");
  });

  it("passes the status filter to the API and shows the empty state", async () => {
    renderWithProviders(<RequestsPage />);
    await screen.findByText("Dmitry Orlov");

    await userEvent.click(screen.getByRole("button", { name: "Completed" }));

    expect(await screen.findByText(/No requests yet/)).toBeInTheDocument();
    expect(mockGet).toHaveBeenCalledWith("/api/v1/reference-requests", {
      params: { query: { status: "COMPLETED" } },
    });
  });
});
