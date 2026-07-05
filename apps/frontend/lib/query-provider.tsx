"use client";

import {
  MutationCache,
  QueryCache,
  QueryClient,
  QueryClientProvider,
} from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";

import { errorMessage } from "@/lib/api/errors";
import type { ApiError } from "@/lib/api/client";

/** Thrown by query/mutation functions when the backend returns an ApiError body. */
export class RequestError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: ApiError | undefined,
  ) {
    super(body?.message ?? `HTTP ${status}`);
  }
}

/** Route prefixes with their own session/error semantics — no login redirect. */
const PUBLIC_PREFIXES = [
  "/verify",
  "/invitations",
  "/respond",
  "/login",
  "/auth",
  "/data-requests",
  // Admin has its own login; a 401 here must never bounce to the USER /login.
  // Authenticated admin pages guard themselves via /admin/me (see lib/admin/use-admin-session).
  "/admin",
];

export function QueryProvider({ children }: { children: React.ReactNode }) {
  const t = useTranslations();
  const router = useRouter();

  const [client] = useState(() => {
    const onError = (error: unknown, suppressToast = false) => {
      if (error instanceof RequestError && error.status === 401) {
        // Read the location at error time: this closure lives for the whole
        // session, so a hook-captured pathname would go stale after navigation.
        const current = window.location.pathname;
        if (!PUBLIC_PREFIXES.some((p) => current.startsWith(p))) {
          router.replace("/login");
          return;
        }
      }
      // A mutation that renders its own inline error opts out of the global toast.
      if (suppressToast) return;
      if (error instanceof RequestError) {
        toast.error(errorMessage(error.body, t));
      } else {
        toast.error(t("errors.UNKNOWN", { code: "NETWORK" }));
      }
    };

    return new QueryClient({
      queryCache: new QueryCache({ onError: (error) => onError(error) }),
      mutationCache: new MutationCache({
        onError: (error, _vars, _ctx, mutation) =>
          onError(error, mutation.meta?.inlineError === true),
      }),
      defaultOptions: {
        queries: {
          staleTime: 30_000,
          retry: (failureCount, error) => {
            if (error instanceof RequestError && error.status < 500) return false;
            return failureCount < 2;
          },
        },
      },
    });
  });

  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

/** Unwraps an openapi-fetch result into data-or-throw for TanStack Query. */
export function unwrap<T>(result: {
  data?: T;
  error?: ApiError;
  response: Response;
}): T {
  if (result.error !== undefined || !result.response.ok) {
    throw new RequestError(result.response.status, result.error);
  }
  return result.data as T;
}
