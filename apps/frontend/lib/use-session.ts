"use client";

import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";

export function useSession() {
  const query = useQuery({
    queryKey: ["session"],
    queryFn: async () => unwrap(await api.GET("/api/v1/auth/sessions/current")),
    staleTime: 5 * 60_000,
  });
  return { session: query.data, isLoading: query.isLoading, error: query.error };
}
