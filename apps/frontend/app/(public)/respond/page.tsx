"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { CheckCircle2 } from "lucide-react";
import { useTranslations } from "next-intl";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { ConsentGate } from "@/components/respond/consent-gate";
import { UploadsPanel } from "@/components/respond/uploads-panel";
import { VerifolioWordmark } from "@/components/verifolio/wordmark";
import { api } from "@/lib/api/client";
import { RequestError, unwrap } from "@/lib/query-provider";

type Question = { key: string; label: string; required?: boolean };

function questionsOf(schema: unknown): Question[] {
  const qs = (schema as { recommenderQuestions?: Question[] } | undefined)
    ?.recommenderQuestions;
  return Array.isArray(qs) ? qs.filter((q) => q?.key && q?.label) : [];
}

const AUTOSAVE_DELAY_MS = 2_000;

export default function RespondPage() {
  const t = useTranslations();
  const [declined, setDeclined] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [letter, setLetter] = useState("");
  const [recipientConfirmed, setRecipientConfirmed] = useState(false);
  const [relationshipConfirmed, setRelationshipConfirmed] = useState(false);
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved">(
    "idle",
  );
  const hydrated = useRef(false);
  const saveTimer = useRef<ReturnType<typeof setTimeout>>(null);

  const context = useQuery({
    queryKey: ["recommender-request"],
    queryFn: async () => unwrap(await api.GET("/api/v1/recommender/request")),
    retry: false,
  });

  // Hydrate local state from the server draft exactly once.
  useEffect(() => {
    const draft = context.data?.draft;
    if (draft && !hydrated.current) {
      hydrated.current = true;
      setAnswers((draft.answersJson as Record<string, string>) ?? {});
      setLetter(draft.approvedLetterText ?? "");
    }
  }, [context.data]);

  const saveDraft = useMutation({
    mutationFn: async (payload: {
      answersJson: Record<string, string>;
      approvedLetterText: string | null;
    }) =>
      unwrap(await api.PUT("/api/v1/recommender/response-draft", { body: payload })),
    onSuccess: () => setSaveState("saved"),
    onError: () => setSaveState("idle"),
  });

  const scheduleSave = (
    nextAnswers: Record<string, string>,
    nextLetter: string,
  ) => {
    setSaveState("saving");
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(() => {
      saveDraft.mutate({
        answersJson: nextAnswers,
        approvedLetterText: nextLetter || null,
      });
    }, AUTOSAVE_DELAY_MS);
  };

  const submit = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST("/api/v1/recommender/responses", {
          body: {
            answersJson: answers,
            approvedLetterText: letter || null,
            recipientConfirmed,
            relationshipConfirmed,
            confirmationText: null,
          },
        }),
      ),
    onSuccess: () => setSubmitted(true),
  });

  const sessionExpired =
    context.error instanceof RequestError &&
    [401, 403].includes(context.error.status);

  const wrap = (children: React.ReactNode) => (
    <main className="flex flex-1 flex-col items-center gap-8 px-4 py-12">
      <VerifolioWordmark />
      {children}
    </main>
  );

  if (context.isLoading) return wrap(<Skeleton className="h-64 w-full max-w-2xl" />);

  if (sessionExpired || (!context.data && !context.isLoading)) {
    return wrap(
      <Card className="w-full max-w-lg">
        <CardHeader>
          <CardTitle>{t("respond.expiredTitle")}</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-slate-text">{t("respond.expiredBody")}</p>
        </CardContent>
      </Card>,
    );
  }

  if (declined) {
    return wrap(
      <Card className="w-full max-w-lg">
        <CardContent className="flex items-start gap-3 p-6" role="status">
          <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-verified-green" aria-hidden />
          <p className="text-sm text-slate-text">{t("respond.declinedBody")}</p>
        </CardContent>
      </Card>,
    );
  }

  if (submitted) {
    return wrap(
      <Card className="w-full max-w-lg">
        <CardContent className="flex flex-col gap-3 p-6" role="status">
          <div className="flex items-start gap-3">
            <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-verified-green" aria-hidden />
            <p className="text-sm text-slate-text">{t("respond.submittedBody")}</p>
          </div>
        </CardContent>
      </Card>,
    );
  }

  const data = context.data!;
  const consentPending = data.status === "OPENED" || data.status === "SENT";

  if (consentPending) {
    return wrap(
      <>
        <div className="w-full max-w-2xl text-center">
          <h1 className="text-xl font-extrabold text-ink">
            {t("respond.title", { requester: data.requesterName ?? "" })}
          </h1>
        </div>
        <ConsentGate consents={data.consents} onDecided={(d) => {
          if (d) setDeclined(true);
          else context.refetch();
        }} />
      </>,
    );
  }

  const questions = questionsOf(data.questionSchema);
  const requiredAnswered = questions
    .filter((q) => q.required)
    .every((q) => (answers[q.key] ?? "").trim().length > 0);
  const canSubmit =
    requiredAnswered && recipientConfirmed && relationshipConfirmed;

  return wrap(
    <div className="flex w-full max-w-2xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-extrabold text-ink">
          {t("respond.title", { requester: data.requesterName ?? "" })}
        </h1>
        <p className="mt-1 text-sm text-muted-text">{data.templateName}</p>
        {data.purpose && (
          <p className="mt-3 rounded-card bg-paper/60 p-4 text-sm text-ink">
            {data.purpose}
          </p>
        )}
      </div>

      <Card className="shadow-none">
        <CardHeader>
          <CardTitle className="flex items-center justify-between">
            {t("respond.questionsTitle")}
            <span
              className="text-xs font-normal text-muted-text"
              role="status"
            >
              {saveState === "saved" && t("common.saved")}
              {saveState === "saving" && t("respond.saving")}
            </span>
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {questions.map((q) => (
            <div key={q.key} className="flex flex-col gap-2">
              <Label htmlFor={`q-${q.key}`}>
                {q.label}
                {q.required && <span className="text-danger"> *</span>}
              </Label>
              <Textarea
                id={`q-${q.key}`}
                value={answers[q.key] ?? ""}
                onChange={(e) => {
                  const next = { ...answers, [q.key]: e.target.value };
                  setAnswers(next);
                  scheduleSave(next, letter);
                }}
              />
            </div>
          ))}
        </CardContent>
      </Card>

      <Card className="shadow-none">
        <CardHeader>
          <CardTitle>{t("respond.letterTitle")}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <p className="text-sm text-muted-text">{t("respond.letterHint")}</p>
          <Textarea
            aria-label={t("respond.letterTitle")}
            value={letter}
            maxLength={20000}
            rows={10}
            onChange={(e) => {
              setLetter(e.target.value);
              scheduleSave(answers, e.target.value);
            }}
          />
          {letter && (
            <div className="rounded-card border border-border-light bg-paper/40 p-5 font-serif text-[15px] leading-relaxed text-ink">
              {letter.split("\n\n").map((paragraph, i) => (
                <p key={i} className="mb-3 last:mb-0">
                  {paragraph}
                </p>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      <UploadsPanel />

      <Card className="shadow-none">
        <CardHeader>
          <CardTitle>{t("respond.confirmTitle")}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <label className="flex items-start gap-3 text-sm text-ink">
            <Checkbox
              checked={recipientConfirmed}
              onCheckedChange={(v) => setRecipientConfirmed(v === true)}
              className="mt-0.5"
            />
            {t("respond.recipientConfirm", {
              requester: data.requesterName ?? "",
            })}
          </label>
          <label className="flex items-start gap-3 text-sm text-ink">
            <Checkbox
              checked={relationshipConfirmed}
              onCheckedChange={(v) => setRelationshipConfirmed(v === true)}
              className="mt-0.5"
            />
            {t("respond.relationshipConfirm")}
          </label>
          <Button
            variant="success"
            className="mt-2 self-start"
            disabled={!canSubmit || submit.isPending}
            onClick={() => submit.mutate()}
          >
            {t("respond.submit")}
          </Button>
          {!requiredAnswered && (
            <p className="text-xs text-muted-text">
              {t("respond.requiredHint")}
            </p>
          )}
        </CardContent>
      </Card>

      <p className="pb-8 text-center text-xs text-muted-text">
        {t("respond.footer")}{" "}
        <Link href="/" className="text-trust-blue hover:underline">
          Verifolio
        </Link>
      </p>
    </div>,
  );
}
