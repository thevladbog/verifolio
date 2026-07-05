const MAILPIT = process.env.MAILPIT_URL ?? "http://localhost:8025";

type MailpitMessage = { ID: string; To: Array<{ Address: string }> };

async function latestMessageFor(email: string): Promise<string | null> {
  const res = await fetch(
    `${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}&limit=1`,
  );
  if (!res.ok) return null;
  const data = (await res.json()) as { messages?: MailpitMessage[] };
  const id = data.messages?.[0]?.ID;
  if (!id) return null;
  const msg = await fetch(`${MAILPIT}/api/v1/message/${id}`);
  if (!msg.ok) return null;
  const body = (await msg.json()) as { Text?: string };
  return body.Text ?? null;
}

/** Polls Mailpit for the newest message to `email` matching `pattern`. */
export async function waitForMail(
  email: string,
  pattern: RegExp,
  attempts = 20,
): Promise<RegExpMatchArray> {
  for (let i = 0; i < attempts; i++) {
    const text = await latestMessageFor(email);
    const match = text?.match(pattern);
    if (match) return match;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`No mail for ${email} matching ${pattern} after ${attempts} polls`);
}
