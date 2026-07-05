"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { z } from "zod";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { AdminCard } from "@/components/admin/admin-card";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";

export default function AdminLoginPage() {
  const t = useTranslations("admin.login");

  const schema = z.object({ email: z.string().email(t("invalidEmail")) });
  type FormValues = z.infer<typeof schema>;

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: "" },
  });

  const requestLink = useMutation({
    mutationFn: async (values: FormValues) =>
      unwrap(await api.POST("/api/v1/admin/auth/magic-links", { body: values })),
    meta: { inlineError: true },
  });

  return (
    <div className="flex flex-1 flex-col items-center justify-center py-8">
      <AdminCard title={t("title")} description={t("subtitle")}>
        {requestLink.isSuccess ? (
          <p role="status" className="text-sm text-blue-gray">
            {t("sent")}
          </p>
        ) : (
          <form
            className="flex flex-col gap-4"
            onSubmit={form.handleSubmit((values) => requestLink.mutate(values))}
            noValidate
          >
            <div className="flex flex-col gap-2">
              <Label htmlFor="email" className="text-paper">
                {t("emailLabel")}
              </Label>
              <Input
                id="email"
                type="email"
                autoComplete="email"
                placeholder={t("emailPlaceholder")}
                className="border-navy bg-ink text-paper placeholder:text-blue-gray/60"
                {...form.register("email")}
              />
              {form.formState.errors.email && (
                <p className="text-sm text-warning">
                  {form.formState.errors.email.message}
                </p>
              )}
            </div>
            <Button
              type="submit"
              variant="secondary"
              disabled={requestLink.isPending}
            >
              {t("submit")}
            </Button>
          </form>
        )}
      </AdminCard>
    </div>
  );
}
