import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { VerifyContent } from "@/components/verify/verify-content";
import type { components } from "@/lib/api/schema";

type PageData = components["schemas"]["VerificationPageResponse"];

export const metadata: Metadata = {
  // No personal data in metadata.
  title: "Verified document — Verifolio",
  description: "Verification evidence for a shared professional document.",
};

async function fetchPage(token: string): Promise<PageData | null> {
  const base = process.env.BACKEND_INTERNAL_URL ?? "http://localhost:8080";
  try {
    const res = await fetch(
      `${base}/api/v1/verification-pages/${encodeURIComponent(token)}`,
      { cache: "no-store", signal: AbortSignal.timeout(10_000) },
    );
    if (!res.ok) return null;
    return (await res.json()) as PageData;
  } catch {
    // Hung or unreachable backend degrades to the same neutral invalid state.
    return null;
  }
}

export default async function VerifyPage({
  params,
}: {
  params: Promise<{ token: string }>;
}) {
  const { token } = await params;
  const page = await fetchPage(token);
  // One neutral state for unknown/revoked/expired — no oracle. Tombstoned pages
  // still resolve (200 with status TOMBSTONED) and render the "removed" state.
  if (!page) notFound();

  return <VerifyContent page={page} token={token} />;
}
