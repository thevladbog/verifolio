import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fail, ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/login",
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import LoginPage from "../login/page";

const mockPost = vi.mocked(api.POST);

beforeEach(() => {
  mockPost.mockReset();
});

describe("LoginPage", () => {
  it("requests a magic link and shows anti-enumeration copy on 202", async () => {
    mockPost.mockResolvedValue(ok(undefined, 202) as never);
    renderWithProviders(<LoginPage />);

    await userEvent.type(screen.getByLabelText("Email"), "user@example.com");
    await userEvent.click(
      screen.getByRole("button", { name: "Send sign-in link" }),
    );

    expect(await screen.findByRole("status")).toHaveTextContent(
      /a sign-in link is on its way/i,
    );
    expect(mockPost).toHaveBeenCalledWith("/api/v1/auth/magic-links", {
      body: { email: "user@example.com" },
    });
  });

  it("validates email before calling the API", async () => {
    renderWithProviders(<LoginPage />);

    await userEvent.type(screen.getByLabelText("Email"), "not-an-email");
    await userEvent.click(
      screen.getByRole("button", { name: "Send sign-in link" }),
    );

    expect(
      await screen.findByText("Enter a valid email address"),
    ).toBeInTheDocument();
    expect(mockPost).not.toHaveBeenCalled();
  });

  it("keeps the form (no success copy) when rate limited", async () => {
    mockPost.mockResolvedValue(fail(429, "RATE_LIMITED") as never);
    renderWithProviders(<LoginPage />);

    await userEvent.type(screen.getByLabelText("Email"), "user@example.com");
    await userEvent.click(
      screen.getByRole("button", { name: "Send sign-in link" }),
    );

    await waitFor(() => expect(mockPost).toHaveBeenCalled());
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Email")).toBeInTheDocument();
  });
});
