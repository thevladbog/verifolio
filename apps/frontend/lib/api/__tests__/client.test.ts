import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createApi } from "../client";
import { errorMessage } from "../errors";

const received: Array<{ method: string; csrf: string | null }> = [];

beforeEach(() => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: Request) => {
      received.push({
        method: input.method,
        csrf: input.headers.get("X-XSRF-TOKEN"),
      });
      return new Response(JSON.stringify({ items: [] }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  received.length = 0;
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("api client CSRF middleware", () => {
  const api = createApi("http://localhost/", (...args: Parameters<typeof fetch>) =>
    globalThis.fetch(...args),
  );

  it("attaches X-XSRF-TOKEN from the cookie on mutating requests", async () => {
    document.cookie = "XSRF-TOKEN=abc%2F123";
    await api.POST("/api/v1/auth/magic-links", {
      body: { email: "user@example.com" },
    });
    expect(received).toEqual([{ method: "POST", csrf: "abc/123" }]);
  });

  it("does not attach the header on GET requests", async () => {
    document.cookie = "XSRF-TOKEN=abc123";
    await api.GET("/api/v1/templates", {
      params: { query: { locale: "en" } },
    });
    expect(received).toEqual([{ method: "GET", csrf: null }]);
  });

  it("omits the header when no CSRF cookie is present", async () => {
    await api.POST("/api/v1/auth/magic-links", {
      body: { email: "user@example.com" },
    });
    expect(received).toEqual([{ method: "POST", csrf: null }]);
  });
});

describe("errorMessage", () => {
  const t = vi.fn(
    (key: string, values?: Record<string, string>) =>
      `${key}${values?.code ? `:${values.code}` : ""}`,
  );

  it("maps known codes to their i18n key", () => {
    expect(
      errorMessage({ code: "RATE_LIMITED", message: "x" }, t),
    ).toBe("errors.RATE_LIMITED");
  });

  it("falls back to UNKNOWN with the raw code", () => {
    expect(errorMessage({ code: "WEIRD_CODE", message: "x" }, t)).toBe(
      "errors.UNKNOWN:WEIRD_CODE",
    );
  });

  it("handles missing error bodies", () => {
    expect(errorMessage(undefined, t)).toBe("errors.UNKNOWN:NETWORK");
  });
});
