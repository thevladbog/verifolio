import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fail, ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/contacts",
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import ContactsPage from "../contacts/page";

const mockGet = vi.mocked(api.GET);
const mockPost = vi.mocked(api.POST);
const mockDelete = vi.mocked(api.DELETE);

const CONTACT = {
  id: "c1",
  name: "Dmitry Orlov",
  email: "d@techflow.io",
  companyName: "TechFlow",
  companyDomain: null,
  title: "CTO",
  relationshipType: "MANAGER",
  createdAt: "2026-07-01T10:00:00Z",
};

beforeEach(() => {
  mockGet.mockReset();
  mockPost.mockReset();
  mockDelete.mockReset();
  mockGet.mockResolvedValue(
    ok({ items: [CONTACT], nextCursor: "cur-2" }) as never,
  );
});

describe("ContactsPage", () => {
  it("renders contacts and offers Load more when a cursor is present", async () => {
    renderWithProviders(<ContactsPage />);

    expect(await screen.findByText("Dmitry Orlov")).toBeInTheDocument();
    expect(screen.getByText("TechFlow · CTO")).toBeInTheDocument();
    expect(screen.getByText("Manager")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Load more" })).toBeInTheDocument();
  });

  it("loads the next page with the cursor and appends items", async () => {
    renderWithProviders(<ContactsPage />);
    await screen.findByText("Dmitry Orlov");

    mockGet.mockResolvedValueOnce(
      ok({
        items: [{ ...CONTACT, id: "c2", name: "Maria Kim" }],
        nextCursor: null,
      }) as never,
    );
    await userEvent.click(screen.getByRole("button", { name: "Load more" }));

    expect(await screen.findByText("Maria Kim")).toBeInTheDocument();
    expect(screen.getByText("Dmitry Orlov")).toBeInTheDocument();
    expect(mockGet).toHaveBeenLastCalledWith("/api/v1/contacts", {
      params: { query: { cursor: "cur-2" } },
    });
    expect(
      screen.queryByRole("button", { name: "Load more" }),
    ).not.toBeInTheDocument();
  });

  it("creates a contact through the dialog", async () => {
    mockPost.mockResolvedValue(ok({ ...CONTACT, id: "c3" }) as never);
    renderWithProviders(<ContactsPage />);
    await screen.findByText("Dmitry Orlov");

    await userEvent.click(
      screen.getAllByRole("button", { name: /Add contact/ })[0],
    );
    await userEvent.type(screen.getByLabelText("Name"), "Elena M");
    await userEvent.type(screen.getByLabelText("Email"), "e@nordwind.eu");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith("/api/v1/contacts", {
        body: {
          name: "Elena M",
          email: "e@nordwind.eu",
          companyName: null,
          title: null,
          relationshipType: "COLLEAGUE",
        },
      }),
    );
  });

  it("requires a name before creating", async () => {
    renderWithProviders(<ContactsPage />);
    await screen.findByText("Dmitry Orlov");

    await userEvent.click(
      screen.getAllByRole("button", { name: /Add contact/ })[0],
    );
    await userEvent.type(screen.getByLabelText("Email"), "e@nordwind.eu");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(
      await screen.findByText("Enter the contact's name"),
    ).toBeInTheDocument();
    expect(mockPost).not.toHaveBeenCalled();
  });

  it("deletes a contact (409 CONTACT_IN_USE handled by the error layer)", async () => {
    mockDelete.mockResolvedValue(fail(409, "CONTACT_IN_USE") as never);
    renderWithProviders(<ContactsPage />);
    await screen.findByText("Dmitry Orlov");

    await userEvent.click(screen.getByRole("button", { name: "Delete" }));
    // Destructive action requires an explicit confirmation step.
    expect(await screen.findByText("Delete contact?")).toBeInTheDocument();
    expect(mockDelete).not.toHaveBeenCalled();

    const dialog = screen.getByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Delete" }));
    await waitFor(() =>
      expect(mockDelete).toHaveBeenCalledWith("/api/v1/contacts/{id}", {
        params: { path: { id: "c1" } },
      }),
    );
    // The row stays — deletion failed.
    expect(screen.getByText("Dmitry Orlov")).toBeInTheDocument();
  });
});
