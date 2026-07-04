# Code Style

## General Principles

- Prefer explicit code over clever code.
- Keep domain rules readable.
- Avoid hidden side effects.
- Favor small application services and well-named policies.
- Do not place business logic in controllers.
- Do not duplicate permission logic.
- Do not bypass module APIs.

## Kotlin

Use idiomatic Kotlin:

- data classes for DTOs and value objects where appropriate;
- sealed classes for constrained state/result hierarchies;
- non-null types by default;
- explicit nullable handling;
- meaningful domain names;
- extension functions only when they improve clarity.

Avoid:

- large God services;
- deeply nested conditionals;
- excessive use of `Any`;
- reflection-heavy patterns;
- raw strings for domain states when enums/sealed classes are better;
- silent null fallback for security-sensitive data.

## Spring

Controllers should:

- validate input;
- delegate to application services;
- return DTOs.

Application services should:

- orchestrate use cases;
- enforce authorization;
- emit audit events;
- call repositories through module-local interfaces.

Domain classes should:

- express invariants;
- validate state transitions;
- avoid infrastructure dependencies.

Infrastructure should:

- implement repositories;
- integrate providers;
- contain object storage, mail, Temporal adapters.

## Database

- Use Flyway for migrations.
- Use jOOQ for database access.
- Prefer explicit SQL semantics.
- Add constraints for important invariants.
- Add indexes for public lookup tokens, owner IDs, document IDs, and status filters.
- Never manually edit generated jOOQ code.

## API

- Use stable DTOs.
- Keep internal domain objects out of public API responses.
- Use explicit error codes.
- Use pagination for lists.
- Use opaque IDs externally.
- Do not expose internal storage keys unless explicitly safe.

## Error Handling

- Use typed application errors.
- Do not leak sensitive details.
- Log enough for debugging without logging secrets.
- User-facing errors should be actionable.

## Logging

Do not log:

- full magic link tokens;
- invitation tokens;
- session IDs;
- private signed URLs;
- document contents;
- raw uploaded file contents;
- passwords;
- raw signature payloads.

## Formatting

The project should use automated formatting and linting.

Recommended:

- ktlint or Spotless for Kotlin;
- ESLint/Prettier for frontend;
- markdownlint for documentation where practical.
