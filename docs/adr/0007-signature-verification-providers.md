# ADR 0007: Signature Verification Providers

## Status

Accepted (provider selection pending)

## Context

Verifolio verifies digital signatures on recommendation documents.

Legal signature regimes differ by jurisdiction:

- in the EU, legally meaningful validation follows eIDAS, with PAdES (embedded PDF signatures) and CAdES (detached CMS containers) formats;
- in Russia, legally meaningful verification requires GOST R 34.10-2012 cryptography and CryptoPro-ecosystem providers.

Region policies forbid sending regional documents to non-regional providers, so a single global verification service is not acceptable.

The signature format taxonomy must be normalized across regions: detached CMS/CAdES containers and PAdES for embedded PDF signatures, regardless of region.

## Considered Options

1. **In-house verification.**
   Rejected: cryptographic and legal complexity (certificate chains, revocation, trusted lists, GOST implementations) is far beyond MVP scope and creates liability.
2. **Single global provider.**
   Rejected: violates data residency, and no global provider covers GOST R 34.10-2012 / CryptoPro requirements.
3. **Per-region verification providers behind one internal interface.** (chosen)

## Decision

Signature verification is provider-based and region-specific:

- **EU:** eIDAS validation providers (PAdES/CAdES);
- **RU:** GOST R 34.10-2012 / CryptoPro-ecosystem provider.

Concrete provider selection per region is pending; each provider must be listed in the region policy and pass the provider checklist in `docs/REGION_POLICIES.md`.

Fallback: when no verification provider is available in a region, the system asserts **SIGNATURE_ATTACHED** only. It must never claim **SIGNATURE_VERIFIED** without a provider-backed verification result.

Signatures reference specific FileObjects by hash, so a verification result is always bound to exact, immutable file contents.

## Consequences

Positive:

- legally meaningful verification per jurisdiction;
- residency-compliant: documents never leave the region for verification;
- honest signal semantics via the SIGNATURE_ATTACHED fallback;
- new regions can launch before a provider is selected.

Negative:

- per-region provider integration and contract work;
- verification capability differs between regions;
- provider outages degrade regions to SIGNATURE_ATTACHED semantics for new verifications.
