# ADR 0008: Region Routing at Login

## Status

Accepted

## Context

Users' data lives in isolated regional cells (ADR 0003). At login time, the system must route a user to the cell that holds their account.

Any global component that maps identities to regions is itself a store of PII-derived data and creates account-enumeration risk, which conflicts with the rule that the global layer stores no personal data.

Share links and public verification URLs must also route to the correct cell without a global lookup.

## Considered Options

1. **Region-specific app domains chosen by the user.** (chosen)
   Users authenticate on `app.eu.verifolio.com`, `app.ru.verifolio.com`, etc.; the global layer only performs region selection.
2. **Global keyed-hash email→region directory.**
   Rejected for v1: even a keyed hash is a centralized PII derivative, it enables enumeration of which emails have accounts, and it requires legal review before it can exist.
3. **Login fan-out across cells.**
   Rejected: querying every cell on each login attempt is an enumeration oracle and adds cross-region latency and coupling.

## Decision

Users authenticate on region-specific app domains (for example `app.eu.verifolio.com`, `app.ru.verifolio.com`).

The global layer performs region selection only. There is **no global email→region directory in v1**.

Share-link and verification tokens encode the region — via a region-encoded token prefix or region subdomains — so the global verification router remains stateless and stores no personal data.

## Consequences

Positive:

- no personal data or PII derivatives stored globally;
- no account enumeration surface at the global layer;
- stateless global routing for verification links;
- clean per-region auth isolation.

Negative:

- a user who forgets their region must try region domains manually;
- the region choice appears in URLs the user sees;
- a keyed-hash email→region directory may be reconsidered later, but only with legal review.
