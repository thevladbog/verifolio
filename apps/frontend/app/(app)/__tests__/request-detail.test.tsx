import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/requests/req-1",
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "req-1" }),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import RequestDetailPage from "../requests/[id]/page";

const mockGet = vi.mocked(api.GET);
const mockPost = vi.mocked(api.POST);

function stubRequest(status: string) {
  mockGet.mockImplementation(async (path: string) => {
    if (path === "/api/v1/reference-requests/{id}")
      return ok({
        id: "req-1",
        recommenderContactId: "c1",
        templateId: "tpl-1",
        purpose: "Hiring",
        status,
        expiresAt: "2026-07-30T10:00:00Z",
        createdAt: "2026-07-01T10:00:00Z",
      }) as never;
    if (path === "/api/v1/contacts")
      return ok({
        items: [{ id: "c1", name: "Dmitry Orlov", email: "d@techflow.io" }],
        nextCursor: null,
      }) as never;
    if (path === "/api/v1/templates")
      return ok({
        items: [
          {
            id: "tpl-1",
            type: "EMPLOYMENT_REFERENCE",
            name: "Employment Reference",
            description: "",
            locale: "en",
          },
        ],
      }) as never;
    throw new Error(`unexpected GET ${path}`);
  });
}

beforeEach(() => {
  mockGet.mockReset();
  mockPost.mockReset();
});

describe("RequestDetailPage", () => {
  it("offers Send only for CREATED", async () => {
    stubRequest("CREATED");
    renderWithProviders(<RequestDetailPage />);

    expect(
      await screen.findByRole("button", { name: "Send invitation" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Accept and lock" }),
    ).not.toBeInTheDocument();
  });

  it("hides Send and shows Cancel for SENT", async () => {
    stubRequest("SENT");
    renderWithProviders(<RequestDetailPage />);

    expect(
      await screen.findByRole("button", { name: "Cancel request" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Send invitation" }),
    ).not.toBeInTheDocument();
  });

  it("accepts a NEEDS_REVIEW response and links to the created document", async () => {
    stubRequest("NEEDS_REVIEW");
    mockPost.mockResolvedValue(
      ok({ request: { id: "req-1", status: "COMPLETED" }, documentId: "doc-9" }) as never,
    );
    renderWithProviders(<RequestDetailPage />);

    await userEvent.click(
      await screen.findByRole("button", { name: "Accept and lock" }),
    );

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith(
        "/api/v1/reference-requests/{id}/accept",
        { params: { path: { id: "req-1" } } },
      ),
    );
    const link = await screen.findByRole("link", { name: "Open document" });
    expect(link).toHaveAttribute("href", "/documents/doc-9");
  });

  it("sends a correction request with the optional message", async () => {
    stubRequest("NEEDS_REVIEW");
    mockPost.mockResolvedValue(ok({ id: "req-1" }) as never);
    renderWithProviders(<RequestDetailPage />);

    await userEvent.click(
      await screen.findByRole("button", { name: "Request correction" }),
    );
    await userEvent.type(
      screen.getByPlaceholderText("What should be corrected? (optional)"),
      "Please fix the dates",
    );
    await userEvent.click(
      screen.getAllByRole("button", { name: "Request correction" }).at(-1)!,
    );

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith(
        "/api/v1/reference-requests/{id}/request-correction",
        {
          params: { path: { id: "req-1" } },
          body: { message: "Please fix the dates" },
        },
      ),
    );
  });

  it("shows the terminal banner instead of actions for EXPIRED", async () => {
    stubRequest("EXPIRED");
    renderWithProviders(<RequestDetailPage />);

    expect(await screen.findByText(/request expired/i)).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Cancel request" }),
    ).not.toBeInTheDocument();
  });
});
