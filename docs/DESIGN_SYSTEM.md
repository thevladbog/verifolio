# Verifolio Design System

## Brand Direction

**Verifolio** is a calm, trustworthy, document-oriented product for verified professional references, signed letters, and proof documents.

The visual identity is based on a **V-shaped folder / folio mark**:

* the letter **V** represents **verification**;
* the folder/folio shape represents **documents, references, and personal proof archive**;
* folded paper geometry represents **professional letters and structured evidence**;
* the overall mark should feel modern, credible, and international.

The product should not look like:

* a government portal;
* a legal-only tool;
* a blockchain/crypto product;
* a generic HR platform;
* a playful consumer app;
* a LinkedIn clone.

The desired feeling:

```text
Calm. Verified. Organized. Professional. Human. Secure.
```

## Logo Concept

### Primary Logo Idea

The primary logo is a **stylized letter V shaped like an open folder or folded folio**.

The mark should combine:

1. **Letter V**
   Symbol of verification, validation, and Verifolio.

2. **Folder / Folio**
   Symbol of stored professional documents and references.

3. **Folded Paper**
   Symbol of letters, forms, signatures, and proof.

4. **Subtle Check / Trust Signal**
   Optional, but should not dominate the mark.

### Logo Shape

The icon can be used in two forms:

#### Standalone Mark

A clean V-shaped folder/folio icon.

Used for:

* favicon;
* app icon;
* sidebar icon;
* document watermark;
* verification badge;
* loading state.

#### Full Lockup

V-shaped folder mark + `Verifolio` wordmark.

Used for:

* landing page header;
* auth screens;
* PDF footer;
* email header;
* public verification page.

## Logo Construction

The logo should be built from simple geometric paper-like shapes:

```text
Left panel  — dark folded document side
Right panel — lighter folded document side
Inner fold  — soft paper highlight
```

The silhouette should clearly read as **V**, but also subtly suggest a **folder, document stack, or folded paper**.

Recommended properties:

* rounded corners;
* slightly folded paper edge;
* strong silhouette at small size;
* no excessive detail;
* no thin lines that disappear in favicon;
* no realistic paper texture in the logo itself.

## Logo Variants

### 1. Primary Color Logo

Used on light backgrounds.

```text
Mark: Deep Ink + Blue Gray + Paper Ivory
Accent: Verified Green
Wordmark: Deep Ink
```

### 2. Dark Background Logo

Used on dark surfaces.

```text
Mark: Paper Ivory + Blue Gray
Accent: Verified Green
Wordmark: Paper Ivory
```

### 3. Monochrome Logo

Used for stamps, watermarks, legal-looking documents, and low-color environments.

```text
Mark: Deep Ink or white
No gradients
No color accent
```

### 4. App Icon

Rounded square container with the V-folder mark centered.

Recommended:

```text
Background: Deep Ink
Icon: Paper Ivory / Blue Gray
Accent: Verified Green, very small
```

### 5. Verification Badge Variant

A compact version of the V-folder mark inside a small circular or rounded badge.

Used for:

* `Version Locked`;
* `Verified Document`;
* `Signature Attached`;
* public verification page trust summary.

## Color Palette

### Core Palette

```text
Deep Ink:        #0F1B2E
Midnight Navy:   #182033
Paper Ivory:     #F7F4EC
Warm White:      #FBFAF6
Soft Blue Gray:  #A5B4C4
Light Blue Gray: #DDE5EC
Trust Blue:      #2563EB
Verified Green:  #2EAD72
Muted Gold:      #C8A24D
Slate Text:      #475467
Muted Text:      #667085
Border Light:    #E4E7EC
Danger Red:      #D92D20
Warning Amber:   #F79009
```

Verified Green is the only green in the palette, and Muted Gold is the only gold. Do not introduce additional green or gold values.

Dark mode: out of scope for v1.

## Color Roles

### Deep Ink

Used for:

* main text;
* navigation;
* primary buttons;
* logo wordmark;
* document headers;
* sidebar background.

Meaning:

```text
Trust, seriousness, structure, security.
```

### Paper Ivory

Used for:

* document surfaces;
* cards;
* empty states;
* letter previews;
* warm backgrounds.

Meaning:

```text
Documents, letters, human warmth.
```

### Soft Blue Gray

Used for:

* secondary UI;
* inactive states;
* borders;
* panels;
* metadata blocks.

Meaning:

```text
Calm structure, neutrality, system clarity.
```

### Verified Green

Used only for actual positive verification states.

Used for:

* verified badges;
* success states;
* active trust signals;
* completed steps;
* confirmation icons.

Avoid using green for generic decoration.

Meaning:

```text
Confirmed, trusted, completed.
```

### Muted Gold

Used for:

* signature-related states;
* premium seal details;
* formal document accents;
* certificate-like elements;
* highlights in brand materials.

Meaning:

```text
Signature, seal, authority, value.
```

Gold should be subtle, not luxury-heavy.

### Trust Blue

Used for:

* informational states;
* links;
* focus rings (alternative to Verified Green);
* neutral verification metadata.

Meaning:

```text
Information, clarity, neutral trust.
```

## Semantic Colors

```text
Success:  Verified Green
Warning:  Amber
Error:    Red
Info:     Blue Gray / Trust Blue
Neutral:  Slate / Gray
Locked:   Deep Ink
Signed:   Muted Gold
```

## Typography

### UI Font

Recommended:

```text
Inter or Manrope
```

Use for:

* dashboard;
* forms;
* buttons;
* navigation;
* verification pages;
* product UI.

### Document Font

Recommended:

```text
Source Serif 4 or Literata
```

Use for:

* generated recommendation letters;
* PDF body;
* formal document previews.

### Typography Style

The system should feel:

* readable;
* calm;
* precise;
* professional;
* not overly corporate;
* not decorative.

## Type Scale

```text
Display:    48–64px / 1.05
H1:         36–44px / 1.15
H2:         28–32px / 1.2
H3:         22–24px / 1.3
Body L:     18px / 1.55
Body:       16px / 1.55
Body S:     14px / 1.45
Caption:    12px / 1.4
Micro:      11px / 1.3
```

## Border Radius

```text
Small controls:     8px
Buttons:            10–12px
Cards:              16px
Large panels:        20–24px
App icon:            22–28% radius
Badges:              full pill or 8px
```

The logo itself should use soft geometry with rounded edges, matching the product UI.

## Spacing System

Use an 8px spacing grid.

```text
4px   micro spacing
8px   compact spacing
12px  small gap
16px  default gap
24px  section gap
32px  large card gap
48px  major layout gap
64px  page section gap
96px  landing section gap
```

## UI Surface System

### Backgrounds

```text
Main app background:       #FBFAF6 (Warm White — the single app background token)
Card background:           #FFFFFF
Document background:       #F7F4EC
Dark sidebar:              #0F1B2E
Verification page surface: #FFFFFF
```

### Borders

Use subtle borders instead of heavy shadows.

```text
Default border: #E4E7EC
Soft border:    #EEF1F4
Focus border:   Verified Green or Trust Blue
```

### Shadows

Keep shadows soft and minimal.

```text
Card shadow:
0 8px 24px rgba(15, 27, 46, 0.06)

Floating panel:
0 16px 40px rgba(15, 27, 46, 0.10)

Document preview:
0 24px 60px rgba(15, 27, 46, 0.12)
```

## Buttons

### Primary Button

```text
Background: Deep Ink
Text: White
Hover: slightly lighter navy
Radius: 10–12px
```

Used for:

* Create request;
* Continue;
* Send request;
* Share document.

### Secondary Button

```text
Background: White
Border: Border Light
Text: Deep Ink
```

Used for:

* Save draft;
* Preview;
* Download;
* Cancel.

### Success Button

Use rarely.

```text
Background: Verified Green
Text: White
```

Used for:

* Confirm;
* Verify;
* Submit final response.

### Dangerous Button

```text
Background: White or Red
Text: Red
Border: Red-tinted border
```

Used for:

* Revoke link;
* Delete draft;
* Expire request.

## Cards

Cards are the core UI element.

### Reference Card

Should show:

* document title;
* document type;
* recommender;
* relationship;
* status;
* trust badges;
* last activity date.

### Document Card

Should show:

* document version;
* locked status;
* PDF status;
* attached files;
* share state;
* signal badge list (never a numeric verification score).

### Trust Signal Card

Should show:

* icon;
* signal name;
* status;
* date;
* short explanation.

### Request Template Card

Should show:

* template type;
* icon;
* purpose;
* expected evidence;
* recommended verification options.

## Badges

Badges are essential to the product.

### Badge Style

```text
Shape: pill or soft rounded rectangle
Height: 24–28px
Icon: 12–14px
Text: 12–13px
Weight: medium
```

### Verification Badges

```text
Email Confirmed
Corporate Domain
Recipient Confirmed
Relationship Confirmed
Scan Attached
Signature Attached
Signature Verified
Version Locked
Identity Verified
Public Verification
```

### Badge Color Rules

```text
Verified:      soft green background + dark green text
Signed:        soft gold background + dark gold text
Locked:        navy-tinted background + deep ink text
Pending:       gray background + slate text
Failed:        red-tinted background + red text
Expired:       amber/gray background + warning text
```

Do not make all badges green. Each badge should communicate its trust type.

## Icons

Icon style:

* outline or duotone;
* rounded stroke caps;
* 1.5–2px stroke;
* simple geometry;
* no excessive detail.

Core icon metaphors:

```text
Document
Folder
Seal
Check
Lock
Signature
Mail
Domain / Building
Clock
Shield
QR code
Link
Download
Upload
User verified
```

The V-folder logo should inspire the icon style: folded paper, soft geometry, structured lines.

## Product Components

### Request Builder

Visual direction:

* guided;
* calm;
* step-by-step;
* template cards;
* right-side completeness panel.

Core components:

```text
Stepper
TemplateCard
QuestionBlock
CompletenessHint
EmailPreview
VerificationOptions
```

`CompletenessHint` is a private profile/request-completion hint for the owner only. It must never be presented as a trust indicator and never appears on public pages.

### Recommender Form

Visual direction:

* respectful;
* low-friction;
* not bureaucratic.

Core components:

```text
RequestSummary
GuidedQuestions
RichTextLetterEditor
UploadScanCard
AttachSignatureCard
ConfirmationCheckboxes
SubmitPanel
```

### Public Verification Page

Visual direction:

* transparent;
* credible;
* evidence-first.

Core components:

```text
VerificationHeader
RecipientBlock
RecommenderBlock
TrustSummary
DocumentPreview
VerificationTimeline
DownloadPanel
SignatureInfo
DisclaimerBlock
```

### Profile Verification

Visual direction:

* progressive trust;
* optional;
* not KYC-heavy.

Core components:

```text
SignalBadgeList
TrustSummary
VerificationMethodCard
ProfessionalLinks
NameConsistencyCheck
IdentitySignalList
```

`TrustSummary` shows counts of confirmed signals grouped by category. It must never render a percentage, progress bar, or single aggregated number. There is no numeric trust score anywhere in the product (see `VERIFICATION_SIGNALS.md`).

Self-declared recommender fields (relationship, title, organization name without a verified record) must always be labeled "stated by recommender".

## Layout Principles

### Landing Page

Should feel premium and calm.

Hero should include:

* headline;
* short explanation;
* CTA;
* product mockup;
* trust badges.

Recommended hero copy:

```text
Professional references and documents, verified.

Collect, verify, sign, and share professional references for work,
immigration, visas, education, and clients.
```

### App Dashboard

Should feel like a professional document workspace.

Main sections:

```text
Trust overview
Recent references
Pending requests
Document cards
Verification status
Recent activity
```

### Verification Page

Should not look like a marketing page.

It should feel like:

```text
evidence report + document preview + trust summary
```

## Motion

Motion should be subtle.

Allowed:

* soft card hover;
* step transitions;
* badge confirmation animation;
* upload progress;
* document preview loading;
* verification success micro-animation.

Avoid:

* playful bouncing;
* excessive confetti;
* flashy transitions;
* gamified trust effects.

## Illustration Style

Use sparingly.

Illustrations should show:

* documents;
* folders;
* seals;
* signatures;
* QR codes;
* secure links;
* verification timeline.

Avoid:

* generic people with laptops;
* overly playful mascots;
* legal/gavel imagery;
* blockchain chains/cubes.

## Document / PDF Style

Generated PDFs should feel more formal than the web UI.

PDF style:

```text
Header: clean wordmark
Body: serif or highly readable font
Footer: verification ID + QR code
Seal: subtle Verifolio mark
Metadata block: document type, version, locked date
```

PDF should include:

* document title;
* recipient;
* recommender;
* date;
* letter content;
* verification QR;
* verification ID;
* optional signature/scan notes.

## Voice & Copy

Tone:

* clear;
* honest;
* calm;
* precise;
* human.

Do not say:

```text
This recommendation is 100% authentic.
We guarantee that all information is true.
Officially certified by Verifolio.
```

Say:

```text
This document was confirmed by the recommender.
This version was locked after submission.
A scan was attached by the recommender.
The signature file was attached and verified.
Verifolio shows verification evidence, but does not independently guarantee every statement.
```

## Accessibility

Minimum requirements:

* WCAG-friendly contrast;
* keyboard navigation;
* visible focus states;
* semantic headings;
* accessible form labels;
* readable badge states without color alone;
* PDF preview fallback;
* screen-reader-friendly verification summaries.

## Design Tokens

Recommended token groups:

```text
color.background.*
color.text.*
color.border.*
color.status.*
color.brand.*
space.*
radius.*
shadow.*
font.*
fontSize.*
lineHeight.*
badge.*
button.*
card.*
```

Example:

```text
color.brand.ink = #0F1B2E
color.brand.paper = #F7F4EC
color.brand.green = #2EAD72
color.brand.gold = #C8A24D
radius.card = 16px
radius.button = 12px
shadow.card = 0 8px 24px rgba(15, 27, 46, 0.06)
```

## Design System Summary

Verifolio should feel like a **secure professional folio**.

The V-shaped folder logo defines the visual language:

```text
V = Verified
Folder = Professional proof archive
Folded paper = Letters and documents
Green accent = Confirmation
Gold accent = Signature and seal
Deep navy = Trust and structure
Ivory = Human document warmth
```

The design system should make every important interaction feel like:

```text
I know what this document is.
I know who confirmed it.
I know what evidence supports it.
I understand the limitations.
I can trust the process.
```
