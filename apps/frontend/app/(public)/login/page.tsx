"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { useForm } from "react-hook-form";
import { z } from "zod";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";
import { VerifolioWordmark } from "@/components/verifolio/wordmark";

export default function LoginPage() {
  const t = useTranslations();

  const schema = z.object({
    email: z.string().email(t("login.invalidEmail")),
  });
  type FormValues = z.infer<typeof schema>;

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: "" },
  });

  const requestLink = useMutation({
    mutationFn: async (values: FormValues) =>
      unwrap(await api.POST("/api/v1/auth/magic-links", { body: values })),
  });

  return (
    <main className="flex flex-1 flex-col items-center justify-center gap-8 px-4 py-16">
      <Link href="/">
        <VerifolioWordmark />
      </Link>
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle className="text-xl">{t("login.title")}</CardTitle>
        </CardHeader>
        <CardContent>
          {requestLink.isSuccess ? (
            <p role="status" className="text-sm text-slate-text">
              {t("login.sent")}
            </p>
          ) : (
            <form
              className="flex flex-col gap-4"
              onSubmit={form.handleSubmit((values) => requestLink.mutate(values))}
              noValidate
            >
              <div className="flex flex-col gap-2">
                <Label htmlFor="email">{t("login.emailLabel")}</Label>
                <Input
                  id="email"
                  type="email"
                  autoComplete="email"
                  placeholder={t("login.emailPlaceholder")}
                  {...form.register("email")}
                />
                {form.formState.errors.email && (
                  <p className="text-sm text-danger">
                    {form.formState.errors.email.message}
                  </p>
                )}
              </div>
              <Button type="submit" disabled={requestLink.isPending}>
                {t("login.submit")}
              </Button>
            </form>
          )}
        </CardContent>
      </Card>
    </main>
  );
}
