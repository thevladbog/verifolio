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
import { VerifyContent } from "../verify-content";

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

describe("VerifyContent", () => {
  it("renders the tombstoned state with only the notice — no signals or downloads", () => {
    renderWithProviders(
      <VerifyContent
        token="tok"
        page={{
          status: "TOMBSTONED",
          notice: "Removed at the data subject's request.",
          recipient: null,
          recommender: null,
          version: null,
          badges: [],
          downloads: [
            { id: "gen", kind: "GENERATED_PDF", filename: null, downloadable: true },
          ],
        }}
      />,
    );

    expect(
      screen.getByText("Removed at the data subject's request."),
    ).toBeInTheDocument();
    // No signals section, no downloads, no version/recipient blocks.
    expect(screen.queryByText("Verification badges")).not.toBeInTheDocument();
    expect(screen.queryByText("Files")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Download" }),
    ).not.toBeInTheDocument();
  });

  it("renders the retracted banner and still shows revoked signals + the PDF download", () => {
    renderWithProviders(
      <VerifyContent
        token="tok"
        page={{
          status: "ACTIVE",
          header: { verificationId: "V-1", documentType: "Reference letter" },
          recipient: { name: "Recipient A" },
          version: {
            versionNumber: 1,
            lockedAt: "2026-01-01T00:00:00Z",
            retractedAt: "2026-06-01T00:00:00Z",
          },
          badges: [
            {
              signalType: "PUBLIC_VERIFICATION_ENABLED",
              title: "Public verification",
              status: "REVOKED",
              date: "2026-06-01",
            },
          ],
          downloads: [
            { id: "gen", kind: "GENERATED_PDF", filename: null, downloadable: true },
          ],
        }}
      />,
    );

    expect(
      screen.getByText(/Recommendation retracted by the recommender/),
    ).toBeInTheDocument();
    // The revoked signal renders with the failed variant badge.
    const badge = screen.getByText("Public verification").closest("[data-trust-badge]");
    expect(badge).toHaveAttribute("data-trust-badge", "failed");
    // The generated PDF is still downloadable.
    expect(
      screen.getByRole("button", { name: "Download" }),
    ).toBeInTheDocument();
  });

  it("shows the verified organization record on the corporate-domain badge when present", () => {
    renderWithProviders(
      <VerifyContent
        token="tok"
        page={{
          status: "ACTIVE",
          header: { verificationId: "V-1", documentType: "Reference letter" },
          badges: [
            {
              signalType: "CORPORATE_DOMAIN_CONFIRMED",
              title: "Corporate domain confirmed",
              status: "VERIFIED",
              date: "2026-06-01",
              organizationName: "Acme Corp",
              organizationSource: "verified-record",
            },
          ],
          downloads: [],
        }}
      />,
    );

    expect(
      screen.getByText("at Acme Corp — verified organization record"),
    ).toBeInTheDocument();
  });

  it("renders the corporate-domain badge unchanged when the org name is absent (recommender-stated)", () => {
    renderWithProviders(
      <VerifyContent
        token="tok"
        page={{
          status: "ACTIVE",
          header: { verificationId: "V-1", documentType: "Reference letter" },
          badges: [
            {
              signalType: "CORPORATE_DOMAIN_CONFIRMED",
              title: "Corporate domain confirmed",
              status: "VERIFIED",
              date: "2026-06-01",
              organizationName: null,
              organizationSource: null,
            },
          ],
          downloads: [],
        }}
      />,
    );

    expect(
      screen.getByText("Corporate domain confirmed"),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(/verified organization record/),
    ).not.toBeInTheDocument();
  });
});
