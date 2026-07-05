import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fail, ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/admin/login",
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import AdminLoginPage from "../admin/login/page";

const mockPost = vi.mocked(api.POST);

beforeEach(() => {
  mockPost.mockReset();
});

describe("AdminLoginPage", () => {
  it("requests an admin magic link and shows anti-enumeration copy on 202", async () => {
    mockPost.mockResolvedValue(ok({ message: "ok" }, 202) as never);
    renderWithProviders(<AdminLoginPage />);

    await userEvent.type(screen.getByLabelText("Email"), "admin@example.com");
    await userEvent.click(
      screen.getByRole("button", { name: "Send sign-in link" }),
    );

    expect(await screen.findByRole("status")).toHaveTextContent(
      /if this is an admin address/i,
    );
    expect(mockPost).toHaveBeenCalledWith("/api/v1/admin/auth/magic-links", {
      body: { email: "admin@example.com" },
    });
  });

  it("keeps the form (no success copy) when rate limited", async () => {
    mockPost.mockResolvedValue(fail(429, "RATE_LIMITED") as never);
    renderWithProviders(<AdminLoginPage />);

    await userEvent.type(screen.getByLabelText("Email"), "admin@example.com");
    await userEvent.click(
      screen.getByRole("button", { name: "Send sign-in link" }),
    );

    await waitFor(() => expect(mockPost).toHaveBeenCalled());
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Email")).toBeInTheDocument();
  });
});
