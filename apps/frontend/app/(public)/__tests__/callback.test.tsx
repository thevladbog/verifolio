import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fail, ok, renderWithProviders } from "@/lib/test/render";

const replace = vi.fn();
let params = new URLSearchParams();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/auth/callback",
  useSearchParams: () => params,
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import AuthCallbackPage from "../auth/callback/page";

const mockPost = vi.mocked(api.POST);
const mockGet = vi.mocked(api.GET);

beforeEach(() => {
  mockPost.mockReset();
  mockGet.mockReset();
  replace.mockReset();
});

describe("AuthCallbackPage", () => {
  it("exchanges the token for a session and redirects to the dashboard", async () => {
    params = new URLSearchParams("token=raw-token-1");
    mockPost.mockResolvedValue(ok({ userId: "u1" }) as never);
    mockGet.mockResolvedValue(ok({ displayName: "Ada" }) as never);
    renderWithProviders(<AuthCallbackPage />);

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith("/api/v1/auth/sessions", {
        body: { token: "raw-token-1" },
      }),
    );
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard"));
    expect(mockPost).toHaveBeenCalledTimes(1);
  });

  it("routes a fresh profile through onboarding", async () => {
    params = new URLSearchParams("token=raw-token-2");
    mockPost.mockResolvedValue(ok({ userId: "u1" }) as never);
    mockGet.mockResolvedValue(ok({ displayName: null }) as never);
    renderWithProviders(<AuthCallbackPage />);

    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith("/profile?welcome=1"),
    );
  });

  it("shows the invalid-link state with a re-request path on failure", async () => {
    params = new URLSearchParams("token=expired");
    mockPost.mockResolvedValue(fail(401, "UNAUTHORIZED") as never);
    renderWithProviders(<AuthCallbackPage />);

    expect(
      await screen.findByText(/invalid or has expired/i),
    ).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });

  it("treats a missing token as an invalid link", async () => {
    params = new URLSearchParams();
    renderWithProviders(<AuthCallbackPage />);

    expect(
      await screen.findByText(/invalid or has expired/i),
    ).toBeInTheDocument();
    expect(mockPost).not.toHaveBeenCalled();
  });
});
