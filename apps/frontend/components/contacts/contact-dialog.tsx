"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { z } from "zod";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { api } from "@/lib/api/client";
import { unwrap } from "@/lib/query-provider";
import type { components } from "@/lib/api/schema";

export type Contact = components["schemas"]["ContactResponse"];

export const RELATIONSHIP_TYPES = [
  "MANAGER",
  "COLLEAGUE",
  "DIRECT_REPORT",
  "CLIENT",
  "PROFESSOR",
  "MENTOR",
  "PERSONAL",
  "OTHER",
] as const;

export function ContactDialog({
  open,
  onOpenChange,
  contact,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  contact?: Contact;
}) {
  const t = useTranslations();
  const queryClient = useQueryClient();

  const schema = z.object({
    name: z.string().min(1, t("contacts.nameRequired")),
    email: z.string().email(t("login.invalidEmail")),
    companyName: z.string().optional(),
    title: z.string().optional(),
    relationshipType: z.enum(RELATIONSHIP_TYPES),
  });
  type FormValues = z.infer<typeof schema>;

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    values: {
      name: contact?.name ?? "",
      email: contact?.email ?? "",
      companyName: contact?.companyName ?? "",
      title: contact?.title ?? "",
      relationshipType:
        (contact?.relationshipType as FormValues["relationshipType"]) ??
        "COLLEAGUE",
    },
  });

  const save = useMutation({
    mutationFn: async (values: FormValues) => {
      const body = {
        name: values.name,
        email: values.email,
        companyName: values.companyName || null,
        title: values.title || null,
        relationshipType: values.relationshipType,
      };
      if (contact) {
        return unwrap(
          await api.PUT("/api/v1/contacts/{id}", {
            params: { path: { id: contact.id! } },
            body,
          }),
        );
      }
      return unwrap(await api.POST("/api/v1/contacts", { body }));
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["contacts"] });
      onOpenChange(false);
      form.reset();
    },
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {contact ? t("contacts.editTitle") : t("contacts.addTitle")}
          </DialogTitle>
        </DialogHeader>
        <form
          className="flex flex-col gap-4"
          onSubmit={form.handleSubmit((values) => save.mutate(values))}
          noValidate
        >
          <div className="flex flex-col gap-2">
            <Label htmlFor="contact-name">{t("contacts.name")}</Label>
            <Input id="contact-name" {...form.register("name")} />
            {form.formState.errors.name && (
              <p className="text-sm text-danger">
                {form.formState.errors.name.message}
              </p>
            )}
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="contact-email">{t("login.emailLabel")}</Label>
            <Input id="contact-email" type="email" {...form.register("email")} />
            {form.formState.errors.email && (
              <p className="text-sm text-danger">
                {form.formState.errors.email.message}
              </p>
            )}
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="contact-company">{t("contacts.company")}</Label>
              <Input id="contact-company" {...form.register("companyName")} />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="contact-title">{t("contacts.jobTitle")}</Label>
              <Input id="contact-title" {...form.register("title")} />
            </div>
          </div>
          <div className="flex flex-col gap-2">
            <Label>{t("contacts.relationship")}</Label>
            <Select
              value={form.watch("relationshipType")}
              onValueChange={(v) =>
                form.setValue(
                  "relationshipType",
                  v as FormValues["relationshipType"],
                )
              }
            >
              <SelectTrigger aria-label={t("contacts.relationship")}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {RELATIONSHIP_TYPES.map((r) => (
                  <SelectItem key={r} value={r}>
                    {t(`contacts.relationshipTypes.${r}`)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="secondary"
              onClick={() => onOpenChange(false)}
            >
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={save.isPending}>
              {t("common.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
