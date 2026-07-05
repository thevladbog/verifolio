import { expect, test, type Page } from "@playwright/test";
import * as OTPAuth from "otpauth";

import { loginViaMagicLink } from "./helpers";
import { waitForMail } from "./mailpit";

/**
 * Admin console E2E (docs admin-foundation design, Task 8).
 *
 *  1. Enroll + dashboard + DSR queue — the bootstrapped SUPERADMIN
 *     (`verifolio.admin.bootstrap-emails=admin-e2e@verifolio.test`, seeded on the
 *     backend for this run) requests a magic link, enrolls TOTP MFA (the test
 *     computes a valid code from the secret shown on the enroll page), lands on
 *     the dashboard, and reviews a seeded owner DELETION request in the DSR queue.
 *  2. Re-login via CHALLENGE — a fresh session for the now-enrolled admin routes
 *     through the MFA challenge (not enroll) and a fresh TOTP mints a session.
 *
 * Serial: the enroll test captures the TOTP secret the challenge test reuses, and
 * enrollment must happen before the challenge branch exists.
 */

const ADMIN_EMAIL = "admin-e2e@verifolio.test";
const ADMIN_LINK = /http:\/\/localhost:3000\/admin\/auth\/callback\?token=[\w~.-]+/;

/** Computes the current 6-digit TOTP for a Base32 secret (mirrors the backend). */
function totp(secretBase32: string): string {
  return new OTPAuth.TOTP({
    secret: OTPAuth.Secret.fromBase32(secretBase32),
    digits: 6,
    period: 30,
  }).generate();
}

/** Requests an admin magic link and follows it to the MFA branch (enroll/challenge). */
async function requestAdminLink(page: Page): Promise<void> {
  await page.goto("/admin/login");
  await page.getByLabel("Email").fill(ADMIN_EMAIL);
  await page.getByRole("button", { name: "Send sign-in link" }).click();
  await expect(page.getByRole("status")).toContainText(/sign-in link is on its way/);
  const [link] = await waitForMail(ADMIN_EMAIL, ADMIN_LINK);
  await page.goto(link);
}

const run = Date.now();
// Captured during enroll so the challenge test can compute the same TOTP.
let mfaSecret: string;

test.describe.serial("admin console", () => {
  test("enroll → dashboard → DSR queue", async ({ browser }) => {
    const ownerEmail = `e2e-admin-owner-${run}@example.com`;

    // Seed a RECEIVED DSR: an owner files a DELETION request from /profile.
    const ownerContext = await browser.newContext();
    try {
      const ownerPage = await ownerContext.newPage();
      await loginViaMagicLink(ownerPage, ownerEmail);
      await ownerPage.goto("/profile");
      await expect(ownerPage.getByText("Data & privacy")).toBeVisible();
      await ownerPage.getByRole("button", { name: "Submit request" }).click();
      await expect(ownerPage.getByText("Your request was received.")).toBeVisible();
    } finally {
      await ownerContext.close();
    }

    const adminContext = await browser.newContext();
    try {
      const admin = await adminContext.newPage();
      await requestAdminLink(admin);

      // First login for this admin → ENROLL branch. Read the secret, compute a code.
      await expect(admin).toHaveURL(/\/admin\/mfa\/enroll/);
      const secret = (
        await admin.getByTestId("mfa-secret").textContent()
      )?.trim();
      expect(secret).toMatch(/^[A-Z2-7]+$/);
      mfaSecret = secret!;

      await admin.getByLabel("Authenticator code").fill(totp(mfaSecret));
      await admin.getByRole("button", { name: "Enable two-factor" }).click();

      // Dashboard: identity (email + SUPERADMIN role) and the pending-DSR card.
      await expect(admin).toHaveURL(/\/admin$/);
      await expect(
        admin.getByRole("heading", { name: `Signed in as ${ADMIN_EMAIL}` }),
      ).toBeVisible();
      await expect(admin.getByText("Superadmin", { exact: true })).toBeVisible();
      await expect(admin.getByText("Pending data requests")).toBeVisible();

      // DSR queue: the seeded DELETION request is listed; its detail shows the
      // SUPERADMIN decision actions (approve / reject / execute).
      await admin.getByRole("link", { name: "Open the review queue" }).click();
      await expect(admin).toHaveURL(/\/admin\/data-requests/);

      const row = admin.getByRole("button").filter({ hasText: ownerEmail });
      await expect(row).toBeVisible();
      await row.click();

      await expect(
        admin.getByRole("button", { name: "Approve", exact: true }),
      ).toBeVisible();
      await expect(
        admin.getByRole("button", { name: "Execute", exact: true }),
      ).toBeVisible();
    } finally {
      await adminContext.close();
    }
  });

  test("EXPORT DSR: admin executes → EXECUTED + subject receives export link", async ({
    browser,
  }) => {
    expect(mfaSecret, "enroll test must run first").toBeTruthy();
    const exportOwnerEmail = `e2e-export-owner-${run}@example.com`;

    // An account holder submits an EXPORT DSR from the /profile Data & privacy card.
    const ownerContext = await browser.newContext();
    try {
      const ownerPage = await ownerContext.newPage();
      await loginViaMagicLink(ownerPage, exportOwnerEmail);
      await ownerPage.goto("/profile");
      await expect(ownerPage.getByText("Data & privacy")).toBeVisible();
      // Default type is DELETION; switch the request-type select to EXPORT.
      await ownerPage.getByRole("combobox", { name: "Request type" }).click();
      await ownerPage.getByRole("option", { name: "Export my data" }).click();
      await ownerPage.getByRole("button", { name: "Submit request" }).click();
      await expect(
        ownerPage.getByText("Your request was received."),
      ).toBeVisible();
    } finally {
      await ownerContext.close();
    }

    // The already-enrolled admin challenge-logs in and executes the EXPORT DSR.
    const adminContext = await browser.newContext();
    try {
      const admin = await adminContext.newPage();
      await requestAdminLink(admin);
      await expect(admin).toHaveURL(/\/admin\/mfa\/challenge/);
      await admin.getByLabel("Authenticator code").fill(totp(mfaSecret));
      await admin.getByRole("button", { name: "Verify" }).click();
      await expect(admin).toHaveURL(/\/admin$/);

      await admin.goto("/admin/data-requests");
      const row = admin
        .getByRole("button")
        .filter({ hasText: exportOwnerEmail });
      await expect(row).toBeVisible();
      await row.click();

      // The detail panel shows the EXPORT request; execute it.
      await expect(
        admin.getByRole("heading", { name: "Export" }),
      ).toBeVisible();
      await admin
        .getByRole("button", { name: "Execute", exact: true })
        .click();

      // EXPORT now executes synchronously — the DSR flips to EXECUTED and the
      // calm "manual execution required" state never fires for this type.
      await expect(admin.getByText("Executed").first()).toBeVisible();
      await expect(
        admin.getByText("Manual execution required"),
      ).toHaveCount(0);
    } finally {
      await adminContext.close();
    }

    // The subject's inbox receives the data-export email carrying a short-lived
    // MinIO presigned GET link; fetching it yields the account-holder JSON package.
    const [downloadUrl] = await waitForMail(
      exportOwnerEmail,
      /http:\/\/localhost:9000\/\S+/,
    );
    const res = await fetch(downloadUrl);
    expect(res.ok).toBeTruthy();
    const pkg = (await res.json()) as {
      subjectType?: string;
      account?: unknown;
    };
    expect(pkg.subjectType).toBe("ACCOUNT_HOLDER");
    expect(pkg.account).toBeTruthy();
  });

  test("re-login routes through MFA challenge", async ({ browser }) => {
    expect(mfaSecret, "enroll test must run first").toBeTruthy();

    const adminContext = await browser.newContext();
    try {
      const admin = await adminContext.newPage();
      await requestAdminLink(admin);

      // Already enrolled → CHALLENGE branch (not enroll).
      await expect(admin).toHaveURL(/\/admin\/mfa\/challenge/);
      await admin.getByLabel("Authenticator code").fill(totp(mfaSecret));
      await admin.getByRole("button", { name: "Verify" }).click();

      await expect(admin).toHaveURL(/\/admin$/);
      await expect(
        admin.getByRole("heading", { name: `Signed in as ${ADMIN_EMAIL}` }),
      ).toBeVisible();
    } finally {
      await adminContext.close();
    }
  });
});
