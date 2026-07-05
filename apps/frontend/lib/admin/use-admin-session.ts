"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { api } from "@/lib/api/client";
import { RequestError, unwrap } from "@/lib/query-provider";

/**
 * The authenticated admin identity from `GET /api/v1/admin/me`.
 *
 * The admin session cookie is HttpOnly (not readable by JS), so presence is
 * verified server-side by hitting `/admin/me`. A 401 means "not an admin
 * session" — admin has its own login (`/admin` is a PUBLIC_PREFIX, so the global
 * handler does NOT redirect to the user /login), so we redirect to /admin/login
 * here at the page level instead.
 */
export function useAdminSession() {
  const router = useRouter();
  const query = useQuery({
    queryKey: ["admin-session"],
    queryFn: async () => unwrap(await api.GET("/api/v1/admin/me")),
    retry: false,
    staleTime: 5 * 60_000,
  });

  const is401 =
    query.error instanceof RequestError && query.error.status === 401;

  useEffect(() => {
    if (is401) {
      router.replace("/admin/login");
    }
  }, [is401, router]);

  return {
    admin: query.data,
    isLoading: query.isLoading,
    // A 401 redirects to /admin/login above, so it's not a surfaced error. Any
    // OTHER failure (500, network) must be surfaced so pages that gate on
    // `isLoading || !admin` render an error+retry instead of spinning forever.
    isError: query.isError && !is401,
    error: query.error,
    refetch: query.refetch,
  };
}
