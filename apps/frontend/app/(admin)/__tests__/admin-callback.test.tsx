import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fail, ok, renderWithProviders } from "@/lib/test/render";

const replace = vi.fn();
let params = new URLSearchParams();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/admin/auth/callback",
  useSearchParams: () => params,
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import AdminAuthCallbackPage from "../admin/auth/callback/page";

const mockPost = vi.mocked(api.POST);

beforeEach(() => {
  mockPost.mockReset();
  replace.mockReset();
});

describe("AdminAuthCallbackPage", () => {
  it("routes an ENROLL state to the enrollment page", async () => {
    params = new URLSearchParams("token=raw-1");
    mockPost.mockResolvedValue(ok({ state: "ENROLL" }) as never);
    renderWithProviders(<AdminAuthCallbackPage />);

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith(
        "/api/v1/admin/auth/magic-links/consume",
        { body: { token: "raw-1" } },
      ),
    );
    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith("/admin/mfa/enroll"),
    );
    expect(mockPost).toHaveBeenCalledTimes(1);
  });

  it("routes a CHALLENGE state to the challenge page", async () => {
    params = new URLSearchParams("token=raw-2");
    mockPost.mockResolvedValue(ok({ state: "CHALLENGE" }) as never);
    renderWithProviders(<AdminAuthCallbackPage />);

    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith("/admin/mfa/challenge"),
    );
  });

  it("shows the invalid-link state with a re-request path on failure", async () => {
    params = new URLSearchParams("token=expired");
    mockPost.mockResolvedValue(fail(400, "TOKEN_INVALID") as never);
    renderWithProviders(<AdminAuthCallbackPage />);

    expect(
      await screen.findByText(/invalid or has expired/i),
    ).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

  it("treats a missing token as an invalid link", async () => {
    params = new URLSearchParams();
    renderWithProviders(<AdminAuthCallbackPage />);

    expect(
      await screen.findByText(/invalid or has expired/i),
    ).toBeInTheDocument();
    expect(mockPost).not.toHaveBeenCalled();
  });
});
