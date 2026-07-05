/** DSR request types (spec §DSR). Falls back to the raw code for unknowns. */
export const DSR_TYPES = [
  "DELETION",
  "EXPORT",
  "REGION_MIGRATION",
  "CONSENT_WITHDRAWAL",
  "CORRECTION",
] as const;

type Translator = (key: string) => string;

/** Localized label for a DSR type under `admin.types.*`. */
export function dsrTypeLabel(
  type: string | undefined,
  t: Translator,
): string {
  if (type && (DSR_TYPES as readonly string[]).includes(type)) {
    return t(`types.${type}`);
  }
  return type ?? "—";
}
