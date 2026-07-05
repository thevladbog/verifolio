import { expect, test } from "@playwright/test";

import { runCanonicalFlowToShare } from "./helpers";

/**
 * Verified-organization provenance (organizations module, iteration 12):
 * when the recommender's email is on a seeded VERIFIED org domain
 * (SAP SE / sap.com — V13 seed), the CORPORATE_DOMAIN_CONFIRMED signal is
 * enriched to a verified-record and the public verify page names the
 * organization. Mailpit captures any domain locally, so @sap.com delivery
 * works in the local stack.
 */

const run = Date.now();

test("verified org provenance: sap.com recommender → public page names SAP SE", async ({
  browser,
}) => {
  const requesterEmail = `e2e-org-req-${run}@example.com`;
  const recommenderEmail = `e2e-org-rec-${run}@sap.com`;

  const { shareUrl } = await runCanonicalFlowToShare(
    browser,
    requesterEmail,
    recommenderEmail,
  );

  const publicContext = await browser.newContext();
  try {
    const publicPage = await publicContext.newPage();
    await publicPage.goto(shareUrl);

    await expect(
      publicPage.getByText("Verified document").first(),
    ).toBeVisible();
    await expect(publicPage.getByText("Verification badges")).toBeVisible();

    // The verified-record provenance line matches messages/en.json
    // verify.orgVerifiedRecord ("at {name} — verified organization record").
    await expect(
      publicPage.getByText("at SAP SE — verified organization record"),
    ).toBeVisible();
  } finally {
    await publicContext.close();
  }
});
