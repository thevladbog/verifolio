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

const SUBMITTED_RESPONSE = {
  approvedLetterText: "Dear team,\n\nAnna is a great engineer.",
  answers: { relationship: "Manager", strengths: "Ownership" },
  submittedAt: "2026-07-04T10:00:00Z",
  recipientConfirmed: true,
  relationshipConfirmed: true,
  uploads: [
    {
      id: "up-1",
      kind: "SCAN",
      contentType: "application/pdf",
      sizeBytes: 123456,
      sharedPublicly: true,
      targetUploadId: null,
    },
    {
      id: "up-2",
      kind: "SIGNED_PDF",
      contentType: "application/pdf",
      sizeBytes: 2048,
      sharedPublicly: false,
      targetUploadId: null,
    },
  ],
};

function stubRequest(status: string, declinedReason: string | null = null) {
  mockGet.mockImplementation(async (path: string) => {
    if (path === "/api/v1/reference-requests/{id}")
      return ok({
        id: "req-1",
        recommenderContactId: "c1",
        templateId: "tpl-1",
        purpose: "Hiring",
        status,
        declinedReason,
        expiresAt: "2026-07-30T10:00:00Z",
        createdAt: "2026-07-01T10:00:00Z",
      }) as never;
    if (path === "/api/v1/reference-requests/{id}/response")
      return ok(SUBMITTED_RESPONSE) as never;
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

  it("shows the submitted letter, answers, and upload metadata in review", async () => {
    stubRequest("NEEDS_REVIEW");
    renderWithProviders(<RequestDetailPage />);

    // Letter preview with the exact submitted text.
    expect(
      await screen.findByText(/Anna is a great engineer/),
    ).toBeInTheDocument();

    // Answers as a definition list.
    expect(screen.getByText("relationship")).toBeInTheDocument();
    expect(screen.getByText("Manager")).toBeInTheDocument();
    expect(screen.getByText("strengths")).toBeInTheDocument();
    expect(screen.getByText("Ownership")).toBeInTheDocument();

    // Upload metadata rows: kind badge, human size, public-sharing marker.
    expect(screen.getByText("Scanned copy")).toBeInTheDocument();
    expect(screen.getByText("Signed PDF")).toBeInTheDocument();
    expect(screen.getByText("121 KB")).toBeInTheDocument();
    expect(screen.getByText("2.0 KB")).toBeInTheDocument();
    expect(screen.getAllByText("may be shared publicly")).toHaveLength(1);
  });

  it("shows the decline reason label for DECLINED requests", async () => {
    stubRequest("DECLINED", "TOO_BUSY");
    renderWithProviders(<RequestDetailPage />);

    expect(
      await screen.findByText("Recommender declined: Too busy"),
    ).toBeInTheDocument();
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
