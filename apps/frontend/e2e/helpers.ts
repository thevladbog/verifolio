import {
  expect,
  type Browser,
  type BrowserContext,
  type Page,
} from "@playwright/test";

import { waitForMail } from "./mailpit";

/**
 * Shared building blocks for multi-actor E2E flows, extracted from
 * canonical-flow.spec.ts so the privacy retraction journey can reach a
 * COMPLETED, shared document without duplicating the whole sequence.
 */

/** Logs a fresh account holder in via the emailed magic link; lands on /dashboard. */
export async function loginViaMagicLink(page: Page, email: string): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByRole("button", { name: "Send sign-in link" }).click();
  await expect(page.getByRole("status")).toContainText(/on its way/);
  const [link] = await waitForMail(
    email,
    /http:\/\/localhost:3000\/auth\/callback\?token=[\w~.-]+/,
  );
  await page.goto(link);
  await expect(page).toHaveURL(/\/dashboard/);
}

export type CanonicalFlowResult = {
  requestUrl: string;
  shareUrl: string;
};

/**
 * Drives the full canonical flow (login → contact → request → invitation →
 * code → consent → respond → submit → accept → lock → share link) to a
 * COMPLETED document and returns the public share URL. Requester and
 * recommender run in separate browser contexts.
 */
export async function runCanonicalFlowToShare(
  browser: Browser,
  requesterEmail: string,
  recommenderEmail: string,
): Promise<CanonicalFlowResult> {
  const requester = await browser.newContext();
  const recommender = await browser.newContext();
  try {
    return await runFlow(
      requester,
      recommender,
      requesterEmail,
      recommenderEmail,
    );
  } finally {
    await requester.close();
    await recommender.close();
  }
}

async function runFlow(
  requester: BrowserContext,
  recommender: BrowserContext,
  requesterEmail: string,
  recommenderEmail: string,
): Promise<CanonicalFlowResult> {
  const requesterPage = await requester.newPage();
  await loginViaMagicLink(requesterPage, requesterEmail);

  // Contact
  await requesterPage.goto("/contacts");
  await requesterPage.getByRole("button", { name: "Add contact" }).first().click();
  await requesterPage.getByLabel("Name").fill("Privacy Recommender");
  await requesterPage.getByLabel("Email").fill(recommenderEmail);
  await requesterPage.getByRole("button", { name: "Save" }).click();
  await expect(requesterPage.getByText("Privacy Recommender")).toBeVisible();

  // Builder
  await requesterPage.goto("/requests/new");
  await requesterPage.getByText("Employment Reference").first().click();
  await requesterPage.getByRole("button", { name: "Continue" }).click();
  await requesterPage.getByLabel("Purpose and context").fill("E2E privacy flow");
  await requesterPage.getByRole("button", { name: "Continue" }).click();
  await requesterPage.getByText("Privacy Recommender").click();
  await requesterPage.getByRole("button", { name: "Continue" }).click();
  await requesterPage.getByRole("checkbox").check();
  await requesterPage.getByRole("button", { name: "Create request" }).click();
  await expect(requesterPage).toHaveURL(/\/requests\/[0-9a-f-]{36}/);
  const requestUrl = requesterPage.url();

  // Send
  await requesterPage.getByRole("button", { name: "Send invitation" }).click();
  await expect(requesterPage.getByText("Sent", { exact: true })).toBeVisible();

  // Recommender opens the invitation
  const [invitationLink] = await waitForMail(
    recommenderEmail,
    /http:\/\/localhost:3000\/invitations\/[\w~.-]+/,
  );
  const recommenderPage = await recommender.newPage();
  await recommenderPage.goto(invitationLink);
  await expect(
    recommenderPage.getByText(/asks you for a professional reference/),
  ).toBeVisible();

  // Email confirmation code
  await recommenderPage
    .getByRole("button", { name: "Email me a confirmation code" })
    .click();
  const [, code] = await waitForMail(recommenderEmail, /code[^\d]*(\d{6})/i);
  await recommenderPage.getByLabel("Confirmation code").fill(code);
  await recommenderPage.getByRole("button", { name: "Continue" }).click();
  await expect(recommenderPage).toHaveURL(/\/respond/);

  // Consent gate
  await recommenderPage.getByRole("button", { name: "I agree — start" }).click();

  // Answer required questions
  await expect(recommenderPage.getByText("Guided questions")).toBeVisible();
  const required = recommenderPage.locator("textarea[id^='q-']");
  const count = await required.count();
  for (let i = 0; i < count; i++) {
    await required.nth(i).fill(`E2E answer ${i + 1}`);
  }
  await recommenderPage
    .getByLabel("Your letter")
    .fill("I confirm this E2E privacy reference letter.");

  // Confirm + submit
  await recommenderPage.getByRole("checkbox", { name: /reference is for/i }).check();
  await recommenderPage
    .getByRole("checkbox", { name: /professional relationship/i })
    .check();
  await recommenderPage.getByRole("button", { name: "Submit response" }).click();
  await expect(recommenderPage.getByText(/response is submitted/i)).toBeVisible();

  // Requester accepts + locks
  await requesterPage.goto(requestUrl);
  await expect(requesterPage.getByText("Submitted letter")).toBeVisible();
  await requesterPage.getByRole("button", { name: "Accept and lock" }).click();
  await requesterPage.getByRole("link", { name: "Open document" }).click();
  await expect(requesterPage).toHaveURL(/\/documents\/[\w-]+/);
  await expect(requesterPage.getByText(/v1 · locked/)).toBeVisible();

  // Share link (no expiry)
  await requesterPage.getByRole("button", { name: "Share" }).click();
  await requesterPage.getByRole("button", { name: "No expiry" }).click();
  await requesterPage.getByRole("button", { name: "Create link" }).click();
  const shareUrl = (
    await requesterPage.locator("p.font-mono").textContent()
  )?.trim();
  expect(shareUrl).toMatch(/\/verify\//);
  await requesterPage.getByRole("button", { name: "Close" }).first().click();

  return { requestUrl, shareUrl: shareUrl! };
}
