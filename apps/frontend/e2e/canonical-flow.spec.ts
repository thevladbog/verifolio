import { expect, test, type Page } from "@playwright/test";

import { waitForMail } from "./mailpit";

/**
 * The canonical end-to-end sequence (docs/USER_FLOWS.md): login →
 * contact → build+send request → recommender confirms email → consent →
 * respond → submit → recipient accepts → share link → public page →
 * revoke → invalid state. Requester and recommender run in separate
 * browser contexts.
 */

const run = Date.now();
const requesterEmail = `e2e-requester-${run}@example.com`;
const recommenderEmail = `e2e-recommender-${run}@example.com`;

async function loginRequester(page: Page) {
  await page.goto("/login");
  await page.getByLabel("Email").fill(requesterEmail);
  await page.getByRole("button", { name: "Send sign-in link" }).click();
  await expect(page.getByRole("status")).toContainText(/on its way/);
  const [link] = await waitForMail(
    requesterEmail,
    /http:\/\/localhost:3000\/auth\/callback\?token=[\w~.-]+/,
  );
  await page.goto(link);
  await expect(page).toHaveURL(/\/dashboard/);
}

test("canonical flow: request → response → accept → share → revoke", async ({
  browser,
}) => {
  const requester = await browser.newContext();
  const requesterPage = await requester.newPage();
  await loginRequester(requesterPage);

  // Contact
  await requesterPage.goto("/contacts");
  await requesterPage.getByRole("button", { name: "Add contact" }).first().click();
  await requesterPage.getByLabel("Name").fill("E2E Recommender");
  await requesterPage.getByLabel("Email").fill(recommenderEmail);
  await requesterPage.getByRole("button", { name: "Save" }).click();
  await expect(requesterPage.getByText("E2E Recommender")).toBeVisible();

  // Builder
  await requesterPage.goto("/requests/new");
  await requesterPage.getByText("Employment Reference").first().click();
  await requesterPage.getByRole("button", { name: "Continue" }).click();
  await requesterPage
    .getByLabel("Purpose and context")
    .fill("E2E canonical flow");
  await requesterPage.getByRole("button", { name: "Continue" }).click();
  await requesterPage.getByText("E2E Recommender").click();
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
  const recommender = await browser.newContext();
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

  // Consent gate: no inputs before accept
  await expect(recommenderPage.getByRole("textbox")).toHaveCount(0);
  await recommenderPage
    .getByRole("button", { name: "I agree — start" })
    .click();

  // Answer required questions
  await expect(
    recommenderPage.getByText("Guided questions"),
  ).toBeVisible();
  const required = recommenderPage.locator("textarea[id^='q-']");
  const count = await required.count();
  for (let i = 0; i < count; i++) {
    await required.nth(i).fill(`E2E answer ${i + 1}`);
  }
  await recommenderPage
    .getByLabel("Your letter")
    .fill("I confirm this E2E reference letter.");

  // Confirm + submit
  await recommenderPage.getByRole("checkbox", { name: /reference is for/i }).check();
  await recommenderPage
    .getByRole("checkbox", { name: /professional relationship/i })
    .check();
  await recommenderPage.getByRole("button", { name: "Submit response" }).click();
  await expect(
    recommenderPage.getByText(/response is submitted/i),
  ).toBeVisible();

  // Requester accepts
  await requesterPage.goto(requestUrl);
  await requesterPage.getByRole("button", { name: "Accept and lock" }).click();
  await requesterPage.getByRole("link", { name: "Open document" }).click();
  await expect(requesterPage).toHaveURL(/\/documents\/[\w-]+/);
  await expect(requesterPage.getByText(/v1 · locked/)).toBeVisible();

  // Share link
  await requesterPage.getByRole("button", { name: "Share" }).click();
  await requesterPage.getByRole("button", { name: "No expiry" }).click();
  await requesterPage.getByRole("button", { name: "Create link" }).click();
  const shareUrl = (
    await requesterPage.locator("p.font-mono").textContent()
  )?.trim();
  expect(shareUrl).toMatch(/\/verify\//);
  await requesterPage.getByRole("button", { name: "Close" }).first().click();

  // Public page
  const publicPage = await (await browser.newContext()).newPage();
  await publicPage.goto(shareUrl!);
  await expect(publicPage.getByText("Verified document").first()).toBeVisible();
  await expect(publicPage.getByText("stated by recommender")).toBeVisible();
  await expect(publicPage.getByText("Verification badges")).toBeVisible();

  // Revoke → public page goes invalid
  await requesterPage.getByRole("button", { name: "Revoke" }).click();
  await expect(requesterPage.getByText("Revoked", { exact: true })).toBeVisible();
  await publicPage.reload();
  await expect(
    publicPage.getByText(/invalid or no longer active/i),
  ).toBeVisible();
});
