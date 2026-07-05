import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ok, renderWithProviders } from "@/lib/test/render";

const push = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push, refresh: vi.fn() }),
  usePathname: () => "/requests/new",
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import NewRequestPage from "../requests/new/page";

const mockGet = vi.mocked(api.GET);
const mockPost = vi.mocked(api.POST);

const TEMPLATE = {
  id: "tpl-1",
  type: "EMPLOYMENT_REFERENCE",
  locale: "en",
  name: "Employment Reference",
  description: "Role, period, results",
};

const CONTACT = {
  id: "c1",
  name: "Dmitry Orlov",
  email: "d@techflow.io",
  relationshipType: "MANAGER",
};

beforeEach(() => {
  mockGet.mockReset();
  mockPost.mockReset();
  push.mockReset();
  mockGet.mockImplementation(async (path: string) => {
    if (path === "/api/v1/templates")
      return ok({ items: [TEMPLATE] }) as never;
    if (path === "/api/v1/contacts")
      return ok({ items: [CONTACT], nextCursor: null }) as never;
    if (path === "/api/v1/consent-texts/{consentType}")
      return ok({
        consentType: "REQUESTER_VERBAL_CONSENT_ATTESTATION",
        textId: "local-requester-attestation",
        version: 1,
        locale: "en",
        title: "Verbal consent attestation",
        body: "I confirm the recommender gave me verbal consent to receive this request.\n\nThis attestation is recorded.",
      }) as never;
    throw new Error(`unexpected GET ${path}`);
  });
});

async function walkToConfirmStep() {
  renderWithProviders(<NewRequestPage />);
  // Step 1: template
  await userEvent.click(await screen.findByText("Employment Reference"));
  await userEvent.click(screen.getByRole("button", { name: "Continue" }));
  // Step 2: context (optional purpose)
  await userEvent.type(
    screen.getByLabelText("Purpose and context"),
    "International hiring",
  );
  await userEvent.click(screen.getByRole("button", { name: "Continue" }));
  // Step 3: recommender
  await userEvent.click(await screen.findByText("Dmitry Orlov"));
  await userEvent.click(screen.getByRole("button", { name: "Continue" }));
}

describe("NewRequestPage", () => {
  it("blocks Continue until a template is selected", async () => {
    renderWithProviders(<NewRequestPage />);
    await screen.findByText("Employment Reference");
    expect(screen.getByRole("button", { name: "Continue" })).toBeDisabled();
  });

  it("keeps Create disabled until the verbal-consent attestation is checked", async () => {
    await walkToConfirmStep();

    const createButton = screen.getByRole("button", { name: "Create request" });
    expect(createButton).toBeDisabled();
    await userEvent.click(createButton);
    expect(mockPost).not.toHaveBeenCalled();

    await userEvent.click(
      screen.getByRole("checkbox", {
        name: /verbal consent/i,
      }),
    );
    expect(createButton).toBeEnabled();
  });

  it("creates the request with the attestation and navigates to the detail", async () => {
    mockPost.mockResolvedValue(ok({ id: "req-1", status: "CREATED" }) as never);
    await walkToConfirmStep();

    await userEvent.click(
      screen.getByRole("checkbox", { name: /verbal consent/i }),
    );
    await userEvent.click(screen.getByRole("button", { name: "Create request" }));

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith("/api/v1/reference-requests", {
        body: {
          templateId: "tpl-1",
          recommenderContactId: "c1",
          purpose: "International hiring",
          verbalConsentAttested: true,
        },
      }),
    );
    await waitFor(() => expect(push).toHaveBeenCalledWith("/requests/req-1"));
  });
});
