# Mobile design direction

- **Status:** approved 2026-11-05 with the amendments below (they take precedence over the sections that follow).
- **Branch:** `feat/mobile-design-refresh`

**Amendments (2026-11-06, after the first Home review):**

- **Keep the brand colours.** The palette stays as in `src/theme` (navy card, navy primary, pale-blue icon circles, distinct currency badge colours); §2.1's single-accent neutral palette is not applied. The audit's structural fixes still apply: tokens for spacing, radii and type, fewer type sizes, arrows only on "next step" buttons, touch targets ≥ 44 px, no marketing copy except "Money, without borders." on Welcome. System font, no custom font.
- **Home (replaces §2.7 "After"):** the navy balance card stays and is the wallet switcher: swipe horizontally between the 6 wallets (page dots under the card) or tap the currency code to open a picker sheet. The "Your wallets" list leaves Home. Quick actions: Add money, Send (primary, navy), Convert. Recent activity follows the selected wallet. Not-provisioned wallets still show "Setting up", never 0.
- **Receiving details** live in one "Account details" sheet, opened from the Home quick action **Details** (Add money · Send · Convert · Details) and from a Profile row; the Receive route is removed. History is the Activity tab. This satisfies GitHub criterion D1 (#22) (`docs/product/MOBILE_GAP_ANALYSIS.md` §3).
- **Profile:** plain icons in rows (no tinted tiles), Sign out as a text button at the end of the list.
- **Activity:** grouped by day with sticky headers; the header's end slot is reserved for an end-of-day balance from the API (never computed in the app).
- **Finish:** large light balance with smaller minor units; gold only as the card hairline and the active-tab indicator; skeletons instead of spinners; short motion that respects reduce-motion. No gradients, glass effects, illustrations or emoji.
- **Scope:** visual layer only — theme tokens, shared components, layout and spacing, typography, icons, copy tone. No change to business logic, services, navigation flows, security behaviour (lock, FLAG_SECURE, sandbox badge), money formatting or test expectations.
- **Evidence:** screenshots taken on 2026-11-05 on the Pixel_6 emulator (Android, 1080×2400) with the real backend and an approved, funded test user; onboarding and KYC screens with the dev-only mock build (`EXPO_PUBLIC_MOCK_FEATURES=all`). Files are in `mobile/artifacts/design-before/` (git-ignored, local only).
- **Not captured:** Unlock screen (the emulator has no screen lock, so app unlock cannot be turned on), Send result screens (would need a real transfer), KYC "pending" (the dev scenario did not switch). These are audited from code only.

## 1. Audit

### 1.1 What makes it look generic

| # | Finding | Evidence |
| --- | --- | --- |
| A1 | **Tokens exist but are not used.** `theme.type`, `theme.space` and `theme.radius` are referenced 3 times in the whole app. Screens hard-code values: **17 different border radii** (3–52), **16 font sizes** (11–40), **5 font weights** (400–800), off-grid spacing (2, 6, 10, 14, 18, 22). | `src/theme/index.ts:31-33`; every screen `StyleSheet` |
| A2 | **Two blues and a hard-coded third.** `brand #153D85` for buttons, `blue #2864CF` for the input focus ring, the Security switch and the GBP tile, and `#F3F6FC` for the selected wallet. Several pale tints (`paleBlue`, `paleSuccess`, `paleWarning`) are used as decoration, not as meaning. | `theme/index.ts:7-9`, `ui.tsx:420`, `Security.tsx:347`, `Home.tsx:80,526` |
| A3 | **Rainbow currencies.** Each wallet gets its own tint and colour (blue, green, light blue, gold), with no meaning; next to the green credit amounts and the gold sandbox badge the screen reads as busy. | `Home.tsx:74-84`; `04-home-wallets.png` |
| A4 | **Card stacking on Home.** Navy hero card (radius 20), five outlined circles, then six separately bordered wallet cards (radius 14, 8 px apart), then hairline activity rows: three container styles on one screen. | `Home.tsx:464,499-509,514-525`; `03-home.png`, `04-home-wallets.png` |
| A5 | **"Add money" is the highlighted action but it is a placeholder** ("Not available yet"). The one filled circle points to the one action that does nothing. | `Home.tsx:55-58,343-344,509`; `03-home.png` |
| A6 | **Decorative icon tiles everywhere.** 48 px pale-blue tiles on every Profile, Security and KYC row; 72–104 px tinted circles above Receive, Unlock and KYC status titles; a diagonal-lines illustration and an arrow on Welcome. | `ui.tsx:546-553`, `Receive.tsx:456-464`, `Unlock.tsx:166-174`, `Kyc.tsx:174-181`, `Welcome.tsx:42-56`; `13-profile.png`, `09-receive.png`, `23-kyc-intro.png`, `01-welcome.png` |
| A7 | **Weak, inconsistent type hierarchy.** Screen titles at 28, 30, 36, 39 and 40 px; amounts at 40/500 (Home), 40/700 (Send), 36/600 (detail); section titles at 17, 18 and 20 px. | `Home.tsx:431,477`, `ui.tsx:477-484`, `Welcome.tsx:69`, `Send.tsx:615-622`, `TransactionDetail.tsx:76`, `RecentActivity.tsx:113`, `Notifications.tsx:291` |
| A8 | **Every primary button carries an arrow,** including "Sign out →", "Share →" and "Confirm and send →". The arrow means "next step", not "act". | `ui.tsx:147-153`; `27-kyc-rejected.png`, `09-receive.png`, `12-send-review.png` |
| A9 | **Heavy secondary buttons.** Secondary = full-width pale-blue slab, used for Sign out, Go back, Share receipt, Retry, Load more. | `ui.tsx:530`; `13-profile.png`, `14-security.png`, `07b-txdetail-bottom.png` |
| A10 | **Repeated information.** Profile: "Active" badge and "Account status: Active" row. Transaction detail: type as caption and as row; "Completed" as badge and as row. KYC approved: title, body and badge say the same thing. Security: header back arrow and a "Go back" footer button. | `Profile.tsx:204-211`, `TransactionDetail.tsx:34-50`, `Kyc.tsx:153`, `Security.tsx:332`; `13-profile.png`, `07-txdetail.png`, `24-kyc-after.png`, `14-security.png` |
| A11 | **Headers disagree.** Home: wordmark at the start. Activity and Profile: wordmark centred. Pushed screens: "Personal account" caption centred, a label that adds nothing. | `Home.tsx:208-228`, `ui.tsx:224-231`; `03-home.png`, `06-activity.png`, `07-txdetail.png` |
| A12 | **Three press feedbacks:** opacity 0.65, pale-blue fill, opacity 0.82 + scale. Dividers are 1 px in Activity and hairline in Notifications. | `money.tsx:139`, `ui.tsx:524`, `ui.tsx:126`, `Notifications.tsx:297` vs `money.tsx:700` |
| A13 | **Marketing and filler copy.** "A new perspective on money", "One account. More possibilities.", "Built for life across borders.", "Personal. Secure. Yours.", "Good to see you,", "Select a currency to view its balance.", "Looking good?", "Let's try that again.", "Good things are on their way." Titles end with a full stop and are broken over two lines by hand ("Verify your\nidentity."). | `locales/en.ts:7-31,67,88,104,118,181`; `01-welcome.png`, `22-onb-review.png` |
| A14 | **Touch target below 44 px:** currency chips are 40 px high. | `money.tsx:142`; `06-activity.png`, `11-send-amount.png` |

### 1.2 What works and must stay

- **Palette restraint**: navy ink on ivory with a gold accent matches the brand assets (`mobile/assets/brand/TANYT02.png`, `TANYT03.png`: navy, ivory, gold). The wordmark stays live text "NEOS" with the gold mark, as decided in `mobile/README.md`.
- **No shadows anywhere**: keep it that way.
- **One icon family** (Feather).
- **Amounts**: tabular figures, "+" green for credits, "−" ink for debits, strike-through for reversed; formatting comes from `src/money` and does not change.
- **`DetailRow`** label/value structure (detail, review, receive).
- **Accessibility groundwork**: 48 px icon buttons, labels on every control, live regions, reduce-motion check, RTL-safe start/end styles.
- **Honest states**: the "Sandbox · test funds" badge, "Not available yet" placeholders, stale-balance notice.
- **Tab bar**: three tabs, Feather icons, already calm.

## 2. Direction

One sentence: **ivory page, navy text, one blue for actions, flat grouped lists, numbers do the talking.**

### 2.1 Colour tokens

Light mode only (the app sets `userInterfaceStyle: 'light'`, no dark mode exists).

| Token | Value | Use | Contrast on `bg` |
| --- | --- | --- | --- |
| `bg` | `#FAF9F6` | page | — |
| `surface` | `#FFFFFF` | grouped lists, inputs, sheets | — |
| `fill` | `#F0F1F3` | neutral circles (actions, row icons), pressed rows, secondary buttons | — |
| `line` | `#E3E6EA` | hairline dividers, input and group borders | — |
| `ink` | `#13233B` | primary text, icons | 15.0:1 |
| `muted` | `#5F6B7C` | secondary text, inactive tabs | 5.1:1 |
| `accent` | `#153D85` | **the only accent**: primary buttons, links, selection, focus, switch | 9.8:1 |
| `accentPressed` | `#102F68` | primary pressed | — |
| `onAccent` | `#FFFFFF` | text on accent | 10.3:1 |
| `success` / `successBg` | `#287152` / `#E7F1EB` | credits, success states | 5.6:1 |
| `warning` / `warningBg` | `#7F5A1A` / `#F5EDDC` | pending, sandbox badge | 5.3:1 on `warningBg` (was 4.6:1) |
| `error` / `errorBg` | `#B23C3C` / `#F8EAEA` | errors, reversed | 5.5:1 |
| `gold` | `#B39A58` | **wordmark mark only**, never text (2.6:1) | — |
| `disabled` | `#E3E6EA` bg, `muted` text | disabled controls | exempt |

Removed: `blue`, `paleBlue`, `onDark*`, the hard-coded `#F3F6FC` and the per-currency tints. `warning` is darkened slightly (from `#8B631D`) so the 12 px sandbox badge clears 4.5:1 with margin. The capture/silhouette tokens stay for the dev-only KYC preview.

### 2.2 Type

System font (Roboto on Android, SF Pro on iOS). Three weights only: 400, 500, 600. All sizes scale with the system font size; amounts keep `adjustsFontSizeToFit` on one line.

| Style | Size / line | Weight | Use |
| --- | --- | --- | --- |
| `display` | 36 / 44, tracking −0.5, tabular | 600 | balance, transfer and detail amounts |
| `title` | 28 / 34, tracking −0.3 | 600 | one per screen |
| `headline` | 17 / 24 | 600 | section headers, sheet titles |
| `body` | 16 / 24 | 400 | text |
| `bodyStrong` | 16 / 24 | 500 | row titles; row amounts (tabular) |
| `caption` | 14 / 20 | 400 | secondary lines, hints, labels |
| `micro` | 12 / 16 | 500 | badges, tab and action labels |

Mono stays for references and IDs (13 / 18).

**Optional custom font (needs your approval, not assumed):** Inter (SIL OFL 1.1), static TTF Regular, Medium and SemiBold ≈ 0.9–1.0 MB of assets, loaded with `expo-font` (already a dependency, so no new package). It gives true tabular figures on both platforms and a more distinctive look. Recommendation: **stay on the system font for this refresh**; the scale above works without it and Roboto already supports tabular figures.

### 2.3 Space, radius, borders

- **Spacing (4/8 grid):** `xs 4`, `sm 8`, `md 12`, `lg 16`, `xl 24`, `xxl 32`, `xxxl 48`. Page gutter 24; content max width stays 520.
- **Vertical rhythm:** 32 between sections, 8 between a section header and its content, 16 between related blocks.
- **Radius:** `sm 8` (inputs, badges, chips), `md 12` (buttons, grouped lists, banners), `lg 16` (sheet top corners), `pill` (avatars, icon circles, sandbox badge). Nothing else.
- **Borders vs shadows:** no shadows. A grouped list is one `surface` block with a 1 px `line` border; rows inside are separated by hairlines inset to the text start. Elevation exists only for sheets (scrim `rgba(19,35,59,0.4)` from `ink`).

### 2.4 Components

- **Button, primary:** `accent` fill, `onAccent` label `bodyStrong`, height 52, radius 12, no icon. Pressed `accentPressed`. Loading keeps width. One primary per screen.
- **Button, secondary:** `surface` fill, 1 px `line` border, `ink` label. For Retry, Load more, Cancel, Share receipt.
- **Text button:** `accent` label 15/600, min 44 × 44. For "See all", "Mark all as read", "Edit details", "Sign in".
- **No trailing arrows on buttons.** Chevrons appear only on list rows that open a screen.
- **List row:** min height 64; optional leading 40 px `fill` circle with a 20 px `ink` icon (or currency symbol); title `bodyStrong`, secondary `caption muted`; trailing value right-aligned, tabular; chevron `muted` 20 if it navigates. Pressed = `fill` background. One divider style (hairline `line`).
- **Info row (Profile, Security):** label `caption muted` over value `body`, same as `DetailRow`; no icon tiles.
- **Section header:** `headline` at the start, optional text button at the end, 32 above, 8 below. No helper sentence under it.
- **Input:** height 52, radius 8, `surface`, 1 px `line`; focus 2 px `accent`; error 1 px `error` with the message below in `caption error`. Label `caption` 500 above, 8 gap.
- **Banner:** one component for notices (pending transfer, provisioning, stale balance, errors): tone background (`warningBg`/`errorBg`/`fill` for info), radius 12, padding 16, 20 px tone icon, title `bodyStrong ink`, body `caption`. `ErrorNotice` becomes this banner in the error tone.
- **Badge:** pill, `micro`, tone background + tone text, optional 14 px icon. At most one per screen and never repeating a row value.
- **Sandbox badge:** unchanged text and accessibility label, `warningBg` + `warning`, pill, no border, always on Home above the balance (see 2.7).
- **Chips (currency):** height 44, radius 8, `surface` + `line`; selected `ink` fill with white text (so it is not confused with the primary action).
- **Status screens (KYC, unlock, results):** 48 px tone icon without a tinted circle, `title`, one `body` paragraph, actions in the footer. No badge that repeats the title.
- **Empty:** 24 px `muted` icon, `headline`, `caption muted`, centred, 48 from the top of the list area.
- **Loading:** `ActivityIndicator` in `accent`, centred in the area that is loading; existing values stay visible while refreshing.
- **Error:** error banner + secondary Retry, in place of the content that failed.
- **Press feedback:** one rule: rows and icon buttons get `fill` background; buttons darken. Remove opacity/scale variants.

### 2.5 Icons

Feather only, stroke as shipped (2 px at 24). Sizes: 24 in the header and tab bar, 20 in rows and banners, 48 on status screens. Colour `ink` or `muted`; tone colours only where the icon carries a status. No icon inside a tinted tile; no purely decorative icons (Welcome arrow, Receive symbol, Profile row icons go).

### 2.6 Copy tone

Short, neutral, sentence case. No exclamation marks (none today, keep it so), no trailing full stop on titles and buttons, no hand-placed line breaks, no slogans. Say what the screen does.

| Before | After |
| --- | --- |
| A new perspective on money / Money,\nwithout borders. | Money across currencies |
| One account. More possibilities. / Built for life across borders. / Personal. Secure. Yours. | removed |
| Let's begin | Create account |
| Already have an account? Sign in | Sign in |
| Good to see you, | removed (name stays in the avatar) |
| Select a currency to view its balance. | removed |
| Looking good? | Check your details |
| Verify your\nidentity. | Verify your identity |
| Let's try\nthat again. | Verification unsuccessful |
| You're verified. | Identity verified |
| Money sent. | Sent |
| Good things are on their way. | Not available yet |
| Personal account (header caption) | removed |

Final wording is reviewed screen by screen in Part B; message keys do not change, so no logic or test is affected.

### 2.7 Home, the reference screen

**Before** (`03-home.png`, `04-home-wallets.png`, `05-home-recent.png`): wordmark, bell and a gold avatar; "Good to see you, / Smoke" at 28 px with a gold-bordered sandbox pill beside it; a navy card (radius 20) holding "Available", the currency code, a 40 px amount, an eye button, a divider and the currency name; five outlined 52 px circles with the unavailable "Add money" filled in blue; "Your wallets" with a helper sentence; six separately bordered cards with four different tints, the selected one outlined in blue on a hard-coded pale blue; "Recent activity" as hairline rows with tinted arrow circles.

**After**:

1. **Header** (64): wordmark at the start; bell and a 40 px `fill` avatar with the initial in `ink` at the end. Same header on all three tabs.
2. **Sandbox badge** directly under the header, at the start, unchanged text.
3. **Balance**, straight on the page (no card): `caption muted` "EUR balance", then the amount in `display`, with the 44 px eye toggle at the end of the same line. Pending and "last updated" lines in `caption` below. 32 px below the header block.
4. **Actions:** five equal 48 px `fill` circles with 22 px `ink` icons and `micro` labels. None highlighted.
5. **Wallets:** section header "Wallets"; one grouped list with six rows (40 px `fill` circle with the symbol in `ink`, code `bodyStrong`, name `caption muted`, balance `bodyStrong` tabular at the end). The selected wallet shows its circle in `accent` with a white symbol and `accessibilityState.selected`; no other colour.
6. **Recent activity:** section header with "See all"; same grouped list style; arrow icons in `fill` circles, `ink` for both directions; credit amounts `success` with "+".

Net effect: one dark element fewer, four tints fewer, one accent, two container styles instead of three, and the eye goes to the balance first.

## 3. Implementation order (Part B, after approval)

| Step | Work | Check gate | Estimate |
| --- | --- | --- | --- |
| 1 | `theme/index.ts`: new colour, type, space, radius tokens; keep old names as aliases until step 6 so nothing breaks mid-way | tsc, lint | 0.5 h |
| 2 | `components/ui.tsx`: Button (no arrow, secondary outline), TextButton, Field, Banner (+ ErrorNotice), Badge, Screen header (no "Personal account"), Sheet, Intro, InfoRow → label/value, press feedback | tsc, lint, tests | 2 h |
| 3 | `components/money.tsx` + new `ListGroup`/`ListRow` in `ui.tsx`: chips 44 px, TransactionRow, DetailRow; tab bar tokens | tsc, lint, tests; screenshots Activity | 1 h |
| 4 | **Home** per 2.7 (incl. `RecentActivity`) | tsc, lint, tests; before/after Home | 2 h |
| 5 | **Money screens**: Send (all steps and results), Activity, Transaction detail, Receive, Notifications | tsc, lint, tests; before/after | 2 h |
| 6 | **Account and entry screens**: Profile, Security, Unlock, Welcome, Onboarding, KYC, status and unavailable screens; copy pass in `en.ts`; remove old token aliases | tsc, lint, tests; before/after | 2.5 h |
| 7 | Final: all `CLAUDE.md` checks (tsc, lint, test, release-bundle, release-readiness), full screenshot set, engineering log (WP25) | all green | 1 h |

Total ≈ 11 h of work, run as one branch with no commit until you ask.

## 4. Decisions needed

1. Approve this direction (or list changes).
2. Font: system font (recommended) or Inter (≈ 1 MB of OFL assets).
3. Home: balance without the navy card (proposed) or keep a card.
4. Copy changes in 2.6: approve as a set, or keep Welcome copy as is.
