"use client";

import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";
import type { components } from "@/lib/api/schema";

export type ReferenceRequest =
  components["schemas"]["ReferenceRequestResponse"];
export type Template = components["schemas"]["TemplateListItem"];

export function useTemplates(locale: string) {
  return useQuery({
    queryKey: ["templates", locale],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/v1/templates", {
          params: { query: { locale } },
        }),
      ),
    staleTime: 10 * 60_000,
  });
}

/**
 * The request DTO carries only recommenderContactId / templateId; screens
 * join names client-side from these cached lookups (no dedicated backend
 * aggregate in MVP).
 */
export function useContactNames() {
  return useQuery({
    queryKey: ["contacts", "names"],
    queryFn: async () => {
      const byId = new Map<string, { name: string; email: string }>();
      let cursor: string | undefined;
      // Names directory for list rendering; bounded to avoid unbounded walks.
      for (let page = 0; page < 4; page++) {
        const data = unwrap(
          await api.GET("/api/v1/contacts", {
            params: { query: cursor ? { cursor } : {} },
          }),
        );
        for (const c of data.items ?? []) {
          if (c.id) byId.set(c.id, { name: c.name ?? "", email: c.email ?? "" });
        }
        cursor = data.nextCursor ?? undefined;
        if (!cursor) break;
      }
      return byId;
    },
    staleTime: 60_000,
  });
}
