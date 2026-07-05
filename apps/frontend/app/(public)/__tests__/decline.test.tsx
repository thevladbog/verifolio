import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

import { ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/invitations/tok-1/decline",
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ token: "tok-1" }),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import DeclinePage from "../invitations/[token]/decline/page";

const mockPost = vi.mocked(api.POST);

beforeAll(() => {
  // Radix Select needs pointer-capture and scroll APIs missing from jsdom.
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
  window.HTMLElement.prototype.hasPointerCapture = vi.fn(() => false);
  window.HTMLElement.prototype.releasePointerCapture = vi.fn();
});

beforeEach(() => {
  mockPost.mockReset();
  mockPost.mockResolvedValue(ok({}) as never);
});

describe("Decline one-click page", () => {
  it("declines without a body when no reason is chosen", async () => {
    renderWithProviders(<DeclinePage />);

    await userEvent.click(
      screen.getByRole("button", { name: "Decline request" }),
    );

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith(
        "/api/v1/invitations/{token}/decline",
        { params: { path: { token: "tok-1" } } },
      ),
    );
    expect(await screen.findByText(/request is declined/i)).toBeInTheDocument();
  });

  it("posts the chosen reason category", async () => {
    renderWithProviders(<DeclinePage />);

    await userEvent.click(
      screen.getByRole("combobox", {
        name: "Why are you declining? (optional)",
      }),
    );
    await userEvent.click(
      await screen.findByRole("option", { name: "I'm too busy right now" }),
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Decline request" }),
    );

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith(
        "/api/v1/invitations/{token}/decline",
        {
          params: { path: { token: "tok-1" } },
          body: { reasonCategory: "TOO_BUSY" },
        },
      ),
    );
  });

  it("keeps 'prefer not to say' as the default selection", () => {
    renderWithProviders(<DeclinePage />);

    expect(
      screen.getByRole("combobox", {
        name: "Why are you declining? (optional)",
      }),
    ).toHaveTextContent("Prefer not to say");
  });
});
