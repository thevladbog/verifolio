"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { z } from "zod";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { errorMessage } from "@/lib/api/errors";
import { RequestError } from "@/lib/query-provider";

/** The shared 6-digit TOTP entry used by both enroll and challenge (design 5a). */
export function MfaCodeForm({
  label,
  submitLabel,
  pending,
  error,
  onSubmit,
}: {
  label: string;
  submitLabel: string;
  pending: boolean;
  error: unknown;
  onSubmit: (code: string) => void;
}) {
  const t = useTranslations();

  const schema = z.object({
    code: z
      .string()
      .regex(/^\d{6}$/, t("admin.mfa.codeFormat")),
  });
  type FormValues = z.infer<typeof schema>;

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { code: "" },
  });

  const inlineError =
    error instanceof RequestError ? errorMessage(error.body, t) : undefined;

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={form.handleSubmit((values) => onSubmit(values.code))}
      noValidate
    >
      <div className="flex flex-col gap-2">
        <Label htmlFor="code" className="text-paper">
          {label}
        </Label>
        <Input
          id="code"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          placeholder="000000"
          className="border-navy bg-ink text-center text-lg tracking-[0.4em] text-paper placeholder:text-blue-gray/50"
          {...form.register("code")}
        />
        {form.formState.errors.code && (
          <p className="text-sm text-warning">
            {form.formState.errors.code.message}
          </p>
        )}
        {inlineError && (
          <p role="alert" className="text-sm text-warning">
            {inlineError}
          </p>
        )}
      </div>
      <Button type="submit" variant="secondary" disabled={pending}>
        {submitLabel}
      </Button>
    </form>
  );
}
