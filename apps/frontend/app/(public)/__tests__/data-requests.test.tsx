import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fail, ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/data-requests",
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "dsr-123" }),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import DataRequestsPage from "../data-requests/page";
import DataRequestVerifyPage from "../data-requests/[id]/page";

const mockPost = vi.mocked(api.POST);

beforeEach(() => {
  mockPost.mockReset();
});

describe("DataRequestsPage (email entry)", () => {
  it("posts the email and shows the anti-enumeration state on 202", async () => {
    mockPost.mockResolvedValue({
      data: undefined,
      error: undefined,
      response: new Response(null, { status: 202 }),
    } as never);

    renderWithProviders(<DataRequestsPage />);

    await userEvent.type(
      screen.getByLabelText("Email address"),
      "rec@example.com",
    );
    await userEvent.click(screen.getByRole("button", { name: "Send code" }));

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith(
        "/api/v1/privacy/recommender-requests",
        { body: { email: "rec@example.com" } },
      ),
    );
    expect(
      await screen.findByText(/we've sent a verification code/),
    ).toBeInTheDocument();
  });
});

describe("DataRequestVerifyPage (code + type)", () => {
  it("verifies a consent withdrawal and shows the completed state", async () => {
    mockPost.mockResolvedValue(
      ok({ status: "EXECUTED", executed: true, dueAt: null }) as never,
    );

    renderWithProviders(<DataRequestVerifyPage />);

    await userEvent.type(screen.getByLabelText("Verification code"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "Confirm" }));

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith(
        "/api/v1/privacy/recommender-requests/{id}/verify",
        {
          params: { path: { id: "dsr-123" } },
          body: { code: "123456", type: "CONSENT_WITHDRAWAL" },
        },
      ),
    );
    expect(
      await screen.findByText(/Consent withdrawal completed/),
    ).toBeInTheDocument();
  });

  it("shows an inline error on CODE_INVALID", async () => {
    mockPost.mockResolvedValue(fail(400, "CODE_INVALID") as never);

    renderWithProviders(<DataRequestVerifyPage />);

    await userEvent.type(screen.getByLabelText("Verification code"), "000000");
    await userEvent.click(screen.getByRole("button", { name: "Confirm" }));

    expect(
      await screen.findByText("That code is not valid. Check the code and try again."),
    ).toBeInTheDocument();
  });
});
