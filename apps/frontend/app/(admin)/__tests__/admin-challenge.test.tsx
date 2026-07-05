import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fail, ok, renderWithProviders } from "@/lib/test/render";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/admin/mfa/challenge",
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import AdminMfaChallengePage from "../admin/mfa/challenge/page";

const mockPost = vi.mocked(api.POST);

beforeEach(() => {
  mockPost.mockReset();
  replace.mockReset();
});

describe("AdminMfaChallengePage", () => {
  it("posts the code and redirects to the dashboard on success", async () => {
    mockPost.mockResolvedValue(ok({ ok: true }) as never);
    renderWithProviders(<AdminMfaChallengePage />);

    await userEvent.type(
      screen.getByLabelText("Authenticator code"),
      "654321",
    );
    await userEvent.click(screen.getByRole("button", { name: "Verify" }));

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith("/api/v1/admin/auth/mfa/verify", {
        body: { code: "654321" },
      }),
    );
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/admin"));
  });

  it("shows an inline CODE_INVALID message on a bad code", async () => {
    mockPost.mockResolvedValue(fail(400, "CODE_INVALID") as never);
    renderWithProviders(<AdminMfaChallengePage />);

    await userEvent.type(
      screen.getByLabelText("Authenticator code"),
      "000000",
    );
    await userEvent.click(screen.getByRole("button", { name: "Verify" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /that code is not valid/i,
    );
    expect(replace).not.toHaveBeenCalled();
  });
});
