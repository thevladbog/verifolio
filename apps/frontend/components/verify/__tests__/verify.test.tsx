import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/verify/tok",
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ token: "tok" }),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import { publicBadgeVariant } from "../badge-variant";
import { DownloadsPanel } from "../downloads-panel";

const mockGet = vi.mocked(api.GET);

beforeEach(() => {
  mockGet.mockReset();
  vi.stubGlobal("open", vi.fn());
});

describe("publicBadgeVariant", () => {
  it("keeps each trust type on its own tone", () => {
    expect(publicBadgeVariant("EMAIL_CONFIRMED", "VERIFIED")).toBe("verified");
    expect(publicBadgeVariant("SIGNATURE_ATTACHED", "VERIFIED")).toBe("signed");
    expect(publicBadgeVariant("VERSION_LOCKED", "VERIFIED")).toBe("locked");
    expect(publicBadgeVariant("PUBLIC_VERIFICATION_ENABLED", "REVOKED")).toBe(
      "failed",
    );
    expect(publicBadgeVariant("PUBLIC_VERIFICATION_ENABLED", "EXPIRED")).toBe(
      "expired",
    );
  });
});

describe("DownloadsPanel", () => {
  it("lists unconsented attachments without filename and without a button", () => {
    renderWithProviders(
      <DownloadsPanel
        token="tok"
        downloads={[
          { id: "gen", kind: "GENERATED_PDF", filename: null, downloadable: true },
          { id: "a1", kind: "SCAN", filename: null, downloadable: false },
        ]}
      />,
    );

    expect(screen.getByText("Verified document (PDF)")).toBeInTheDocument();
    expect(screen.getByText("not shared publicly")).toBeInTheDocument();
    // Exactly one download button — the unconsented scan has none.
    expect(screen.getAllByRole("button", { name: "Download" })).toHaveLength(1);
  });

  it("fetches the presigned URL on click and opens it immediately", async () => {
    mockGet.mockResolvedValue(
      ok({ url: "https://storage/presigned-public", expiresAt: "" }) as never,
    );
    renderWithProviders(
      <DownloadsPanel
        token="tok"
        downloads={[
          {
            id: "a1",
            kind: "SCAN",
            filename: "scan.pdf",
            downloadable: true,
          },
        ]}
      />,
    );

    await userEvent.click(screen.getByRole("button", { name: "Download" }));

    await waitFor(() =>
      expect(mockGet).toHaveBeenCalledWith(
        "/api/v1/verification-pages/{token}/attachments/{attachmentId}/download-url",
        { params: { path: { token: "tok", attachmentId: "a1" } } },
      ),
    );
    expect(window.open).toHaveBeenCalledWith(
      "https://storage/presigned-public",
      "_blank",
      "noopener",
    );
    expect(document.body.innerHTML).not.toContain("presigned-public");
  });
});
