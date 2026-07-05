import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ok, renderWithProviders } from "@/lib/test/render";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/respond",
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({}),
}));

vi.mock("@/lib/api/client", () => ({
  api: { POST: vi.fn(), GET: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import RespondPage from "../respond/page";

const mockGet = vi.mocked(api.GET);
const mockPost = vi.mocked(api.POST);
const mockPut = vi.mocked(api.PUT);

const QUESTIONS = {
  recommenderQuestions: [
    { key: "relationship", label: "What was your relationship?", required: true },
    { key: "strengths", label: "What strengths?", required: false },
  ],
};

function stubContext(status: string, draft: unknown = null) {
  mockGet.mockImplementation(async (path: string) => {
    if (path === "/api/v1/recommender/request")
      return ok({
        status,
        requesterName: "Anna S",
        purpose: "Hiring",
        templateName: "Employment Reference",
        questionSchema: QUESTIONS,
        consents: {
          processing: { textId: "local-processing", version: 1 },
          crossBorderTransfer: { textId: "local-cross-border", version: 1 },
        },
        draft,
      }) as never;
    if (path === "/api/v1/recommender/uploads")
      return ok({ items: [] }) as never;
    throw new Error(`unexpected GET ${path}`);
  });
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  mockGet.mockReset();
  mockPost.mockReset();
  mockPut.mockReset();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("RespondPage consent gate", () => {
  it("renders NO answer inputs before consent is accepted", async () => {
    stubContext("OPENED");
    renderWithProviders(<RespondPage />);

    expect(
      await screen.findByRole("button", { name: "I agree — start" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(
      screen.queryByText("What was your relationship?"),
    ).not.toBeInTheDocument();
  });

  it("declining records the decision and shows the terminal state", async () => {
    stubContext("OPENED");
    mockPost.mockResolvedValue(ok({}) as never);
    renderWithProviders(<RespondPage />);

    await userEvent.click(
      await screen.findByRole("button", { name: "Decline" }),
    );

    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith("/api/v1/recommender/consent", {
        body: { accepted: false, crossBorderAccepted: null },
      }),
    );
    expect(await screen.findByText(/You declined/)).toBeInTheDocument();
  });

  it("requires the cross-border checkbox when the jurisdiction differs", async () => {
    stubContext("OPENED");
    renderWithProviders(<RespondPage />);

    const accept = await screen.findByRole("button", {
      name: "I agree — start",
    });
    expect(accept).toBeEnabled();

    await userEvent.click(
      screen.getByRole("checkbox", { name: /different jurisdiction/i }),
    );
    expect(accept).toBeDisabled();

    await userEvent.click(
      screen.getByRole("checkbox", { name: /cross-border transfer/i }),
    );
    expect(accept).toBeEnabled();
  });
});

describe("RespondPage form", () => {
  it("autosaves the draft after a 2s debounce and shows Saved", async () => {
    stubContext("IN_PROGRESS");
    mockPut.mockResolvedValue(ok({}) as never);
    renderWithProviders(<RespondPage />);

    const input = await screen.findByLabelText(/What was your relationship/);
    await userEvent.type(input, "Manager");

    expect(mockPut).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(2_100);

    await waitFor(() =>
      expect(mockPut).toHaveBeenCalledWith("/api/v1/recommender/response-draft", {
        body: {
          answersJson: { relationship: "Manager" },
          approvedLetterText: null,
        },
      }),
    );
    // Debounce collapses keystrokes into one save.
    expect(mockPut).toHaveBeenCalledTimes(1);
    expect(await screen.findByText("Saved")).toBeInTheDocument();
  });

  it("hydrates the form from a server draft", async () => {
    stubContext("IN_PROGRESS", {
      answersJson: { relationship: "Colleague" },
      approvedLetterText: "Dear team,",
      updatedAt: "2026-07-05T10:00:00Z",
    });
    renderWithProviders(<RespondPage />);

    expect(
      await screen.findByDisplayValue("Colleague"),
    ).toBeInTheDocument();
    expect(screen.getByDisplayValue("Dear team,")).toBeInTheDocument();
  });

  it("gates submit on required answers and both confirmations", async () => {
    stubContext("IN_PROGRESS");
    mockPost.mockResolvedValue(ok({}) as never);
    renderWithProviders(<RespondPage />);

    const submit = await screen.findByRole("button", {
      name: "Submit response",
    });
    expect(submit).toBeDisabled();

    await userEvent.type(
      screen.getByLabelText(/What was your relationship/),
      "Manager",
    );
    expect(submit).toBeDisabled();

    await userEvent.click(
      screen.getByRole("checkbox", { name: /reference is for/i }),
    );
    await userEvent.click(
      screen.getByRole("checkbox", { name: /professional relationship/i }),
    );
    expect(submit).toBeEnabled();

    await userEvent.click(submit);
    await waitFor(() =>
      expect(mockPost).toHaveBeenCalledWith("/api/v1/recommender/responses", {
        body: {
          answersJson: { relationship: "Manager" },
          approvedLetterText: null,
          recipientConfirmed: true,
          relationshipConfirmed: true,
          confirmationText: null,
        },
      }),
    );
    expect(await screen.findByText(/response is submitted/i)).toBeInTheDocument();
  });
});
