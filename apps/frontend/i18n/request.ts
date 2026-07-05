import { getRequestConfig } from "next-intl/server";
import { cookies } from "next/headers";

import { LOCALES, type Locale } from "./locales";

export { LOCALES };
export type { Locale };

export default getRequestConfig(async () => {
  const store = await cookies();
  const candidate = store.get("NEXT_LOCALE")?.value;
  const locale: Locale = (LOCALES as readonly string[]).includes(candidate ?? "")
    ? (candidate as Locale)
    : "en";
  return {
    locale,
    messages: (await import(`../messages/${locale}.json`)).default,
  };
});
