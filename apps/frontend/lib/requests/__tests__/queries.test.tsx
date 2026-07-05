import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { fail, ok } from "@/lib/test/render";

vi.mock("@/lib/api/client", () => ({
  api: { GET: vi.fn(), POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
}));

import { api } from "@/lib/api/client";
import { emailDomain, useOrganizationLookup } from "../queries";

const mockGet = vi.mocked(api.GET);

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => mockGet.mockReset());

describe("emailDomain", () => {
  it("derives a lowercased domain from an address", () => {
    expect(emailDomain("Jane@Acme.COM")).toBe("acme.com");
  });

  it("returns undefined for missing, malformed, or dot-less domains", () => {
    expect(emailDomain(undefined)).toBeUndefined();
    expect(emailDomain("no-at-sign")).toBeUndefined();
    expect(emailDomain("jane@localhost")).toBeUndefined();
  });
});

describe("useOrganizationLookup", () => {
  it("does not fire without a domain", () => {
    renderHook(() => useOrganizationLookup(undefined), { wrapper });
    expect(mockGet).not.toHaveBeenCalled();
  });

  it("returns the org view on 200 (hint shown)", async () => {
    mockGet.mockResolvedValue(
      ok({
        id: "org-1",
        name: "Acme Corp",
        domains: ["acme.com"],
        verificationStatus: "VERIFIED",
      }) as never,
    );

    const { result } = renderHook(() => useOrganizationLookup("acme.com"), {
      wrapper,
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.name).toBe("Acme Corp");
    expect(mockGet).toHaveBeenCalledWith("/api/v1/organizations/lookup", {
      params: { query: { domain: "acme.com" } },
    });
  });

  it("returns null on 404 (no hint shown)", async () => {
    mockGet.mockResolvedValue(fail(404, "NOT_FOUND") as never);

    const { result } = renderHook(() => useOrganizationLookup("unknown.com"), {
      wrapper,
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeNull();
  });
});
