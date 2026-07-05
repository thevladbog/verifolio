import type { ApiError } from "./client";

/** Error codes with a dedicated i18n message under `errors.<CODE>`. */
export const KNOWN_CODES = new Set([
  "RATE_LIMITED",
  "CONSENT_REQUIRED",
  "CODE_INVALID",
  "CONFIRMATION_REQUIRED",
  "CONTACT_IN_USE",
  "INVALID_REQUEST_STATE",
  "VALIDATION_ERROR",
  "NOT_FOUND",
  "FORBIDDEN",
  "UNAUTHORIZED",
  "PROFILE_ERROR",
  "EXECUTION_NOT_AUTOMATED",
]);

type Translator = (key: string, values?: Record<string, string>) => string;

export function errorMessage(
  error: ApiError | { code?: string } | undefined,
  t: Translator,
): string {
  const code = error?.code;
  if (code && KNOWN_CODES.has(code)) return t(`errors.${code}`);
  return t("errors.UNKNOWN", { code: code ?? "NETWORK" });
}
