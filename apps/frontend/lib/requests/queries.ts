"use client";

import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";
import type { components } from "@/lib/api/schema";

export type ReferenceRequest =
  components["schemas"]["ReferenceRequestResponse"];
export type Template = components["schemas"]["TemplateListItem"];
export type OrganizationView = components["schemas"]["OrganizationView"];

/** Derive the email domain (lowercased) or undefined when not a usable address. */
export function emailDomain(email: string | undefined): string | undefined {
  const at = email?.lastIndexOf("@") ?? -1;
  if (!email || at < 0) return undefined;
  const domain = email.slice(at + 1).trim().toLowerCase();
  return domain.includes(".") ? domain : undefined;
}

/**
 * Subtle, non-blocking hint: does a VERIFIED org own this recommender's domain?
 * 200 → OrganizationView; 404 (no verified owner) → null. Enabled only when a
 * domain is present so free-email contacts never fire a request.
 */
export function useOrganizationLookup(domain: string | undefined) {
  return useQuery({
    queryKey: ["organizations", "lookup", domain],
    enabled: !!domain,
    staleTime: 10 * 60_000,
    retry: false,
    queryFn: async (): Promise<OrganizationView | null> => {
      const { data } = await api.GET("/api/v1/organizations/lookup", {
        params: { query: { domain: domain! } },
      });
      return data ?? null;
    },
  });
}

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
