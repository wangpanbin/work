---
name: Star Cinema 星辉影城
description: 冷靛蓝票根印刷质感 —— 影院抢票选座系统的视觉系统
colors:
  paper: "#eef1f6"
  paper-raised: "#f8fafc"
  paper-sunk: "#e2e8f0"
  rule: "#bac5d7"
  rule-strong: "#a8b5c9"
  ink: "#0f172a"
  ink-2: "#475569"
  ink-3: "#576579"
  ink-inverse: "#f8fafc"
  accent: "#2563eb"
  accent-hover: "#1d4ed8"
  accent-press: "#1e40af"
  accent-wash: "rgba(37, 99, 235, 0.08)"
  accent-stamp: "rgba(37, 99, 235, 0.14)"
  accent-disabled: "#a7bfe8"
  accent-rgb: "37 99 235"
  scene-browse: "#f97316"
  scene-browse-ink: "#9a3412"
  scene-browse-rgb: "249 115 22"
  scene-seat: "#0891b2"
  scene-seat-ink: "#155e75"
  scene-seat-rgb: "8 145 178"
  scene-order: "#7c3aed"
  scene-order-ink: "#5b21b6"
  scene-order-rgb: "124 58 237"
  scene-payment: "#b21f96"
  scene-payment-ink: "#701a75"
  scene-payment-rgb: "178 31 150"
  ok: "#2a6b52"
  warn: "#86691c"
  danger: "#a8271b"
  danger-hover: "#8f2015"
  warn-hover: "#6e5516"
  hall: "#0f172a"
  hall-raised: "#16203a"
  hall-rule: "#2c3a55"
  hall-ink: "#e2e8f0"
  hall-ink-2: "#93a3ba"
  hall-mine-bar: "#6ee0ae"
  hall-scene: "var(--scene-seat)"
  hall-glow: "rgb(var(--scene-seat-rgb) / 0.22)"
typography:
  display:
    fontFamily: "Noto Serif SC, Source Han Serif SC, Songti SC, Georgia, serif"
    fontSize: "28px"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "1px"
  headline:
    fontFamily: "Noto Serif SC, Source Han Serif SC, Songti SC, Georgia, serif"
    fontSize: "22px"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "2px"
  title:
    fontFamily: "Noto Serif SC, Source Han Serif SC, Songti SC, Georgia, serif"
    fontSize: "24px"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "1px"
  body:
    fontFamily: "Noto Sans SC, PingFang SC, Microsoft YaHei, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.6
    letterSpacing: "normal"
  label:
    fontFamily: "IBM Plex Mono, Roboto Mono, ui-monospace, Consolas, monospace"
    fontSize: "11px"
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: "0.02em"
rounded:
  sm: "2px"
  md: "3px"
  lg: "4px"
  xl: "6px"
spacing:
  docOnly: true
  xs: "6px"
  sm: "12px"
  md: "16px"
  lg: "24px"
  xl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.ink-inverse}"
    rounded: "{rounded.md}"
    height: "36px"
    padding: "0 18px"
  button-primary-hover:
    backgroundColor: "{colors.accent-hover}"
  button-primary-active:
    backgroundColor: "{colors.accent-press}"
  button-secondary:
    backgroundColor: "{colors.paper-raised}"
    textColor: "{colors.ink-2}"
    rounded: "{rounded.md}"
    height: "36px"
    padding: "0 18px"
  header-chip:
    backgroundColor: "{colors.paper-sunk}"
    textColor: "{colors.ink-2}"
    rounded: "{rounded.md}"
    height: "36px"
    width: "136px"
  header-chip-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.ink-inverse}"
    rounded: "{rounded.md}"
    height: "36px"
    width: "136px"
  card:
    backgroundColor: "{colors.paper-raised}"
    textColor: "{colors.ink-2}"
    rounded: "{rounded.lg}"
    padding: "16px"
  input:
    backgroundColor: "{colors.paper-raised}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    height: "36px"
---

# Design System: Star Cinema 星辉影城

## Overview

**Creative North Star: "The Printed Ticket Stub"**

This is a piece of printed matter that happens to be on a screen. Every surface behaves like a cinema ticket that has been through a press: cold grey-white paper stock, one global ink colour, hairline rules doing all the structural work, and a perforated tear separating the stub from the ticket body. At rest, nothing floats and nothing glows. A card is a sheet lying on other sheets, and the only thing that distinguishes it is that it is a shade brighter than what's underneath.

The density is that of a box-office counter — information-dense, aligned, unemotional, and quick to scan. Structure comes from 1px rules and tonal steps rather than shadows, gradients, or fills; the tabular rhythm is carried by monospaced figures so that ticket numbers, showtimes, amounts and countdowns never reflow when a digit changes. Expression is spent in exactly three places: the single global royal-blue ink, the small library of physical printing devices (perforation notches, mono ticket numbers, the short ink bar that opens a section), and — from 2026-10-19 — one **scene ink** per business domain, spent only on that domain's own small surfaces.

The one deliberate exception is the auditorium: the seat map is a single dark ticket face mounted on the paper, and the admin sidebar is its structural twin. That dark family is the same ink colour inverted, not a second identity — it exists so five saturated seat states stay separable instead of collapsing into grey.

**Key Characteristics:**

- Cold grey-white paper stock. Never warm, never cream.
- One **global** ink colour. Its rarity is the point. Each business domain adds exactly one **local** scene ink, and that scene ink never leaves its domain.
- Hairline 1px rules carry structure; shadows do not.
- Monospaced tabular figures for anything that must not reflow.
- One dark family (`#0f172a`) shared by the admin sidebar and the auditorium panel.
- Perforated tear notches as the recurring signature device.
- Near-zero corner radii — a printed sheet has no rounded corners.

**Confirmed visual rejections** (committed in the implementation, do not reintroduce):

- Colourful emoji as iconography or decoration.
- Gold gradients and 40px glows.
- Hover translation or lift on **primary actions** — a primary button answers with a darker ink, never with motion. (Non-primary interactive surfaces may move, but only under The Flat-By-Default Rule below.)
- A second **global** brand or accent colour. A scene ink is a domain-local exception, not a second global colour — see The Scene Ink Rule.
- A scene ink on a global emphasis surface — primary CTA, link, focus ring, section title bar, hairline border, or nav active state.
- A scene ink outside its own domain.
- Shadows used as rest-state decoration rather than as a response to state.

## Colors

A cold, restrained palette: one high-chroma royal blue against a five-step cool grey-white paper scale, with a single inverted dark family for the auditorium and the admin shell. From 2026-10-19, each business domain additionally carries its own **scene ink** — one hue per domain, spent only on that domain's small surfaces.

### Primary

- **Royal Stamp Blue** (#2563eb): the only **global** ink on the sheet. Primary buttons, links, active nav, focus rings, the section-title bar, `::selection`, the brand mark, and the single "highlighted" stat card. Its rarity is deliberate — it is used on well under a tenth of any screen, and it never changes hue between domains.
- **Royal Stamp Blue, pressed** (#1d4ed8 / #1e40af): hover and active steps. Depth comes from darkening the ink, never from lifting the element.
- **Stamp wash** (rgba(37, 99, 235, 0.08) / 0.14): the tint used for the highlighted card background and the stamped badge. Tints are always built with `rgb(var(--accent-rgb) / a)`, never `rgba(var(--accent-rgb), a)` — the comma form is invalid CSS and the browser silently drops the whole declaration.
- **Ink, disabled** (#a7bfe8): the disabled primary, lightened so "unavailable" reads as a separate state rather than a dimmed active one.

There is no global secondary or tertiary ramp. `themeTokens.test.ts` asserts there are exactly seven `--accent*` declarations in `:root` (base / hover / press / wash / stamp / disabled / rgb); an eighth means someone added a second **global** brand colour.

### Scene Identity Inks (domain-local, 2026-10-19)

A global ink cannot carry every role. Before this change, the same #2563eb was simultaneously a badge, a price, a play button, a full-bleed background and a decorative dot — which is why the interface read as one flat tone. The fix is not "more blues"; it is **one hue per role**, scoped to a domain.

| Domain | Scene | base | ink | rgb |
| --- | --- | --- | --- | --- |
| Browse | `Home`, `MovieDetail` | `#f97316` marquee orange | `#9a3412` | `249 115 22` |
| Seat | `SeatSelect`, `SeatItem` | `#0891b2` projector cyan | `#155e75` | `8 145 178` |
| Order | `OrderList` | `#7c3aed` violet | `#5b21b6` | `124 58 237` |
| Payment | `Payment` | `#b21f96` deep magenta | `#701a75` | `178 31 150` |

Each family has exactly three members: **base** (graphic, fill, chart series), **ink** (text-safe, ≥4.5:1 on every paper tone) and **rgb** (for `rgb(var(--scene-x-rgb) / a)` washes). `themeTokens.test.ts` asserts the family count, the member set, the ink contrast, the ≥25° hue distance from the global ink, and the separation from any semantic colour that co-occurs in the same domain.

**Why order is violet and not green.** The order domain's own semantic colours already include `--ok` (the PAID status tag) and `--warn` (PENDING_PAY / REFUNDING), plus a `--danger` pulse dot. A green identity ink would sit one hue away from the "已支付" green tag on the same card, and green in this product already *means* paid. Violet clears `--ok` by 105°, `--warn` by 142° and `--danger` by 103°.

### The Console Rule (admin, 2026-10-20)

The admin console is not a ticket. It is a dense report surface, and it keeps its own language: the midnight sidebar, the cool paper sheets, tabular figures. It borrows the *method* of the scene inks — a colour earns its place by naming a role — but none of the ticket symbolism.

**It has no identity ink of its own.** The wheel is already spoken for: one global ink, four scene inks, three semantic colours. A fifth identity hue would either collide with one of them or need a separation margin the palette cannot spare. So the console re-assigns the colours it already has:

| Role | Colour | Where |
| --- | --- | --- |
| **Data ink** | `--ink` / `--ink-2` | KPI figures, table figures, IDs, axis labels — the numbers you scan |
| **Interaction ink** | `--accent` | Buttons, links, focus rings, nav active state — the things you can act on |
| **Chart ink** | `--accent` | Trend line and area, ranking bars, occupancy bars — the shapes you look at |
| **Status** | `--ok` / `--warn` / `--danger` | Connection state, event types, validation errors |
| **Structure** | `--hall` family, `--rule` | Sidebar, hairlines, sunken surfaces |

The KPI row reads the same way on every console page: **one headline card, wider than the rest** (1.5fr against 1fr), marked by a 3px ink bar down its left edge — hierarchy by *structure*, not by colour. Every KPI figure is ink, the headline's included; the headline differs by size and by that bar. When a colour would have to name two roles at once, the figure gives it up and the shape keeps it.

`themeTokens.test.ts` asserts the console never reaches for a `--scene-*` family, so this cannot drift.

**Why payment is a deep magenta and not a pink.** The first pink candidate was measured and rejected: it fails the same-domain separation against `--danger` (grey gap 20, saturation gap 0.009) *and* neither ink colour clears 4.5:1 on it. The deep magenta clears `--danger` by 54° and carries white type at 5.66:1, which is also what the dark ticket face needs.

### Neutral

- **Cold Tally Paper** (#eef1f6): the page sheet, and the notch colour of every perforation.
- **Raised Sheet** (#f8fafc): cards, tickets, table bodies — anything floating one step above the page.
- **Sunk Sheet** (#e2e8f0): table headers, pressed fills, the nickname chip — anything recessed.
- **Hairline** (#bac5d7) and **Strong Rule** (#a8b5c9): the two 1px line weights. `--rule` is for card and input borders, `--rule-strong` for tear lines, table frames, and the strong edge of a hover state. The pair is locked in that order: `--rule` must stay lighter than `--rule-strong`.
- **Counter Ink** (#0f172a): primary text, and the anchor of the dark family.
- **Ledger Indigo** (#475569) and **Faded Counter Ink** (#576579): secondary and tertiary text. The tertiary value is contrast-locked at ≥4.5:1 on all three paper tones, so it can carry body copy and not just metadata.
- **Ticket Reverse** (#f8fafc): text sitting on ink.

### The Dark Family (structural, not a second palette)

- **Midnight Auditorium** (#0f172a): the seat-map panel and the admin sidebar share this one value, deliberately — they are the same dark surface seen from two sides of the counter.
- **Auditorium Raised** (#16203a) and **Auditorium Rule** (#2c3a55): the panel's own elevated surface and hairline.
- **Auditorium Text** (#e2e8f0) and **Auditorium Text Muted** (#93a3ba): the inverted ink pair.
- **My-Seat Beacon** (#6ee0ae): a single mint bar, used only as the left edge of seats the current user holds. It is the one non-blue chroma permitted inside the auditorium, and it exists because the locked/selected green needed a non-colour channel.

### Named Rules

**The One Global Ink Rule.** There is one **global** accent. It is applied to less than a tenth of any given screen, and it is never duplicated by a second global hue. It does not change hue between pages, so a primary button means the same thing everywhere. When something needs emphasis and blue is already spent, reach for a heavier rule weight or a darker ink, not a new colour.

**The Scene Ink Rule.** A business domain may carry one **scene ink**, and it is bound to that domain for good. Four rules govern it:

1. **Small surfaces only.** A scene ink may appear on badges, dots, progress fills, chart series, ticket stamps and icon chips — nothing else. It is never allowed on a global emphasis surface: primary CTA, link, focus ring, section title bar, hairline border, or nav active state. Those stay `--accent`.
2. **Three members, no more.** Every scene family is exactly `base` / `ink` / `rgb`. `base` is a graphic colour and is never used for body text. `ink` is the text-safe member and must clear 4.5:1 on every paper tone.
3. **Type on a base fill picks the ink that reads.** Not a fixed member: the text on a `base` fill uses whichever of `--ink` / `--ink-inverse` clears 4.5:1 against that base. Light bases (browse orange, seat cyan) take `--ink`; dark bases (order violet, payment magenta) take `--ink-inverse`. The first version of this rule hard-coded `--ink` and was simply wrong for the two dark families.
4. **Never leaves its domain, and never reads as a semantic colour.** A scene ink used outside its own domain is a bug, not a style choice; `themeTokens.test.ts` asserts the domain boundary file by file. Inside its domain it must stay clear of every semantic colour that appears on the same screen — **hue distance ≥25° OR grey-level gap ≥25 OR saturation gap ≥0.25**. The hue channel is not optional: the semantic colours are low-chroma and dark, so *any* saturated mid-tone magenta or violet shares their lightness band and their saturation band, and grey/saturation alone cannot separate them (measured: magenta vs `--danger` gives grey gap 5–20 and saturation gap 0.00–0.11). Which semantic colours a domain inherits is **derived from the code** — every `var(--ok)` / `var(--warn)` / `var(--danger)` in the domain's files, plus the ones implied by `el-tag type="success|warning|danger"` — so a domain that grows a new semantic colour cannot slip past the gate.

**The Inverted Family Rule.** Dark surfaces are the ink colour inverted, never a new palette. The sidebar and the auditorium must stay the same value, because it is one family.

**The Slash-Syntax Rule.** Alpha is composed as `rgb(var(--x-rgb) / a)`. The comma form `rgba(var(--x-rgb), a)` is invalid CSS — the browser drops the declaration silently and the surface turns transparent.

## Typography

**Display Font:** Noto Serif SC (with Source Han Serif SC, Songti SC, Georgia, serif)
**Body Font:** Noto Sans SC (with PingFang SC, Microsoft YaHei, system-ui)
**Mono Font:** IBM Plex Mono (with Roboto Mono, ui-monospace, Consolas)

**Character:** A serif headline over a neutral sans body reads as printed matter rather than as software chrome; the monospace third voice is what makes it a *ticket* — everything with a number in it is set in mono with tabular figures so the sheet never jitters.

### Hierarchy

- **Display** (700, 28px, line-height 1.3, letter-spacing 1px): the admin welcome headline. The largest type in the system; nothing else competes at this size.
- **Title** (700, 24px, line-height 1.3, letter-spacing 1px): section titles, marked by the 3px ink bar on their left edge.
- **Headline** (700, 22px, line-height 1.3, letter-spacing 2px): the wordmark in both headers. Wide tracking is what makes two Chinese characters read as a brand mark.
- **Body** (400, 14px, line-height 1.6): the default voice for every paragraph, label, table cell, and button.
- **Label** (400, 11px, mono, letter-spacing 0.02em; badges push to 4px and uppercase — the mono face loads 400/600 only, so no code may ask for 500): ticket numbers, column labels, the `ADMIN CONSOLE` eyebrow, and any figure that must not reflow.

### Named Rules

**The Ticket Number Rule.** Anything that is a number on this system is set in mono with `font-variant-numeric: tabular-nums` — order numbers, showtimes, amounts, countdowns, seat labels, row/column labels, and dashboard statistics. Proportional figures are a bug here: a ticking countdown must not move its own frame.

**The Two-Voice Rule.** Serif is for headings and the brand mark only. Body copy never sets in serif, and headings never set in the body sans. The contrast between the two is the hierarchy.

## Layout

A single centred column of printed sheets, 1280px maximum, with 32px vertical and 24px horizontal breathing room. The header is a 64px sticky strip bounded by a hairline below it; the page sheet shows through everywhere else. Cards are sheets separated by 12–16px gutters and, in dashboards, laid out as 4 equal columns that fold to 2 below 768px.

**Asymmetry (2026-10-19).** A column of equal sheets is a box-office ledger, not a cinema. Three surfaces deliberately break the grid while keeping the printed language:

- **Home hero** is an unequal two-part ticket: the main stub takes the free width, the data stub is a fixed 260px column. The perforation between them is unchanged — only the proportion moved.
- **The film grid** is a 12-column grid where the first film takes 6 columns and a wider poster, and the rest take 3. Below 768px it collapses back to an equal `auto-fill` grid, and below 480px to two columns.
- **The session list** is a ticket, not a table row: a large tabular start time on the left, the hall in the middle, and the price stamped in ink on the right, with the booking button last.

A row of identical cards was the shape the previous design kept repeating; the asymmetry is where the colour has room to land.

**Round two (2026-10-20), the same moves applied to the remaining pages:**

- **Order card** — the order number leaves the footer and moves to the top-right of the main coupon, where it joins the status tag as a metadata cluster. The number is the one thing on a ticket that reads as a serial, so it is set as an **order-ink stamp**. The footer keeps time, amount and actions.
- **Payment** — the only page where a ticket has already been issued, so the whole card becomes a **dark ticket face** in the auditorium family (`--hall`), with a payment-ink bar along its bottom edge. The perforation stays on the top edge; the identity bar is the border, not a pseudo-element, so the tear device is untouched.
- **Login** — the stub's three rows (showtime, hall, seat) stop being a two-column table and become three stacked rows of label-plus-large-tabular-value, the same reading order as the home page's stub. An ink bar runs along the stub's top edge, and focused inputs grow a small ink tick on their left edge.
- **Not found** — 404 is a ticket too: the number becomes the ticket's serial, set as an ink stamp beside the brand mark, on a torn coupon with the perforation along its top edge.

The admin shell breaks the model on purpose: it goes full-bleed with a fixed 220px sidebar and a 20/24px content padding, because a data console needs the width more than it needs the margin. The seat page also breaks it: the auditorium panel is a full-width dark ticket face, with the seat grid inside it using `32px + repeat(cols, minmax(28px, 1fr))` columns and a 6px gap so row labels align with seats.

Responsive behaviour is three stepped reductions rather than a fluid rescale:

- **≤768px** — header drops to 56px, page padding to 20/16px, the admin sidebar is removed and replaced by a horizontally scrolling sticky nav at `top: 64px`, and dashboard cards fold 4 → 2.
- **≤640px** — header chips collapse to icon-only 36px squares and the nickname chip disappears, which is what keeps a long Chinese name from breaking the header.
- **≤480px** — a final tightening of header padding and chip gaps.

## Elevation & Depth

This system is **flat by default and tonal everywhere**. Depth is conveyed almost entirely by the paper scale — page, raised, sunk — with a 1px hairline marking the edge of whatever has risen. Shadows exist only for things that genuinely leave the sheet: overlays, toasts, and a barely-there 1px lift under cards.

### Shadow Vocabulary

- **Paper lift** (`0 1px 0 rgba(15, 23, 42, 0.05), 0 6px 16px rgba(15, 23, 42, 0.03)`): the rest state of every card and ticket. It reads as the sheet's own thickness, not as a drop shadow.
- **Paper lift, hover** (same plus `0 8px 20px rgba(15, 23, 42, 0.07)`): reserved for cards that are actually interactive, on hover only.
- **Modal** (`0 12px 32px rgba(15, 23, 42, 0.14)`): dialogs, message boxes, poppers, and toasts — the only convincing shadow in the system.
- **Auditorium edge** (`0 1px 0 rgba(15, 23, 42, 0.1)`): the single-pixel separation used inside the dark panel.
- **Chart tooltip** (`0 2px 8px rgba(15, 23, 42, 0.12)`): chart hover readouts.
- **Scene glow** (`rgb(var(--scene-x-rgb) / a)`, blur ≤ 8px): a domain-local bloom reserved for scene-ink elements on a dark ground. Never a 40px halo, never a gradient.

### Named Rules

**The Flat-By-Default Rule.** Surfaces are flat at rest — nothing lifts, glows or translates until the user touches it. On interaction, motion is allowed, but only under all three of these limits:

- **Where.** The surface must sit on a dark ground (the `--hall` family), or carry a strong edge (`--rule-strong`), or be a scene-ink element. A card floating on plain paper with a hairline does not move.
- **How much.** Displacement ≤ 2px, bloom ≤ 8px. Anything larger reads as a different design language.
- **When.** Motion answers a state; it never loops. A primary button still answers with a *darker ink*, never with a raise. `prefers-reduced-motion: reduce` collapses all of it, globally.

## Shapes

The form language is a printed sheet: hairline borders, near-zero radii, and no ornamental rounding. Radii run 2/3/4/6px, which at these sizes reads as "squared with a softened corner" rather than as a rounded rectangle. Everything structural is a 1px line, in one of exactly two weights.

Three silhouette devices carry the identity:

- **The seat.** A square top with a visibly deeper bottom radius (`4px 4px 8px 8px`) — the seat-back-and-cushion read, small enough that it registers as a chair only at a glance.
- **The perforated tear.** A repeating radial-gradient punching half-circle notches along a container edge (`.stub-perf` vertical, `.stub-perf-x` horizontal) so two sheets look physically separable.
- **The ink bar.** A 3px royal-blue bar: to the left of section titles, and `inset 3px 0 0` inside the active admin nav item.

## Components

### Buttons

- **Shape:** squared with a softened corner (3px radius), 1px border, 36px height, 0 18px padding, 14px/500 label. A fixed-width variant snaps to 128px for stacked action rows.
- **Primary:** solid royal ink with ticket-reverse text. No shadow, no gradient, no lift.
- **Hover / Focus:** hover darkens the ink to `#1d4ed8`, active presses to `#1e40af`; the transition is colour-only at 0.15s. Keyboard focus is a 2px accent outline with 2px offset, drawn globally (`main.css :focus-visible`) so it survives on both paper and dark surfaces — accent reads 3.5:1 against the dark family, clearing the 3:1 non-text bar.
- **Disabled:** the ink is lightened to `#a7bfe8` so it reads as a distinctly unavailable state instead of a dim active one.
- **Secondary:** raised sheet with a hairline border and counter-ink text; hover steps the surface down to the sunk tone and the border up to the strong weight. Danger and warning variants are the semantic solids, each with exactly one darker hover step.

### Header Chips

A chip is the header's only interactive primitive, and the system insists they are pixel-identical: 36px tall and 136px wide across every variant, including the non-interactive nickname display. **Variants change colour only, never size** — that locked geometry is what lets a desktop header show three chips and a long nickname without a ragged baseline. Long names are truncated inside the chip with an ellipsis rather than allowed to push the row. Primary and login chips are solid ink; the danger chip is outlined ink-on-paper; the warning (admin entry) chip is a hairline outlined neutral that fills on hover.

### Cards / Containers

- **Corner Style:** 4px, with the largest surfaces at 6px.
- **Background:** the raised sheet; the single "highlighted" stat card uses the stamp wash.
- **Border:** 1px hairline at rest, stepping to the strong weight on hover for interactive cards only.
- **Shadow Strategy:** the paper-lift pair from Elevation & Depth — the sheet's thickness, nothing more.
- **Internal Padding:** 16px for stat cards, 28/24px for hero panels.

### Inputs / Fields

- **Style:** raised sheet, hairline border, 3px radius, 36px height, body-sized text; Element Plus supplies the mechanics and the tokens supply the skin.
- **Focus:** the global 2px accent outline — the same treatment as every other focusable thing, which is what keeps focus legible on paper and on the dark shell.
- **Error:** validation text is pressed to the semantic danger rather than Element Plus's brighter default.

### Navigation

- **Admin sidebar:** a 220px Midnight Auditorium panel with its own scoped tokens, a 72px brand block on top, and 48px menu items. The active item is **not** a solid blue block — on a dark ground that muddies into the background. It is a 16% ink tint plus a 3px inset ink bar, measured at 12.49:1 against its composited ground. Hover is a raised dark surface, and the keyboard focus ring is drawn explicitly because Element Plus's default is invisible on dark.
- **Mobile admin nav:** a horizontally scrolling sticky strip below the 64px header, items at 36px with the same 3px-radius geometry; the active item is the one place a filled ink pill is used, because there is no bar room at that size.

### Seat Map (signature)

The auditorium is the only dark surface in the customer-facing product, and it is dark for a functional reason: five saturated seat states stay separable against it in a way they would not against paper.

Seats are square with a deepened bottom radius, set in mono at 11px, and separated by 6px in a grid that keeps row/column labels aligned. Every state is distinguished by **colour and a non-colour channel together** — this is the load-bearing requirement, not a nicety:

| State | Ground | Non-colour channel |
| --- | --- | --- |
| Available | `#1e2a40` | 1px solid hairline |
| Selected (mine, pending) | Royal ink | 2px solid edge in the pressed ink (`--accent-press`) |
| Locked (mine) | `#2f6b4f` | 1px solid edge |
| Locked (someone else) | transparent | 1px dashed edge |
| Sold | `#151f33` | 45° stripe fill |

A conflict flash adds a red pulsing ring for 1.8s when a seat the user had selected is taken by someone else. Grayscale separation is asserted in code: the five grounds must keep adjacent luminance gaps of at least 2/255, which is why the locked-seat green is untouchable — moving it collapses the gap to 1.

Drag-selecting a run of seats previews the range with an **outline**, never a fill. The five grounds are untouchable: `themeTokens.test.ts` asserts the exact set of `--seat-*` variables, and a sixth seat colour would compete with the five for the same luminance band. The preview outline is the seat domain's scene ink, drawn outside the seat box, so it adds no ground value and therefore no luminance level of its own.

### Ticket Stub Devices (signature)

The perforation, the mono ticket number, the ink-bar section title, and the hand-drawn ticket-stub SVG in the header are the system's only decoration, and they all come from the same physical metaphor. They are cheap (pure CSS or one inline SVG), they never carry information that isn't also stated in text, and they are the reason the interface reads as one product rather than as a themed admin template.

## Do's and Don'ts

### Do:

- **Do** route every colour through the `:root` tokens in `src/styles/main.css`. Changing a token changes the whole site, including the Element Plus ramp and the chart series. The favicon is a static data-URI in `index.html` that tokens cannot reach — `themeTokens.test.ts` re-derives it from the current tokens and fails the suite if the two drift apart.
- **Do** compose alpha as `rgb(var(--x-rgb) / a)`.
- **Do** keep the **global** ink at under a tenth of any screen, and answer "I need more emphasis" with a heavier rule or a darker ink.
- **Do** reach for a **scene ink** when a role genuinely needs its own hue — a badge that says "now playing", a price stamp, a seat-map glow. One hue per role, one role per hue.
- **Do** keep a scene ink on small surfaces only, and inside its own domain.
- **Do** set every figure that can change in mono with tabular figures.
- **Do** give seat states and any colour-coded status a non-colour channel (edge weight, dash, stripe, bar) alongside the fill.
- **Do** keep the five seat grounds at adjacent grayscale gaps ≥2/255.
- **Do** reuse the shared seat/legend token pair rather than restating seat colours in the legend.
- **Do** draw a visible focus ring on any dark surface; Element Plus's default disappears there.
- **Do** honour `prefers-reduced-motion` — it is already wired globally.

### Don't:

- **Don't** hard-code a colour in a `.vue` file. The only permitted literals are the payment QR code's white and `#111827` and the token declarations themselves.
- **Don't** add a second **global** brand colour. Exactly seven `--accent*` declarations exist in `:root`; an eighth fails the test suite.
- **Don't** put a scene ink on a global emphasis surface — primary CTA, link, focus ring, section title bar, hairline border, or nav active state. Those stay `--accent`.
- **Don't** use a scene ink outside its own domain.
- **Don't** set body text in a scene `base`; use the `ink` member, and use `--ink` (not `--ink-inverse`) for type sitting on a base fill.
- **Don't** lift, translate, glow, or gradient a primary action on hover. Darken the ink instead.
- **Don't** use colourful emoji as icons, decoration, or section markers.
- **Don't** put a solid blue block on the dark sidebar as an active state; use the tint plus the 3px inset bar.
- **Don't** reintroduce a shadow as rest-state decoration on a non-overlay surface.
- **Don't** round a corner past 6px, or give a sheet a pill silhouette.
- **Don't** set body copy in the display serif, or a heading in the body sans.
- **Don't** change any `--seat-*` value without re-running the grayscale-separation assertion.
- **Don't** change a colour in one place only. `DESIGN.md`'s front-matter and `main.css` hold the same palette, and `designDocSync.test.ts` fails the suite if the two drift apart.
- **Don't** delete the table's `white-space: nowrap` rule — it is a functional fix that stops snowflake IDs from wrapping and inflating table rows, not a style preference.