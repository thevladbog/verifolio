"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";

const LOCALES = ["en", "ru"] as const;

function persistLocale(next: string) {
  document.cookie = `NEXT_LOCALE=${next}; path=/; max-age=31536000; samesite=lax`;
}

export default function ProfilePage() {
  const t = useTranslations();
  const router = useRouter();
  const queryClient = useQueryClient();

  const profile = useQuery({
    queryKey: ["profile"],
    queryFn: async () => unwrap(await api.GET("/api/v1/profile")),
  });

  const schema = z.object({
    displayName: z.string().min(1, t("profile.displayNameRequired")),
    legalName: z.string().optional(),
    preferredLocale: z.enum(LOCALES),
  });
  type FormValues = z.infer<typeof schema>;

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    values: {
      displayName: profile.data?.displayName ?? "",
      legalName: profile.data?.legalName ?? "",
      preferredLocale:
        (profile.data?.preferredLocale as FormValues["preferredLocale"]) ?? "en",
    },
  });

  const save = useMutation({
    mutationFn: async (values: FormValues) =>
      unwrap(
        await api.PUT("/api/v1/profile", {
          body: {
            displayName: values.displayName,
            legalName: values.legalName || null,
            preferredLocale: values.preferredLocale,
          },
        }),
      ),
    onSuccess: (data) => {
      queryClient.setQueryData(["profile"], data);
      // The profile locale is also the UI locale.
      persistLocale(data.preferredLocale ?? "en");
      router.refresh();
      toast.success(t("common.saved"));
    },
  });

  if (profile.isLoading) {
    return <Skeleton className="h-64 max-w-xl" />;
  }

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-2xl font-extrabold text-ink">{t("profile.title")}</h1>
      <Card className="max-w-xl">
        <CardHeader>
          <CardTitle>{t("profile.sectionTitle")}</CardTitle>
        </CardHeader>
        <CardContent>
          <form
            className="flex flex-col gap-4"
            onSubmit={form.handleSubmit((values) => save.mutate(values))}
            noValidate
          >
            <div className="flex flex-col gap-2">
              <Label htmlFor="displayName">{t("profile.displayName")}</Label>
              <Input id="displayName" {...form.register("displayName")} />
              {form.formState.errors.displayName && (
                <p className="text-sm text-danger">
                  {form.formState.errors.displayName.message}
                </p>
              )}
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="legalName">{t("profile.legalName")}</Label>
              <Input id="legalName" {...form.register("legalName")} />
              <p className="text-xs text-muted-text">
                {t("profile.legalNameHint")}
              </p>
            </div>
            <div className="flex flex-col gap-2">
              <Label>{t("profile.locale")}</Label>
              <Select
                value={form.watch("preferredLocale")}
                onValueChange={(v) =>
                  form.setValue(
                    "preferredLocale",
                    v as FormValues["preferredLocale"],
                  )
                }
              >
                <SelectTrigger
                  className="w-40"
                  aria-label={t("profile.locale")}
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {LOCALES.map((l) => (
                    <SelectItem key={l} value={l}>
                      {t(`profile.locales.${l}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <Button
              type="submit"
              disabled={save.isPending}
              className="self-start"
            >
              {t("common.save")}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
