// Shared locale list — importable from client components (unlike ./request.ts,
// which pulls in next/headers and is server-only).
export const LOCALES = ["en", "ru"] as const;
export type Locale = (typeof LOCALES)[number];
