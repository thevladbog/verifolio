import { useTranslations } from "next-intl";
import Link from "next/link";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { VerifolioWordmark } from "@/components/verifolio/wordmark";

/** "region=url" pairs, comma-separated, e.g. "EU=https://app.eu.verifolio.com". */
function regionLinks(): Array<{ region: string; url: string }> {
  const raw = process.env.NEXT_PUBLIC_REGION_LINKS ?? "";
  return raw
    .split(",")
    .map((pair) => pair.split("="))
    .filter((parts): parts is [string, string] => parts.length === 2)
    .map(([region, url]) => ({ region, url }));
}

export default function LandingPage() {
  const t = useTranslations();
  const regions = regionLinks();

  return (
    <main className="flex flex-1 flex-col">
      <header className="flex items-center justify-between px-8 py-6">
        <VerifolioWordmark />
        <Button asChild variant="secondary">
          <Link href="/login">{t("landing.cta")}</Link>
        </Button>
      </header>

      <section className="mx-auto flex w-full max-w-3xl flex-1 flex-col items-center justify-center gap-6 px-8 py-24 text-center">
        <h1 className="text-4xl font-semibold leading-tight tracking-tight text-ink sm:text-5xl">
          {t("landing.headline")}
        </h1>
        <p className="max-w-xl text-lg text-slate-text">{t("landing.subline")}</p>
        <Button asChild size="lg">
          <Link href="/login">{t("landing.cta")}</Link>
        </Button>

        {regions.length > 0 && (
          <Card className="mt-12 w-full max-w-md">
            <CardContent className="flex flex-col gap-3 p-6">
              <p className="text-sm font-medium text-ink">{t("landing.chooseRegion")}</p>
              <div className="flex justify-center gap-3">
                {regions.map(({ region, url }) => (
                  <Button key={region} asChild variant="secondary">
                    <a href={url}>{region}</a>
                  </Button>
                ))}
              </div>
              <p className="text-xs text-muted-text">{t("landing.regionNote")}</p>
            </CardContent>
          </Card>
        )}
      </section>
    </main>
  );
}
