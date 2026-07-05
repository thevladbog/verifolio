import { getTranslations } from "next-intl/server";

import { VerifolioWordmark } from "@/components/verifolio/wordmark";

export default async function VerifyNotFound() {
  const t = await getTranslations();
  return (
    <main className="mx-auto flex w-full max-w-md flex-1 flex-col items-center justify-center gap-6 px-4 py-16 text-center">
      <VerifolioWordmark />
      <h1 className="text-xl font-extrabold text-ink">
        {t("verify.invalidTitle")}
      </h1>
      <p className="text-sm text-slate-text">{t("verify.invalidBody")}</p>
    </main>
  );
}
