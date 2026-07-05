import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fail, ok, renderWithProviders } from "@/lib/test/render";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/invitations/tok-1",
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ token: "tok-1" }),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import InvitationPage from "../invitations/[token]/page";
import StopRemindersPage from "../invitations/[token]/stop-reminders/page";

const mockGet = vi.mocked(api.GET);
const mockPost = vi.mocked(api.POST);

const PREVIEW = {
  requesterName: "Anna S",
  purpose: "Hiring",
  templateName: "Employment Reference",
  recommenderEmailMasked: "d***@techflow.io",
  status: "SENT",
};

beforeEach(() => {
  mockGet.mockReset();
  mockPost.mockReset();
  replace.mockReset();
});

describe("InvitationPage", () => {
  it("walks open → code request → confirm → /respond", async () => {
    mockGet.mockResolvedValue(ok(PREVIEW) as never);
    mockPost.mockImplementation(async (path: string) => {
      if (path.endsWith("email-confirmations"))
        return ok(undefined, 202) as never;
      if (path.endsWith("confirm-email"))
        return ok({ status: "CONFIRMED" }) as never;
      throw new Error(`unexpected POST ${path}`);
    });
    renderWithProviders(<InvitationPage />);

    expect(
      await screen.findByText(/Anna S asks you for a professional reference/),
    ).toBeInTheDocument();

    await userEvent.click(
      screen.getByRole("button", { name: "Email me a confirmation code" }),
    );
    const codeInput = await screen.findByLabelText("Confirmation code");

    const cont = screen.getByRole("button", { name: "Continue" });
    expect(cont).toBeDisabled();
    await userEvent.type(codeInput, "123456");
    expect(cont).toBeEnabled();
    await userEvent.click(cont);

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith(
        "/api/v1/invitations/{token}/confirm-email",
        { params: { path: { token: "tok-1" } }, body: { code: "123456" } },
      ),
    );
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/respond"));
  });

  it("shows one neutral invalid state on 404 (no state oracle)", async () => {
    mockGet.mockResolvedValue(fail(404, "NOT_FOUND") as never);
    renderWithProviders(<InvitationPage />);

    expect(
      await screen.findByText(/invalid or has expired/i),
    ).toBeInTheDocument();
    expect(mockPost).not.toHaveBeenCalled();
  });
});

describe("One-click stop-reminders", () => {
  it("fires only on the confirm click and is shown as done", async () => {
    mockPost.mockResolvedValue(ok({}) as never);
    renderWithProviders(<StopRemindersPage />);

    // Never auto-fires on page open (mail scanners prefetch links).
    expect(mockPost).not.toHaveBeenCalled();

    await userEvent.click(
      screen.getByRole("button", { name: "Stop reminders" }),
    );
    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith(
        "/api/v1/invitations/{token}/stop-reminders",
        { params: { path: { token: "tok-1" } } },
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent(
      /Reminders are stopped/,
    );
  });
});
