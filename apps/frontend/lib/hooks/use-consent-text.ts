"use client";

import { useQuery } from "@tanstack/react-query";
import { useLocale } from "next-intl";

import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";

export type ConsentType =
  | "REQUESTER_VERBAL_CONSENT_ATTESTATION"
  | "RECOMMENDER_PROCESSING_CONSENT"
  | "CROSS_BORDER_TRANSFER_CONSENT"
  | "RECOMMENDER_PUBLIC_SHARING_CONSENT";

/** Markdown-ish plain text → paragraphs (no markdown rendering by design). */
export function consentParagraphs(body: string | undefined): string[] {
  return (body ?? "")
    .split(/\n\s*\n/)
    .map((p) => p.trim())
    .filter(Boolean);
}

/**
 * Backend-served consent copy — the backend records `policy_text_version`
 * against exactly this text, so it must never be hardcoded in the frontend.
 * Static policy content: cache for the session.
 */
export function useConsentText(consentType: ConsentType) {
  const locale = useLocale();
  return useQuery({
    queryKey: ["consent-texts", consentType, locale],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/v1/consent-texts/{consentType}", {
          params: { path: { consentType }, query: { locale } },
        }),
      ),
    staleTime: Infinity,
  });
}
