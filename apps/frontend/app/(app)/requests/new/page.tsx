"use client";

import { useMutation } from "@tanstack/react-query";
import {
  Briefcase,
  FileText,
  GraduationCap,
  Handshake,
  Plane,
  Stamp,
  UserRound,
  type LucideIcon,
} from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { ContactDialog } from "@/components/contacts/contact-dialog";
import { Stepper } from "@/components/requests/stepper";
import { api } from "@/lib/api/client";
import { consentParagraphs, useConsentText } from "@/lib/hooks/use-consent-text";
import { unwrap } from "@/lib/query-provider";
import { useContactNames, useTemplates } from "@/lib/requests/queries";
import { cn } from "@/lib/utils";

const TEMPLATE_ICONS: Record<string, LucideIcon> = {
  EMPLOYMENT_REFERENCE: Briefcase,
  IMMIGRATION_REFERENCE: Plane,
  VISA_SUPPORT_LETTER: Stamp,
  ACADEMIC_RECOMMENDATION: GraduationCap,
  CLIENT_TESTIMONIAL: Handshake,
  CHARACTER_REFERENCE: UserRound,
};

export default function NewRequestPage() {
  const t = useTranslations();
  const locale = useLocale();
  const router = useRouter();

  const [step, setStep] = useState(0);
  const [templateId, setTemplateId] = useState<string>();
  const [purpose, setPurpose] = useState("");
  const [contactId, setContactId] = useState<string>();
  const [attested, setAttested] = useState(false);
  const [contactDialogOpen, setContactDialogOpen] = useState(false);

  const templates = useTemplates(locale);
  const contacts = useContactNames();
  // The attestation copy is backend-served: what the requester ticks is
  // exactly the text the deployment records against the request.
  const attestationText = useConsentText("REQUESTER_VERBAL_CONSENT_ATTESTATION");
  const attestationParagraphs = consentParagraphs(attestationText.data?.body);

  const create = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST("/api/v1/reference-requests", {
          body: {
            templateId: templateId!,
            recommenderContactId: contactId ?? null,
            purpose: purpose || null,
            verbalConsentAttested: attested,
          },
        }),
      ),
    onSuccess: (request) => router.push(`/requests/${request.id}`),
  });

  const steps = [
    t("builder.steps.template"),
    t("builder.steps.context"),
    t("builder.steps.recommender"),
    t("builder.steps.confirm"),
  ];

  const canContinue =
    (step === 0 && !!templateId) ||
    step === 1 ||
    (step === 2 && !!contactId) ||
    (step === 3 && attested && !!attestationText.data);

  const selectedTemplate = templates.data?.items?.find(
    (tpl) => tpl.id === templateId,
  );
  const selectedContact = contactId
    ? contacts.data?.get(contactId)
    : undefined;

  return (
    <div className="flex flex-col gap-8">
      <h1 className="text-2xl font-extrabold text-ink">{t("builder.title")}</h1>
      <Stepper steps={steps} current={step} />

      {step === 0 && (
        <section className="flex flex-col gap-4">
          <div>
            <h2 className="text-lg font-bold text-ink">
              {t("builder.templateTitle")}
            </h2>
            <p className="text-sm text-muted-text">
              {t("builder.templateHint")}
            </p>
          </div>
          {templates.isLoading && <Skeleton className="h-40" />}
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {templates.data?.items?.map((template) => {
              const Icon = TEMPLATE_ICONS[template.type ?? ""] ?? FileText;
              const selected = template.id === templateId;
              return (
                <button
                  key={template.id}
                  type="button"
                  onClick={() => setTemplateId(template.id)}
                  aria-pressed={selected}
                  className={cn(
                    "flex flex-col items-start gap-2 rounded-card border bg-white p-5 text-left transition-colors",
                    selected
                      ? "border-ink shadow-card"
                      : "border-border-light hover:border-blue-gray",
                  )}
                >
                  <Icon
                    className={cn(
                      "size-6",
                      selected ? "text-ink" : "text-blue-gray",
                    )}
                  />
                  <span className="font-bold text-ink">{template.name}</span>
                  <span className="text-sm text-muted-text">
                    {template.description}
                  </span>
                </button>
              );
            })}
          </div>
        </section>
      )}

      {step === 1 && (
        <section className="flex max-w-2xl flex-col gap-4">
          <div>
            <h2 className="text-lg font-bold text-ink">
              {t("builder.contextTitle")}
            </h2>
            <p className="text-sm text-muted-text">
              {t("builder.contextHint")}
            </p>
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="purpose">{t("builder.purpose")}</Label>
            <Textarea
              id="purpose"
              value={purpose}
              maxLength={2000}
              onChange={(e) => setPurpose(e.target.value)}
              placeholder={t("builder.purposePlaceholder")}
            />
            <p className="text-xs text-muted-text">
              {t("builder.purposeVisibility")}
            </p>
          </div>
        </section>
      )}

      {step === 2 && (
        <section className="flex max-w-2xl flex-col gap-4">
          <div>
            <h2 className="text-lg font-bold text-ink">
              {t("builder.recommenderTitle")}
            </h2>
            <p className="text-sm text-muted-text">
              {t("builder.recommenderHint")}
            </p>
          </div>
          <div className="flex flex-col gap-2">
            {contacts.isLoading && <Skeleton className="h-24" />}
            {contacts.data &&
              [...contacts.data.entries()].map(([id, contact]) => (
                <button
                  key={id}
                  type="button"
                  onClick={() => setContactId(id)}
                  aria-pressed={contactId === id}
                  className={cn(
                    "flex items-center gap-3 rounded-card border bg-white p-4 text-left transition-colors",
                    contactId === id
                      ? "border-ink shadow-card"
                      : "border-border-light hover:border-blue-gray",
                  )}
                >
                  <span className="flex size-9 items-center justify-center rounded-full bg-paper text-xs font-extrabold text-ink">
                    {contact.name.slice(0, 2).toUpperCase()}
                  </span>
                  <span className="min-w-0">
                    <span className="block truncate font-bold text-ink">
                      {contact.name}
                    </span>
                    <span className="block truncate text-sm text-muted-text">
                      {contact.email}
                    </span>
                  </span>
                </button>
              ))}
            <Button
              type="button"
              variant="secondary"
              onClick={() => setContactDialogOpen(true)}
              className="self-start"
            >
              {t("contacts.add")}
            </Button>
          </div>
        </section>
      )}

      {step === 3 && (
        <section className="flex max-w-2xl flex-col gap-4">
          <div>
            <h2 className="text-lg font-bold text-ink">
              {t("builder.confirmTitle")}
            </h2>
          </div>
          <Card className="flex flex-col gap-2 p-5 shadow-none">
            <p className="text-sm">
              <span className="text-muted-text">{t("builder.steps.template")}: </span>
              <span className="font-semibold text-ink">
                {selectedTemplate?.name}
              </span>
            </p>
            <p className="text-sm">
              <span className="text-muted-text">
                {t("builder.steps.recommender")}:{" "}
              </span>
              <span className="font-semibold text-ink">
                {selectedContact?.name} · {selectedContact?.email}
              </span>
            </p>
            {purpose && (
              <p className="text-sm">
                <span className="text-muted-text">{t("builder.purpose")}: </span>
                <span className="text-ink">{purpose}</span>
              </p>
            )}
          </Card>
          {attestationText.data ? (
            <>
              <label className="flex flex-col gap-2 rounded-card border border-border-light bg-paper/60 p-4">
                <span className="text-sm font-semibold text-ink">
                  {attestationText.data.title}
                </span>
                <span className="flex items-start gap-3">
                  <Checkbox
                    checked={attested}
                    onCheckedChange={(v) => setAttested(v === true)}
                    className="mt-0.5"
                  />
                  <span className="text-sm text-ink">
                    {attestationParagraphs[0]}
                  </span>
                </span>
              </label>
              {attestationParagraphs.slice(1).map((paragraph, i) => (
                <p key={i} className="text-xs text-muted-text">
                  {paragraph}
                </p>
              ))}
            </>
          ) : (
            <div className="flex flex-col gap-2 rounded-card border border-border-light bg-paper/60 p-4">
              <Skeleton className="h-4 w-48" />
              <Skeleton className="h-4 w-full" />
            </div>
          )}
        </section>
      )}

      <div className="flex gap-3">
        {step > 0 && (
          <Button
            type="button"
            variant="secondary"
            onClick={() => setStep(step - 1)}
          >
            {t("common.back")}
          </Button>
        )}
        {step < steps.length - 1 ? (
          <Button
            type="button"
            disabled={!canContinue}
            onClick={() => setStep(step + 1)}
          >
            {t("common.continue")}
          </Button>
        ) : (
          <Button
            type="button"
            disabled={!canContinue || create.isPending}
            onClick={() => create.mutate()}
          >
            {t("builder.create")}
          </Button>
        )}
      </div>

      <ContactDialog
        open={contactDialogOpen}
        onOpenChange={setContactDialogOpen}
      />
    </div>
  );
}
