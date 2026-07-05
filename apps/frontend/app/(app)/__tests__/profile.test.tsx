import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ok, renderWithProviders } from "@/lib/test/render";

const refresh = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh }),
  usePathname: () => "/profile",
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import ProfilePage from "../profile/page";

const mockGet = vi.mocked(api.GET);
const mockPut = vi.mocked(api.PUT);

const PROFILE = {
  profileId: "p1",
  displayName: "Anna S",
  legalName: null,
  preferredLocale: "en",
  profileVerificationStatus: "UNVERIFIED",
};

beforeEach(() => {
  mockGet.mockReset();
  mockPut.mockReset();
  refresh.mockReset();
  mockGet.mockResolvedValue(ok(PROFILE) as never);
});

describe("ProfilePage", () => {
  it("loads and saves the profile, syncing the UI locale", async () => {
    mockPut.mockResolvedValue(
      ok({ ...PROFILE, displayName: "Anna Smirnova", preferredLocale: "ru" }) as never,
    );
    renderWithProviders(<ProfilePage />);

    const nameInput = await screen.findByLabelText("Display name");
    expect(nameInput).toHaveValue("Anna S");

    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, "Anna Smirnova");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(mockPut).toHaveBeenCalledWith("/api/v1/profile", {
        body: {
          displayName: "Anna Smirnova",
          legalName: null,
          preferredLocale: "en",
        },
      }),
    );
    // Saving refreshes so the server picks up the (possibly changed) locale.
    await waitFor(() => expect(refresh).toHaveBeenCalled());
  });

  it("requires a display name", async () => {
    renderWithProviders(<ProfilePage />);
    const nameInput = await screen.findByLabelText("Display name");

    await userEvent.clear(nameInput);
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText("Enter a display name")).toBeInTheDocument();
    expect(mockPut).not.toHaveBeenCalled();
  });
});
