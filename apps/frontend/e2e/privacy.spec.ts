import { expect, test } from "@playwright/test";

import { loginViaMagicLink, runCanonicalFlowToShare } from "./helpers";
import { waitForMail } from "./mailpit";

/**
 * Privacy / DSR flows (docs privacy-dsr-core design):
 *  1. Recommender retraction journey — a COMPLETED, publicly shared document
 *     is retracted when the recommender withdraws consent through the
 *     account-less /data-requests channel. The public page then shows the
 *     retracted banner and REVOKED trust signals, but the PDF stays present.
 *  2. Owner DSR intake — an account holder files a DELETION request from
 *     /profile and sees it listed as RECEIVED with a due date.
 */

const run = Date.now();

test("retraction journey: recommender withdraws consent → public page retracted", async ({
  browser,
}) => {
  const requesterEmail = `e2e-priv-req-${run}@example.com`;
  const recommenderEmail = `e2e-priv-rec-${run}@example.com`;

  const { shareUrl } = await runCanonicalFlowToShare(
    browser,
    requesterEmail,
    recommenderEmail,
  );

  const publicContext = await browser.newContext();
  const recommenderContext = await browser.newContext();
  try {
    // Public page is live and verified before the retraction.
    const publicPage = await publicContext.newPage();
    await publicPage.goto(shareUrl);
    await expect(
      publicPage.getByText("Verified document").first(),
    ).toBeVisible();
    await expect(publicPage.getByText("Verification badges")).toBeVisible();
    await expect(
      publicPage.locator('[data-trust-badge="failed"]'),
    ).toHaveCount(0);

    // Recommender opens the account-less data-requests channel and asks for a code.
    const drPage = await recommenderContext.newPage();
    await drPage.goto("/data-requests");
    await drPage.getByLabel("Email address").fill(recommenderEmail);
    await drPage.getByRole("button", { name: "Send code" }).click();
    await expect(drPage.getByRole("status")).toBeVisible();

    // The email carries both the 6-digit code and the per-request verify link.
    const [, code] = await waitForMail(
      recommenderEmail,
      /Verification code:\s*(\d{6})/,
    );
    const [verifyLink] = await waitForMail(
      recommenderEmail,
      /http:\/\/localhost:3000\/data-requests\/[0-9a-f-]{36}/,
    );

    // Follow the link, enter the code, keep default CONSENT_WITHDRAWAL, confirm.
    await drPage.goto(verifyLink);
    await drPage.getByLabel("Verification code").fill(code);
    await drPage.getByRole("button", { name: "Confirm" }).click();
    await expect(
      drPage.getByText(/Consent withdrawal completed/),
    ).toBeVisible();

    // Reload the public page: retracted banner, REVOKED (failed) signals, PDF intact.
    await publicPage.reload();
    await expect(
      publicPage.getByText(/Recommendation retracted by the recommender on/),
    ).toBeVisible();
    await expect(
      publicPage.locator('[data-trust-badge="failed"]').first(),
    ).toBeVisible();
    await expect(publicPage.getByText("Files")).toBeVisible();
    await expect(
      publicPage.getByRole("button", { name: "Download" }).first(),
    ).toBeVisible();
  } finally {
    await publicContext.close();
    await recommenderContext.close();
  }
});

test("owner DSR intake: DELETION request appears as RECEIVED with a due date", async ({
  browser,
}) => {
  const ownerEmail = `e2e-priv-owner-${run}@example.com`;

  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await loginViaMagicLink(page, ownerEmail);

    await page.goto("/profile");
    // Data & privacy card. DELETION is the default request type.
    await expect(page.getByText("Data & privacy")).toBeVisible();
    await page.getByRole("button", { name: "Submit request" }).click();
    await expect(page.getByText("Your request was received.")).toBeVisible();

    // The request now shows in the owner's list: RECEIVED status + a due date.
    await expect(page.getByText("Received", { exact: true })).toBeVisible();
    await expect(page.getByText(/Due by/)).toBeVisible();
  } finally {
    await context.close();
  }
});
