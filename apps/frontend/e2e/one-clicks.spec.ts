import { expect, test, type Page } from "@playwright/test";

import { waitForMail } from "./mailpit";

const run = Date.now();
const requesterEmail = `e2e-oneclick-req-${run}@example.com`;
const recommenderEmail = `e2e-oneclick-rec-${run}@example.com`;

async function createAndSendRequest(page: Page): Promise<string> {
  await page.goto("/login");
  await page.getByLabel("Email").fill(requesterEmail);
  await page.getByRole("button", { name: "Send sign-in link" }).click();
  const [link] = await waitForMail(
    requesterEmail,
    /http:\/\/localhost:3000\/auth\/callback\?token=[\w~.-]+/,
  );
  await page.goto(link);
  await expect(page).toHaveURL(/\/dashboard/);

  await page.goto("/contacts");
  await page.getByRole("button", { name: "Add contact" }).first().click();
  await page.getByLabel("Name").fill("OneClick Rec");
  await page.getByLabel("Email").fill(recommenderEmail);
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("OneClick Rec")).toBeVisible();

  await page.goto("/requests/new");
  await page.getByText("Employment Reference").first().click();
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByText("OneClick Rec").click();
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "Create request" }).click();
  await expect(page).toHaveURL(/\/requests\/[0-9a-f-]{36}/);
  const requestUrl = page.url();
  await page.getByRole("button", { name: "Send invitation" }).click();
  await expect(page.getByText("Sent", { exact: true })).toBeVisible();
  return requestUrl;
}

test("stop-reminders and decline one-clicks", async ({ browser }) => {
  const requester = await browser.newContext();
  const requesterPage = await requester.newPage();
  const requestUrl = await createAndSendRequest(requesterPage);

  const [invitationLink] = await waitForMail(
    recommenderEmail,
    /http:\/\/localhost:3000\/invitations\/[\w~.-]+/,
  );

  const recommender = await browser.newContext();
  const recommenderPage = await recommender.newPage();

  // Stop reminders: confirm click required, idempotent done state.
  await recommenderPage.goto(`${invitationLink}/stop-reminders`);
  await recommenderPage.getByRole("button", { name: "Stop reminders" }).click();
  await expect(
    recommenderPage.getByText(/Reminders are stopped/),
  ).toBeVisible();

  // Decline: terminal for the request.
  await recommenderPage.goto(`${invitationLink}/decline`);
  await recommenderPage.getByRole("button", { name: "Decline request" }).click();
  await expect(
    recommenderPage.getByText(/The request is declined/),
  ).toBeVisible();

  // Requester sees DECLINED and the terminal banner.
  await requesterPage.goto(requestUrl);
  await expect(
    requesterPage.getByText(/recommender declined this request/i),
  ).toBeVisible();
});
