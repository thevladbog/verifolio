import createClient from "openapi-fetch";

import type { components, paths } from "./schema";

export type ApiError = components["schemas"]["ApiError"];

export function readCookie(name: string): string | undefined {
  if (typeof document === "undefined") return undefined;
  const match = document.cookie.match(
    new RegExp(`(?:^|;\\s*)${name}=([^;]+)`),
  );
  return match ? decodeURIComponent(match[1]) : undefined;
}

export function createApi(baseUrl: string, fetchImpl?: typeof fetch) {
  const client = createClient<paths>({ baseUrl, fetch: fetchImpl });
  client.use({
    onRequest({ request }) {
      if (!["GET", "HEAD"].includes(request.method)) {
        const token = readCookie("XSRF-TOKEN");
        if (token) request.headers.set("X-XSRF-TOKEN", token);
      }
      return request;
    },
  });
  return client;
}

export const api = createApi("/");
