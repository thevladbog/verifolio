# User Flows

## Flow 1: User Registration

1. User opens app.
2. User selects data region.
3. User enters email.
4. User receives magic link.
5. User opens magic link.
6. Backend creates session.
7. User creates profile.

## Flow 2: Create Reference Request

1. User clicks "New Request".
2. User selects template type.
3. User adds context.
4. User adds recommender details.
5. User selects verification options.
6. User previews request email.
7. User sends request.
8. System starts reference request workflow.

## Flow 3: Recommender Submission

1. Recommender receives invitation email.
2. Recommender opens secure link.
3. Recommender confirms email.
4. Recommender reviews request context.
5. Recommender answers guided questions.
6. Recommender optionally uploads scan/PDF.
7. Recommender optionally attaches signature.
8. Recommender confirms recipient and relationship.
9. Recommender submits response.
10. System creates response, audit event, and document workflow.

## Flow 4: Document Generation

1. System receives recommender response.
2. System generates structured document content.
3. System renders HTML.
4. System generates PDF.
5. System stores PDF in object storage.
6. System calculates file hash.
7. System creates document version.
8. System locks version.
9. System creates verification signals.
10. System enables verification page if allowed.

## Flow 5: Public Verification

1. User creates share link.
2. Third party opens link.
3. System validates token and status.
4. System displays verification summary.
5. Third party views document preview.
6. Third party downloads files if allowed.
7. System records audit event where appropriate.

## Flow 6: Revoke Share Link

1. User opens document page.
2. User selects active share link.
3. User clicks revoke.
4. Backend revokes link.
5. Public access stops immediately.
6. Audit event is created.

## Flow 7: Profile Verification

1. User opens profile verification page.
2. User sees available trust signals.
3. User verifies email/phone/professional links.
4. User may attach digital signature or identity proof later.
5. System updates profile verification signals.
6. Public pages can show recipient trust state.

## Flow 8: Region Migration

Not supported automatically.

If needed later:

1. User requests migration.
2. System explains consequences.
3. User gives explicit consent.
4. Export package is created.
5. Target region imports data.
6. Source region handles deletion/retention policy.
7. Full audit trail is preserved.
