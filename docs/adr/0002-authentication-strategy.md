# ADR 0002: Authentication Strategy

## Status

Accepted

## Context

Verifolio must support regional deployments and data residency.

Using managed global authentication providers or a centralized identity provider can complicate personal data localization.

Keycloak was considered but rejected for v1 as operationally excessive.

## Decision

Use an embedded authentication module based on Spring Security.

Initial auth methods:

- email magic links;
- secure server-side sessions;
- recommender invitation links.

The domain model supports multiple auth identities so that external OIDC providers can be added later.

## Consequences

Positive:

- no Keycloak dependency;
- simpler local and regional deployments;
- full control over auth data residency;
- good fit for B2C and recommender flows.

Negative:

- more security responsibility for the engineering team;
- social login, MFA, and SSO require later implementation or provider integration.
