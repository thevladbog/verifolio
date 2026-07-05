import { defineConfig } from "@playwright/test";

/**
 * E2E against the local stack: docker-compose postgres/minio/mailpit +
 * the backend (`./gradlew bootRun` in apps/backend). The Next dev server
 * is started automatically.
 */
export default defineConfig({
  testDir: "./e2e",
  // Full multi-actor flows over a dev server compiling routes on first hit.
  timeout: 240_000,
  expect: { timeout: 10_000 },
  retries: process.env.CI ? 1 : 0,
  workers: 1, // flows share the backend's per-email rate limits
  use: {
    baseURL: "http://localhost:3000",
    trace: "retain-on-failure",
  },
  webServer: {
    // Production server: dev-mode on-demand compilation makes long flows flaky.
    command: "npm run build && npm run start",
    url: "http://localhost:3000",
    reuseExistingServer: true,
    timeout: 180_000,
  },
});
