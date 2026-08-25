# CashDash — Security Audit (consolidated)

| | |
|---|---|
| **Date** | 2026-08-25 |
| **HEAD** | `1986ea5` on `main` |
| **App version** | 0.5.0 (versionCode 23) — in Play review |
| **Scope** | Android client, Cloud Functions, Firestore / RTDB / Storage rules, dependencies, build config, repo hygiene |
| **Supersedes** | `CASHDASH_SECURITY_AUDIT.md` (2026-08-24, 22 findings, at `525a302`) and the earlier 15-finding review. Both are merged here. |

**Status: 24 closed · 1 accepted risk · 3 open · 1 unfixable upstream.**

Two audits existed with different numbering, which is a hazard in itself — a finding closed in one and open in the other is easy to lose. This is now the single tracker. Where the two overlapped, the older number is shown in brackets.

---

## Open — needs action

### A. Authorization keys off an unverified email address `[old #8 / prev #2]` — **High**

The only High remaining, and the only structural one.

Every authorization decision compares an email string that Firebase never verified anyone controls: `isOwner()` and `isSuperAdmin()` in [firestore.rules](firestore.rules), the presence rules in [database.rules.json](database.rules.json), `assertAdmin()` in [functions/index.js](functions/index.js), and the two hardcoded super-admin addresses. Firebase enforces email uniqueness, so a registered address can't be taken — the exposure is an admin address sitting in `admins/` that has **not** yet registered in Auth.

Deferred by request; you are implementing this alongside Google Sign-In.

Three things to carry into that work:

1. **Gate only the admin predicates.** Regular users stay unverified, so only your handful of admins need to verify:
   ```
   function isVerified() { return request.auth != null && request.auth.token.email_verified == true; }
   function isAnyAdmin() { return isVerified() && (isSuperAdmin() || (adminExists() && adminNotExpired())); }
   ```
   The two hardcoded super admins fall under this too.
2. **[ProfileActivity.kt:137](app/src/main/java/com/cash/dash/ProfileActivity.kt) still uses `updateEmail()`**, which changes the address with no verification. The correct API is `verifyBeforeUpdateEmail()`. Consequence: the change then lands outside the app when the user clicks the link, so the Firestore migration (email is the primary key) has to move to a next-launch check. That flow also wipes the old document before writing the new one — reorder to copy, verify, then delete.
3. **Keep "One account per email address" enabled** in Console → Authentication → Settings. The rules key on email, not UID; allowing multiple accounts per email would let two UIDs reach the same data.

### B. App Check not enforced `[old #9 / prev #9]` — Medium

SDK is wired and **proven working**: Play-distributed builds log `{"verifications":{"app":"VALID"}}`. Sideloaded debug builds show `INVALID` unless using the registered debug token — expected, not a regression.

Enforcement is still off, so it protects nothing yet. Gated on 0.5.0 adoption: enforcing while most traffic is on older builds would lock those users out. Enable **Monitor** first, watch the verified percentage climb, then **Enforce** one product at a time.

Also outstanding from the earlier audit: restrict the API key in Cloud Console (Android restriction + package + SHA-1, plus an API allowlist).

### C. `GEMINI_API_KEY_ADMIN` has never been rotated — Low

Deployed binding is `GEMINI_API_KEY_ADMIN = v1`, and v1 is the only version that has ever existed. The 2026-08-24 audit recorded that the then-current replacement keys were exposed in a Claude Code chat transcript, and it names exactly this version. `GEMINI_API_KEY` has since moved to v2; this one has not.

Verified **not** the key that leaked via the APK (that one returns 401, dead). So this is lower risk — a transcript isn't indexed or scanned by bots — but it is unrotated, and you already created a replacement in AI Studio that never reached Secret Manager.

```bash
firebase functions:secrets:set GEMINI_API_KEY_ADMIN --project cashdash-8cd8b --data-file=-
firebase deploy --only functions:rephraseSupportText --project cashdash-8cd8b
```
The redeploy is required — functions pin a secret version at deploy time.

Also worth destroying `GEMINI_API_KEY` v1, still ENABLED and superseded.

---

## Accepted risk

### D. Push notifications can carry arbitrary outbound links `[prev #11]` — Low

`triggerUrl` is prefix-checked for `http(s)` only, then fired as `ACTION_VIEW` into the system browser — bypassing `WebViewActivity`'s host allowlist. An admin can push a CashDash-branded notification whose button leads anywhere, with the destination invisible to the user.

**Accepted 2026-08-25:** the only admins are the two hardcoded super admins, who trust each other, so there is no untrusted party in the threat model.

**Revisit if** a third admin is added (the app ships `allocateAdmins` / `admin_requests`, so this is a supported operation), an admin account is compromised, or the audience widens. The fix is small and client-side: allowlist own domains for silent opening, show the host for anything else.

---

## Unfixable upstream

### E. `uuid` < 11.1.1 — Medium, not reachable

`npm audit` reports 8 moderate entries; **all 8 are one advisory** ([GHSA-w5hq-g745-h8pq](https://github.com/advisories/GHSA-w5hq-g745-h8pq)), reported once per package in the chain: `uuid` ← `gaxios` / `google-gax` / `teeny-request` ← `@google-cloud/*` ← `firebase-admin`.

**Not reachable here.** The flaw is in `v3`/`v5`/`v6` when a `buf` argument is passed. This codebase never imports `uuid`; the Google libraries call `uuid.v4()` with no buffer.

**No upgrade fixes it.** Verified by attempting it: `firebase-admin` 14.3.0 (latest) still ships the same `uuid`, after which npm proposes "fixing" it by *downgrading* to `firebase-admin` 10 / `firebase-functions` 4. Reverted — v14 also removes the legacy `admin.*` namespace, breaking all 8 call sites, for zero security gain. It clears when Google bumps the dependency upstream; re-run `npm audit` occasionally.

---

## Closed

### Backend — live now, protects every user regardless of app version

| # | Finding | Evidence |
|---|---|---|
| `old #1` | `cashdashWebhook` public unauthenticated endpoint | Deleted — probes 404 |
| `old #2` | `adminReply` public unauthenticated endpoint | HMAC-signed, expiring, `timingSafeEqual` — unsigned/forged/expired all 403 |
| `old #4` | No Storage rules | `storage.rules` written; `list` denied, uploads type/size limited, immutable |
| `old #5` | Any admin reads every user's financial data | `config/{docId}` owner-only |
| `old #6` | `validUntil` expiry not enforced server-side | `adminNotExpired()` against `request.time` |
| `old #7` | `allocateAdmins` self-escalation to owner | Blocked — plus `prev #5` below |
| `old #10` | XSS in the admin reply page | `esc()` everywhere, attachment host allowlist, CSP |
| `old #11` | Any user can rewrite `admin_logs` click arrays | Append-own-email only, removals rejected |
| `old #18` | RTDB: users can't read own presence | Fixed |
| `old #22` | `audit_logs` forgeable by any admin | `actor_email` must equal caller, server timestamp |
| `prev #3` | `getSupportReplyLink` ignored `replyToQueries` | `assertAdmin` takes a granular permission |
| `prev #4` | Any admin could force-update-lock every user | `system/**` writes require `canAllocateAdmins()` |
| `prev #5` | `admins` self-escalation via `fullAccess` | Blocked alongside `isOwner` — the older audit missed this path |
| `prev #6` | `user_pushes` readable by every signed-in user | Restricted to broadcasters and the addressed user |
| `prev #7` / `old #13` | Attachments permanently world-public | Download tokens replace `makePublic()`; **83 legacy public objects revoked, verified 403** |
| `prev #15` | `viewAdminLogs` / `viewLastSeen` grants unenforced | `admin_logs` gated; `mirrorAdminToRtdb` + RTDB rules now enforce `viewLastSeen` |

### Client — shipped in 0.4.9, or pending the 0.5.0 rollout

| # | Finding | Ships in |
|---|---|---|
| `old #3` / `prev #1` | Hardcoded Gemini keys in the APK | Keys revoked (verified 401); resource removed — 0.5.0 |
| `old #14` | Dead `fcmTokens` write retried forever | 0.4.9 |
| `old #16` / `prev #10` | Unnecessary exported components | 0.4.9 + widget toggle in 0.5.0 |
| `old #17` | WebView: JS enabled, arbitrary URL | 0.4.9 |
| `old #19` | `data_extraction_rules.xml` stock template | 0.4.9 — 10 explicit excludes |
| `old #20` | Zombie-account recovery could delete a real account | 0.4.9 — `isZombie` requires `account_status == "admin_deleted"` |
| `old #21` | `GlobalScope.launch` for AI calls | 0.4.9 |
| `prev #8` | "Delete Account" silently failed to delete cloud data | 0.5.0 |
| `prev #12` | Camera attachments in external cache | 0.5.0 |
| `prev #13` | Login lockout client-side only | 0.5.0 — 8-char registration minimum |

### Repo and dependencies

| # | Finding | Resolution |
|---|---|---|
| `prev #14` | Keystore inside the repo | Moved to `S:/AndroidStudioProjects/keystores/`, path via `KEYSTORE_FILE` in gitignored `local.properties`. Verified: md5 identical, `signingReport` resolves, same SHA1 |
| `prev #14` | Release APKs in git history | Purged with `git filter-repo`, force-pushed to both remotes. Content provably unchanged (HEAD tree hash identical); 376 → 374 commits |
| `old #12` | Logcat/crash dumps with user emails in history | **Does not apply** — verified those files were never committed. The only email-shaped matches in tracked text files are Kotlin fragments like `this@AllocatorActivity` |
| — | `websocket-driver` ≤ 0.7.4 | **Critical** — patched to 0.7.5 |
| — | `form-data` < 2.5.6 | **High** — patched to 2.5.6 |
| — | `nodemailer` ≤ 9.0.0 | **High** — upgraded to 9.0.5, delivery verified by live test send |
| — | Rephrase rate limit was double the intended ceiling | Keyed per admin, not per scope — a real 20/min |

---

## Not covered by this audit

Carried from the older document's #15, and **not** re-examined here. Reverse-engineering and tamper posture is a coverage gap in both reviews rather than a set of closed findings:

- No root / emulator / debugger detection — a finance app with UPI flows runs unmodified under Frida on a rooted device
- `WalletPrefs` and `LocalScanPrefs` (which stores `last_upi`) are plaintext `SharedPreferences`
- No string encryption — R8 obfuscates identifiers, not string constants
- `-keep` rules expose the Room/Firestore data model in the DEX
- No certificate pinning on Firebase SDK traffic
- `FLAG_SECURE` covers the 5 admin screens only; wallet/history/payment screens are screenshottable — a deliberate product call

What is already right: `isMinifyEnabled`, `isShrinkResources`, `allowBackup="false"`, `debuggable` false, `Log.d/v/i/w` stripped in release, encrypted admin permission cache.

---

## Infrastructure state

Backend fully co-located with Firestore in `asia-south1` (2026-08-25 migration):

| Function | Region |
|---|---|
| `onSupportQuery`, `onGlobalPush`, `onUserPush`, `mirrorAdminToRtdb` | asia-south1 |
| `rephraseSupportText`, `getSupportReplyLink`, `adminReply` | asia-south1 + us-central1 *(dual during rollout)* |
| `onAuthUserDeleted` | us-east1 *(v1 auth trigger, left deliberately)* |

Dual-region is transitional: installed builds have `getInstance("us-central1")` compiled in. After 0.5.0 adoption — switch `buildReplyUrl` to the asia-south1 URL, wait 7 days for outstanding reply links to expire, then drop `us-central1`. See [MIGRATION_ASIA_SOUTH1.md](MIGRATION_ASIA_SOUTH1.md).

RTDB remains in the US and cannot be relocated in place; presence writes still cross regions. Storage bucket location unverified — no CLI access.

---

## Next actions

1. **Ship 0.5.0** (in review) — carries the last client-side fixes.
2. **Rotate `GEMINI_API_KEY_ADMIN`** (C) — two minutes.
3. **App Check → Monitor, then Enforce** (B) once adoption climbs.
4. **Finish the region migration** after adoption.
5. **Email verification** (A) — the remaining High, alongside Google Sign-In.

Delete the backup bundle `S:/AndroidStudioProjects/cashdash-backup-20260825-231858.bundle` once satisfied with the history rewrite; it still contains the old APKs and therefore the revoked keys.

**This file documents unfixed weaknesses with exact rule paths.** Fine while both repos are private. Reconsider before making either public or adding outside collaborators.
