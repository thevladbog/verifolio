import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/documents/doc-1",
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "doc-1" }),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import DocumentDetailPage from "../documents/[id]/page";

const mockGet = vi.mocked(api.GET);
const mockPost = vi.mocked(api.POST);

const DOCUMENT = {
  id: "doc-1",
  requestId: "req-1",
  type: "REFERENCE_LETTER",
  status: "ACTIVE",
  currentVersionNumber: 1,
  versions: [
    {
      versionNumber: 1,
      status: "LOCKED",
      sha256Hash: "abc123",
      lockedAt: "2026-07-02T14:20:00Z",
      createdAt: "2026-07-02T14:20:00Z",
    },
  ],
  createdAt: "2026-07-02T14:20:00Z",
};

beforeEach(() => {
  mockGet.mockReset();
  mockPost.mockReset();
  vi.stubGlobal("open", vi.fn());
  mockGet.mockImplementation(async (path: string) => {
    if (path === "/api/v1/documents/{id}") return ok(DOCUMENT) as never;
    if (path === "/api/v1/documents/{id}/share-links")
      return ok({ items: [] }) as never;
    if (path === "/api/v1/documents/{id}/versions/{versionNumber}/download-url")
      return ok({ url: "https://storage/presigned", expiresAt: "" }) as never;
    throw new Error(`unexpected GET ${path}`);
  });
});

describe("DocumentDetailPage", () => {
  it("fetches the presigned URL on click and opens it immediately", async () => {
    renderWithProviders(<DocumentDetailPage />);

    const buttons = await screen.findAllByRole("button", {
      name: "Download PDF",
    });
    await userEvent.click(buttons[0]);

    await waitFor(() =>
      expect(mockGet).toHaveBeenCalledWith(
        "/api/v1/documents/{id}/versions/{versionNumber}/download-url",
        { params: { path: { id: "doc-1", versionNumber: 1 } } },
      ),
    );
    expect(window.open).toHaveBeenCalledWith(
      "https://storage/presigned",
      "_blank",
      "noopener",
    );
    // The presigned URL is never rendered into the DOM.
    expect(document.body.innerHTML).not.toContain("https://storage/presigned");
  });

  it("creates a share link and shows the raw URL exactly once", async () => {
    mockPost.mockResolvedValue(
      ok({
        id: "sl-1",
        url: "https://app/verify/raw-token",
        versionNumber: 1,
        expiresAt: null,
        createdAt: "2026-07-05T10:00:00Z",
      }) as never,
    );
    renderWithProviders(<DocumentDetailPage />);

    await userEvent.click(await screen.findByRole("button", { name: "Share" }));
    await userEvent.click(screen.getByRole("button", { name: "No expiry" }));
    await userEvent.click(screen.getByRole("button", { name: "Create link" }));

    expect(
      await screen.findByText("https://app/verify/raw-token"),
    ).toBeInTheDocument();
    expect(mockPost).toHaveBeenCalledWith("/api/v1/documents/{id}/share-links", {
      params: { path: { id: "doc-1" } },
      body: { expiresInDays: null },
    });

    // Close and reopen — the raw URL must be gone.
    await userEvent.click(screen.getAllByRole("button", { name: "Close" })[0]);
    await userEvent.click(screen.getByRole("button", { name: "Share" }));
    expect(
      screen.queryByText("https://app/verify/raw-token"),
    ).not.toBeInTheDocument();
  });

  it("revokes a share link", async () => {
    mockGet.mockImplementation(async (path: string) => {
      if (path === "/api/v1/documents/{id}") return ok(DOCUMENT) as never;
      if (path === "/api/v1/documents/{id}/share-links")
        return ok({
          items: [
            {
              id: "sl-1",
              versionNumber: 1,
              expiresAt: null,
              revokedAt: null,
              createdAt: "2026-07-01T10:00:00Z",
            },
          ],
        }) as never;
      throw new Error(`unexpected GET ${path}`);
    });
    mockPost.mockResolvedValue(ok({}) as never);
    renderWithProviders(<DocumentDetailPage />);

    await userEvent.click(await screen.findByRole("button", { name: "Revoke" }));

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith("/api/v1/share-links/{id}/revoke", {
        params: { path: { id: "sl-1" } },
      }),
    );
  });
});
