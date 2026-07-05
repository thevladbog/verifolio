import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ok, renderWithProviders } from "@/lib/test/render";

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import { DsrCard } from "../dsr-card";

const mockGet = vi.mocked(api.GET);
const mockPost = vi.mocked(api.POST);

beforeEach(() => {
  mockGet.mockReset();
  mockPost.mockReset();
});

describe("DsrCard", () => {
  it("lists the owner's requests with status and due date", async () => {
    mockGet.mockResolvedValue(
      ok({
        items: [
          {
            id: "d1",
            type: "EXPORT",
            status: "RECEIVED",
            dueAt: "2026-08-01T00:00:00Z",
          },
        ],
        nextCursor: null,
      }) as never,
    );

    renderWithProviders(<DsrCard />);

    // "Received" only appears in the list badge (not among the type options).
    expect(await screen.findByText("Received")).toBeInTheDocument();
    expect(screen.getByText(/Due by/)).toBeInTheDocument();
  });

  it("submits a data subject request and refetches the list", async () => {
    mockGet.mockResolvedValue(ok({ items: [], nextCursor: null }) as never);
    mockPost.mockResolvedValue(
      ok({ id: "d2", type: "EXPORT", status: "RECEIVED" }) as never,
    );

    renderWithProviders(<DsrCard />);

    await screen.findByText("You have no data requests yet.");

    await userEvent.click(
      screen.getByRole("button", { name: "Submit request" }),
    );

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith(
        "/api/v1/privacy/data-subject-requests",
        { body: { type: "DELETION", comment: null } },
      ),
    );
  });
});
