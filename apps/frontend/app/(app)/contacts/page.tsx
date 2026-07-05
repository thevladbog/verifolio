"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";
import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Contact,
  ContactDialog,
} from "@/components/contacts/contact-dialog";
import { api } from "@/lib/api/client";
import { useCursorList } from "@/lib/hooks/use-cursor-list";
import { unwrap } from "@/lib/query-provider";

function contactInitials(name: string): string {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? "")
    .join("");
}

export default function ContactsPage() {
  const t = useTranslations();
  const format = useFormatter();
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<Contact | undefined>();

  const list = useCursorList<Contact>(["contacts"], async (cursor) =>
    unwrap(
      await api.GET("/api/v1/contacts", {
        params: { query: cursor ? { cursor } : {} },
      }),
    ),
  );

  const remove = useMutation({
    mutationFn: async (id: string) =>
      unwrap(
        await api.DELETE("/api/v1/contacts/{id}", {
          params: { path: { id } },
        }),
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ["contacts"] }),
  });

  const openCreate = () => {
    setEditing(undefined);
    setDialogOpen(true);
  };

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-extrabold text-ink">
            {t("contacts.title")}
          </h1>
          <p className="mt-1 text-sm text-muted-text">{t("contacts.subtitle")}</p>
        </div>
        <Button onClick={openCreate}>
          <Plus />
          {t("contacts.add")}
        </Button>
      </div>

      {list.isLoading && (
        <div className="flex flex-col gap-3">
          <Skeleton className="h-20" />
          <Skeleton className="h-20" />
        </div>
      )}

      <div className="flex flex-col gap-3">
        {list.items.map((contact) => (
          <Card
            key={contact.id}
            className="flex items-center gap-4 p-4 shadow-none"
          >
            <div className="flex size-10 shrink-0 items-center justify-center rounded-full bg-paper text-sm font-extrabold text-ink">
              {contactInitials(contact.name ?? "")}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate font-bold text-ink">{contact.name}</p>
              <p className="truncate text-sm text-muted-text">
                {[contact.companyName, contact.title]
                  .filter(Boolean)
                  .join(" · ") || contact.email}
              </p>
            </div>
            <Badge variant="neutral">
              {t(`contacts.relationshipTypes.${contact.relationshipType}`)}
            </Badge>
            {contact.createdAt && (
              <span className="hidden text-xs text-muted-text sm:block">
                {format.dateTime(new Date(contact.createdAt), {
                  dateStyle: "medium",
                })}
              </span>
            )}
            <div className="flex gap-1">
              <Button
                variant="ghost"
                size="icon"
                aria-label={t("contacts.editTitle")}
                onClick={() => {
                  setEditing(contact);
                  setDialogOpen(true);
                }}
              >
                <Pencil />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                aria-label={t("common.delete")}
                onClick={() => remove.mutate(contact.id!)}
              >
                <Trash2 className="text-danger" />
              </Button>
            </div>
          </Card>
        ))}

        {!list.isLoading && (
          <button
            type="button"
            onClick={openCreate}
            className="flex items-center justify-center gap-2 rounded-card border border-dashed border-border-light p-6 text-sm font-semibold text-muted-text transition-colors hover:border-blue-gray hover:text-ink"
          >
            <Plus className="size-4" />
            {t("contacts.add")}
            <span className="font-normal text-muted-text">
              — {t("contacts.verbalConsentHint")}
            </span>
          </button>
        )}
      </div>

      {list.hasNext && (
        <Button
          variant="secondary"
          onClick={list.loadMore}
          disabled={list.isLoadingMore}
          className="self-center"
        >
          {t("common.loadMore")}
        </Button>
      )}

      <ContactDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        contact={editing}
      />
    </div>
  );
}
