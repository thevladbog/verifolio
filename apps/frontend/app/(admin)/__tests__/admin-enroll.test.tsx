import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ok, renderWithProviders } from "@/lib/test/render";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/admin/mfa/enroll",
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

// Rendering the real QR SVG adds nothing to the assertions and slows the suite.
vi.mock("react-qr-code", () => ({
  default: ({ value }: { value: string }) => (
    <div data-testid="qr-code">{value}</div>
  ),
}));

import { api } from "@/lib/api/client";
import AdminMfaEnrollPage from "../admin/mfa/enroll/page";

const mockGet = vi.mocked(api.GET);
const mockPost = vi.mocked(api.POST);

beforeEach(() => {
  mockGet.mockReset();
  mockPost.mockReset();
  replace.mockReset();
});

describe("AdminMfaEnrollPage", () => {
  it("renders the secret and otpauth QR, then posts the entered code", async () => {
    mockGet.mockResolvedValue(
      ok({
        secretBase32: "JBSWY3DPEHPK3PXP",
        otpauthUri: "otpauth://totp/Verifolio:admin?secret=JBSWY3DPEHPK3PXP",
      }) as never,
    );
    mockPost.mockResolvedValue(ok({ ok: true }) as never);
    renderWithProviders(<AdminMfaEnrollPage />);

    expect(await screen.findByTestId("mfa-secret")).toHaveTextContent(
      "JBSWY3DPEHPK3PXP",
    );
    expect(screen.getByTestId("qr-code")).toHaveTextContent("otpauth://totp");

    await userEvent.type(
      screen.getByLabelText("Authenticator code"),
      "123456",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Enable two-factor" }),
    );

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith("/api/v1/admin/auth/mfa/enroll", {
        body: { code: "123456" },
      }),
    );
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/admin"));
  });
});
