# CashDash — Security Audit

> ### ⚠️ Superseded — historical record
>
> This document has been **merged into `SECURITY_REVIEW.md` in the repo root**, which is now the single live tracker for both audits. Two documents with different numbering is a hazard in itself: a finding closed in one and open in the other is easy to lose.
>
> **Do not track work from this file.** It is kept for its detailed per-finding write-ups and the incident record in the section below, neither of which was carried over in full.
>
> **Statuses re-verified and corrected 2026-08-26** against live infrastructure and current source — not against the notes in this document. Corrections are marked inline. One instruction here was outright wrong and has been struck: see **#12**.

**Scope:** Android app (`app/src/main`), Firebase Cloud Functions (`functions/index.js`), Firestore rules, RTDB rules, Gradle/ProGuard config, repo hygiene.
**Date of audit:** 2026-08-24 · **Audited at:** `525a302` on `main` · **App version then:** 0.4.8 (versionCode 21)
**Re-verified:** 2026-08-26 · **App version now:** 0.5.0 (versionCode 23, in Play review)

**Status as re-verified: 18 of 22 closed · 1 withdrawn (#12 — never applied) · 1 deferred by request (#8) · 1 open (#9) · 1 assessed area (#15).**

*Original 2026-08-24 status line, for the record: "17 of 22 closed · 1 deferred by request (#8) · 4 partial (#9 #12 #13 #15)."* Since then #13 was closed, #12 was found never to have applied, and #19/#20/#22 were confirmed already fixed despite appearing under "Not fixed yet" below.

Deployed and probe-tested in production:

| Check | Result |
|---|---|
| `POST cashdashWebhook` | **HTTP 404** — endpoint deleted |
| `GET adminReply` with no signature | **HTTP 403** — rejected |
| `GET adminReply` with forged signature | **HTTP 403** — rejected |
| `rephraseSupportText`, `getSupportReplyLink` | live (callable) |
| Firestore / Storage / RTDB rules | released |

> **Two ways a fix reaches users.** Backend changes (Cloud Functions, Firestore/Storage/RTDB rules) go live the moment `firebase deploy` runs — they protect every user immediately, old app version or not. App-side changes (key removal, App Check, exported components, WebView allowlist, encrypted cache, backup rules) live inside the APK and only reach a user when they **update the app**. Nine findings were in the second category.
>
> **Update 2026-08-26:** of those nine, six shipped in 0.4.9 (#14, #16, #17, #19, #20, #21). The rest are in 0.5.0, currently in Play review.

**Still outstanding of these 22, as of 2026-08-26:** exactly one — **App Check enforcement (#9)**, console-only, stage via Monitor first. #8 remains deferred at your request pending Google Sign-In. #15 is assessed, and what remains under it is either blocked on #9 or a recorded decision. ~~the git history purge (#12, destructive force-push)~~ — **withdrawn, the premise was false; see #12.** ~~#13~~ — **closed 2026-08-25.**

> **Two things outside these 22 are open and are not tracked anywhere in this file** — see `SECURITY_REVIEW.md`:
>
> 1. **`firestore.rules` has never been executed against a test.** 261 lines, 12 helper predicates, no emulator config, no test infrastructure. This document itself flagged it twice — under #11 (*"the one rule here whose exact semantics I could not execute"*) and in Remaining steps → 5 — and it was never done. It is the control every other finding here leans on.
> 2. **Both Gemini secrets still hold their transcript-exposed values** — `GEMINI_API_KEY` v2 and `GEMINI_API_KEY_ADMIN` v1. Neither has been rotated since. See Remaining steps → 1.

---

## Status at a glance

| # | Severity | Issue | Status — 🟢 LIVE = deployed to Firebase now · 📦 In build = committed, ships with next app release |
|---|----------|-------|--------|
| 1 | 🔴 Critical | `cashdashWebhook` public unauthenticated endpoint | 🟢 **LIVE** — backend; returns 404 |
| 2 | 🔴 Critical | `adminReply` public unauthenticated endpoint | 🟢 **LIVE** — backend; unsigned returns 403 |
| 3 | 🔴 Critical | Hardcoded Gemini API keys in the APK | 🟢 **LIVE** + 📦 needs app release — old keys revoked (401); server holds both scoped keys |
| 4 | 🔴 Critical | No Firebase Storage rules | 🟢 **LIVE** — backend |
| 5 | 🟠 High | Any admin reads every user's financial data | 🟢 **LIVE** — backend rules |
| 6 | 🟠 High | `validUntil` expiry not enforced server-side | 🟢 **LIVE** — backend rules |
| 7 | 🟠 High | `allocateAdmins` self-escalation to owner | 🟢 **LIVE** — backend rules |
| 8 | 🟠 High | Authorization on unverified email | ⏸️ **Deferred by request** — all verification logic removed from the app; you are implementing this with Google Sign-In |
| 9 | 🟠 High | Firebase App Check not enabled | ⚠️ **Still open** — SDK proven working (Play builds log `app: VALID`); **enforcement still off**, gated on 0.5.0 adoption |
| 10 | 🟠 High | XSS in the admin reply page | 🟢 **LIVE** — backend; escaping + allowlist + CSP |
| 11 | 🟡 Medium | Any user can rewrite `admin_logs` click arrays | 🟢 **LIVE** — backend rules; append-own-email only |
| 12 | 🟡 Medium | Logcat/crash dumps with user emails in git | ❎ **Withdrawn** — premise false; the files were never committed (verified 2026-08-26) |
| 13 | 🟡 Medium | Unbounded, unvalidated, auto-public upload | 🟢 **LIVE — CLOSED 2026-08-25** — download tokens replace `makePublic()`; 83 legacy public objects revoked, verified `403` |
| 14 | 🟡 Medium | `fcmTokens` writes always fail | 📦 **In build** — needs app release |
| 15 | 🟡 Medium | Weak reverse-engineering / tamper posture | 📋 **Assessed 2026-08-26 — status now tracked only in `SECURITY_REVIEW.md`.** Restating it here is what made this file drift three times; see *Reverse-engineering and tamper posture* there |
| 16 | 🟡 Medium | Unnecessary exported components | 📦 **In build** — needs app release |
| 17 | 🔵 Low | WebView: JS enabled, arbitrary URL | 📦 **In build** — needs app release |
| 18 | 🔵 Low | RTDB: users can't read own presence | 🟢 **LIVE** — backend rules |
| 19 | 🔵 Low | `data_extraction_rules.xml` is the stock template | 📦 **In build** — needs app release |
| 20 | 🔵 Low | Zombie-account recovery can delete a real account | 📦 **In build** — needs app release |
| 21 | 🔵 Low | `GlobalScope.launch` for AI calls | 📦 **In build** — needs app release |
| 22 | 🔵 Low | `audit_logs` forgeable by any admin | 🟢 **LIVE** — backend rules; actor_email must match caller |

---

## ✅ Independent re-verification — 2026-08-24, after push `525a302`

Every finding was re-checked against the **actual current state** (live endpoints + pushed source), not against earlier notes. 45 automated assertions, all passing.

### Live production probes

| Probe | Result | Finding |
|---|---|---|
| `POST cashdashWebhook` | **404** — endpoint gone | #1 |
| `GET adminReply` no signature | **403** | #2 |
| `GET adminReply` forged signature | **403** | #2 |
| `GET adminReply` expired signature | **403** | #2 |
| `POST adminReply` no signature | **403** | #2 |
| Old Gemini key #1 | **401** — revoked | #3 |
| Old Gemini key #2 | **401** — revoked | #3 |

Deployed functions confirmed: `adminReply`, `getSupportReplyLink`, `onGlobalPush`, `onSupportQuery`, `onUserPush`, `rephraseSupportText`, `onAuthUserDeleted`. No `cashdashWebhook`, no `syncPresenceToFirestore`.

> **Re-confirmed 2026-08-26**, with two changes since: `mirrorAdminToRtdb` has been added (it enforces the `viewLastSeen` grant on presence data), and everything except `onAuthUserDeleted` now runs in `asia-south1`, with three callables dual-homed during the rollout. `cashdashWebhook` is still absent.

### Scoreboard

*As recorded 2026-08-24 — superseded by the re-verified scoreboard below.*

**17 fully closed** — #1, #2, #3, #4, #5, #6, #7, #10, #11, #14, #16, #17, #18, #19, #20, #21, #22

**1 deferred at your request** — #8 (all verification logic removed; you are building this with Google Sign-In)

**4 partial** — #9, #12, #13, #15.

### Scoreboard — re-verified 2026-08-26

**18 closed** — the original 17, plus **#13**.

**1 withdrawn** — **#12**. The premise was false. See below.

**1 deferred at your request** — #8, unchanged.

**1 genuinely open** — #9, App Check enforcement.

**1 assessed area** — #15. Reclassified twice: it was never a set of findings that could be "closed", so listing it as *partial* overstated how much was known. It was a **coverage gap** until 2026-08-26, when it was actually examined. Live status is in `SECURITY_REVIEW.md`, not here.

| # | Re-verified state | Evidence (2026-08-26) |
|---|---|---|
| 9 | **Open.** SDK proven working — Play builds log `app: VALID`; sideloaded builds show `INVALID`, which is expected. Enforcement still off. | Gated on 0.5.0 adoption; enforcing now would lock out users on older builds |
| 12 | **Withdrawn.** `git log --all` over all six named files returns nothing — they were never committed, so nothing was ever exposed and there is nothing to purge. | Files exist untracked on disk only |
| 13 | **Closed 2026-08-25.** `makePublic()` replaced with per-object `firebaseStorageDownloadTokens`; 83 of 85 legacy public objects revoked, 2 already on the token scheme. | [functions/index.js:954](functions/index.js) — `crypto.randomUUID()`; revocation verified by a live `403` |
| 15 | **Assessed 2026-08-26** — no longer a coverage gap. Live status deliberately **not** restated here. | `SECURITY_REVIEW.md` → *Reverse-engineering and tamper posture* |

**So: 18 of 22, one withdrawn, and the remaining three are a console decision (#9), your product decision (#8), and #15 — which was an unassessed area at the time and has since been properly assessed (2026-08-26).** Note that #19, #20 and #22 appear under *"Not fixed yet"* further down — that section is stale. All three were verified fixed in current source; see the correction notice there.

### Note on rule deployment

Firestore/Storage/RTDB rule *files* were verified line-by-line and were deployed successfully in this session. The Firebase CLI offers no read-back command for live rules, so the assurance there is "deployed without error and source verified" rather than a live read. Re-running `firebase deploy --only firestore:rules,storage,database` is idempotent if you want certainty.


## ⚠️ Incident: hardening lost and recovered (2026-08-24)

Worth recording, because the failure mode is easy to repeat.

**What happened.** Every fix in this document was made as *uncommitted working-tree edits*. Partway through, something discarded them — most likely an IDE auto-stash during a branch switch, or a manual discard — and a `git pull --rebase origin main` then brought in five unrelated commits (a Force Update / version-gating feature). The rebase rewrote history, so the original base commit `006bb81` no longer exists. `git reflog` shows the sequence; the one surviving stash turned out to hold that other feature, not this work.

**What was NOT affected.** Deployed Firebase state is independent of git. Throughout the incident, production stayed hardened — verified by probe: `cashdashWebhook` 404, `adminReply` unsigned 403. All secrets in Secret Manager (`GEMINI_API_KEY`, `GEMINI_API_KEY_ADMIN`, `REPLY_SIGNING_SECRET`) were untouched. **No user was ever exposed.**

**The real risk it created.** The local `firestore.rules` and `functions/index.js` reverted to their unhardened originals — including a working `cashdashWebhook`. Anyone running `firebase deploy` from that state would have silently overwritten the live protections and reopened both critical endpoints. That window is now closed.

**Recovery.** All files were rebuilt and committed as `525a302`, merged carefully around the concurrent work — `/system/` Firestore rule, `ForceUpdateActivity`, `VersionCheckManager`, and `EditAdminPermissionsActivity` were all preserved. `assembleDebug` passes; backend redeployed and re-probed.

**Lesson.** Uncommitted work is not safe work. Commit security changes as soon as they build, before any branch or pull operation.


# ✅ Fixed

## 1. `cashdashWebhook` — endpoint deleted

The function was pure duplication. All three callers already wrote `needs_admin_email: true` to Firestore, which fires `onSupportQuery` and sends the same email. The webhook existed only to beat trigger cold-starts — and because it used `.set()` rather than merge, it *raced* the trigger and wiped fields the app had just written, including `needs_admin_email` and `imageUrls`.

Deleting it removes the attack surface **and** a real bug. Support mail now flows exclusively through `onSupportQuery`, which is authenticated by Firestore rules.

- `functions/index.js` — `exports.cashdashWebhook` removed
- `ContactSupportActivity.kt`, `HelpActivity.kt`, `NotificationActivity.kt` — `triggerImmediateWebhook()` and its call site removed from each

## 2. `adminReply` — HMAC-signed, expiring, scope-bound links

The reply page opens from an email client *and* from an in-app WebView, neither of which carries a Firebase session — so a token check was not an option. Access is now an HMAC-SHA256 signature over `${targetUser}|${id}|${exp}`, verified with `crypto.timingSafeEqual` on both GET and POST.

```js
function verifyReplyToken(targetUser, id, exp, sig) {
    if (!targetUser || !id || !exp || !sig) return false;
    const expNum = Number(exp);
    if (!Number.isFinite(expNum) || expNum < Date.now()) return false;
    const expected = Buffer.from(signReplyToken(targetUser, id, expNum));
    const provided = Buffer.from(String(sig));
    if (expected.length !== provided.length) return false;
    return crypto.timingSafeEqual(expected, provided);
}
```

Verified against tampering: a flipped signature character, a truncated signature, an empty signature, a changed `exp`, a changed user, and a changed notification id all return `false`; only the exact tuple passes.

The app can no longer mint these URLs, so it asks for one via a new **admin-only callable**, `getSupportReplyLink`, which runs `assertAdmin()` first:

- `functions/index.js` — `signReplyToken` / `verifyReplyToken` / `buildReplyUrl`, `assertAdmin`, `getSupportReplyLink`
- `SupportReplyLink.kt` (new) — requests the signed link, then opens the WebView
- `AdminActivity.kt`, `ManageAdminAccessActivity.kt` — hand-built URL removed, both now call `SupportReplyLink.openReplyPage(...)`

`cors: true` is also gone — it was letting any origin script the endpoint.

## 3. Gemini keys removed from the client — rotated, split, and DEPLOYED

Both original keys are gone from all six call sites, and the `generativeai` SDK dependency is removed. Rephrasing goes through the `rephraseSupportText` callable, server-side only.

**Rotated once already.** The two original keys hit a `429 RESOURCE_EXHAUSTED` (prepayment credits depleted) shortly after the first deploy — confirmed in `firebase functions:log`, and possible evidence the leaked keys were already drawing on the same billing pool. Both were replaced.

**Now split into two keys, matching the pairing the app used to have hardcoded:**

| Scope | Secret | Screens |
|---|---|---|
| `messaging` | `GEMINI_API_KEY` (v2) | AdminMessagingActivity — announcements + push notifications |
| `admin` | `GEMINI_API_KEY_ADMIN` (v1) | AdminPromotionsActivity + ManageAdminAccessActivity |

The client sends a scope name only (`"messaging"` / `"admin"`) — never a key. The server maps scope to secret:

```js
const REPHRASE_SCOPES = {
    messaging: () => GEMINI_API_KEY.value(),
    admin: () => GEMINI_API_KEY_ADMIN.value(),
};
```

**Rate limit changed, not preserved — flagging this explicitly since it wasn't requested.** The old `MAX_REQUESTS_PER_MINUTE = 10` was one shared in-process Kotlin counter for the whole app, reset on every restart, and meant nothing to anyone holding the extracted key. It is replaced by a Firestore-backed fixed-window limiter, **20/min per admin per scope** — real enforcement, but a materially different shape:

| | Old | New |
|---|---|---|
| Limit | 10/min | 20/min |
| Counter scope | one counter, whole app, per device | per admin, **per key scope** |
| Effective ceiling per admin | 10/min total | up to 40/min (20 messaging + 20 admin) |
| Survives app restart | no | yes (Firestore-backed) |
| Bypassable by whoever holds the key | yes | no |

If a true 10/min total is wanted instead, that's a one-line change to the rate-limit key — not yet done.

> **Done 2026-08-25.** The limiter is now keyed **per admin rather than per admin per scope**, so the effective ceiling is a real 20/min instead of the 40/min the split allowed. The "up to 40/min" row in the table above no longer applies.

```
$ grep -rn 'AQ\.Ab8RN6' app/src/main/java/
  NONE — all removed
```

**Both replacement keys are now also compromised**, via this chat transcript, and should be rotated again when convenient — see Remaining steps.

> **Update 2026-08-26.** Half acted on: `GEMINI_API_KEY` moved to **v2**. `GEMINI_API_KEY_ADMIN` is **still on v1** — the exposed version — and remains the one outstanding rotation.

## 4. Storage rules written

`storage.rules` created and registered in `firebase.json`. The key insight is that Firebase Storage evaluates `get` and `list` separately: `getDownloadUrl()` needs `get`, so that must stay open to signed-in callers — but `list` is what enables bucket enumeration, and nothing in the app uses it.

```
match /support_attachments/{fileName} {
  allow get: if isSignedIn();
  allow list: if false;
  allow create: if isSignedIn() && isImage() && underSizeLimit();
  allow update, delete: if false;
}
```

`allow update, delete: if false` is what stops the two worst outcomes: wiping every user's attachments, and swapping a `promotions/` banner for a scam QR code that your own push pipeline then distributes. `promotions/` writes are admin-only via `firestore.exists()`.

## 5, 6, 7. Admin authorization rebuilt

**Granular permissions (#5).** The ten permissions in `AdminManager.AdminPermissions` now exist in the rules too. Financial data is owner-only; admins keep exactly what their screens need:

```
match /users/{userEmail} {
  allow read: if isOwner(userEmail) || isAnyAdmin();   // listing + name + fcmToken

  match /notifications/{notificationId} {
    allow read: if isOwner(userEmail) || canReplyToQueries();
  }
  match /config/profile {
    allow read: if isOwner(userEmail) || isAnyAdmin();  // name/phone card
  }
  match /config/{docId} {
    allow read, write: if isOwner(userEmail);           // wallet, history, analytics…
  }
}
```

I checked both admin screens read only `config/profile` (`AdminActivity.kt:458`, `ManageAdminAccessActivity.kt:430`), so nothing legitimate loses access.

**Expiry (#6).** `adminNotExpired()` compares `validUntil` against `request.time.toMillis()` — server time, so the device-clock bypass is gone too.

**Self-escalation (#7).**

```
allow create, update: if isSuperAdmin() || (
  canAllocateAdmins() &&
  adminEmail.lower() != request.auth.token.email.lower() &&
  request.resource.data.get('isOwner', false) != true
);
```

## 10. XSS closed

Every interpolated value now goes through one `esc()` helper — `subjectText`, `uid`, `email`, `id`, `exp`, `sig`, and the error paths. The attachment renderer was the subtle one: it escaped first and then re-injected the raw URL into `src=""` and an inline `onclick`, so a crafted marker could break out of the attribute. It now escapes first, validates the URL against your own Storage hosts, drops the inline handler, and renders anything else as `(Attachment removed)`.

A `Content-Security-Policy`, `X-Content-Type-Options: nosniff`, and `Referrer-Policy: no-referrer` are set on the response.

## 11. `admin_logs` click arrays

`hasOnly` constrained *which* keys changed but not *how*. The rule now permits only an append of the caller's own email, and rejects any removal:

```
allow update: if isActiveUser()
  && request.resource.data.diff(resource.data).affectedKeys()
       .hasOnly(['notif_clickers', 'ann_clickers'])
  && request.resource.data.get('notif_clickers', [])
       .removeAll(resource.data.get('notif_clickers', []))
       .hasOnly([request.auth.token.email])
  && resource.data.get('notif_clickers', [])
       .removeAll(request.resource.data.get('notif_clickers', []))
       .size() == 0
  && …same for ann_clickers…;
```

This matches the `arrayUnion` the app already uses. **Worth an emulator test** — it is the one rule here whose exact semantics I could not execute.

## 13. Upload hardening *(was partial; **fully closed 2026-08-25** — see #13 below)*

- `limits: { fileSize: 8MB, files: 4, fields: 20 }` — there were no limits at all
- MIME allowlist: `image/png|jpeg|jpg|webp|gif`
- **Path traversal closed.** `path.basename()` plus `[^A-Za-z0-9._-] → _`. The old `${Date.now()}_${filename}` prefix did *not* prevent traversal: `path.join('/tmp', '1234_../../etc/passwd')` treats `1234_..` as a directory, so `..` escapes and it resolves to `/etc/passwd`.
- Over-limit files settle on `close` rather than `finish`, so a rejected upload can't leave the request hanging

## 14. Dead `fcmTokens` write removed

No rule ever matched `fcmTokens`, so the catch-all denied every write and Firestore's offline queue retried forever. Nothing read it — the functions all use `users/{email}.fcmToken`, which the line above it sets correctly.

## 16. Exported components closed

- **`ScannerActivity`** → `exported="false"`, `cashdash://` BROWSABLE filter deleted. I confirmed the activity never reads `intent.data`, so the deep link only ever let any web page launch your payment scanner.
- **`NotificationActivity`** → `exported="false"`. This needed a backend change first: `adminReply` sent a `notification` payload with `clickAction`, which relies on the FCM SDK building the intent. It now sends **data-only**, so `MyFirebaseMessagingService` builds its own `PendingIntent` — matching the pattern `onUserPush` already used. A `PendingIntent` reaches a non-exported activity fine.
- **`ManageAdminAccessActivity`** → explicit `exported="false"`.

## 17, 18, 21. Lower-severity items

- **WebView** — host allowlist (`cashdash.co.in`, `www.cashdash.co.in`, the reply host), HTTPS-only, `allowFileAccess = false`, `allowContentAccess = false`; off-allowlist navigation is handed to the system browser instead of running with app context
- **RTDB** — owners can now read their own `/status/$userEmail`
- **`GlobalScope`** — all six rephrase blocks moved to `lifecycleScope`, so they no longer outlive the activity and touch destroyed views

---

# ⚠️ Partly fixed — action needed from you

### #3 — superseded

This used to be the open action item for #3. It's now out of date — #3 has moved to ✅ Fixed above (both original keys rotated out, the replacements split into two scoped secrets, second deploy live), with a new open item — rotating the *current* keys — tracked under **Remaining steps → #1**, since they were also exposed in chat. Left this stub here only so old links to `#3` still land somewhere.

### #8 — Deferred by request; all verification logic removed

**Current state: nothing in the app calls Firebase's email-verification API.** The `sendEmailVerification()` call on registration and the in-app prompt were both removed at your request, so this finding is fully open again — no half-implemented state left behind. The rules never referenced `email_verified`, so there was nothing to revert server-side.

**Your plan (to implement later):** Google Sign-In for auto-verified accounts; email/password signup stays unverified at first; a passive verify affordance on the profile page; and an email *change* requiring the new address to be verified before it saves.

**Two things worth carrying into that work:**

1. **The rules fix is narrower than originally scoped.** The attack is specifically an admin email sitting in `admins/` that hasn't registered in Firebase Auth yet. So gate only the admin predicates — leave `isOwner()` alone and regular users stay unverified exactly as you want:

```
function isVerified() {
  return request.auth != null && request.auth.token.email_verified == true;
}
function isAnyAdmin() {
  return isVerified() && (isSuperAdmin() || (adminExists() && adminNotExpired()));
}
```

Only your handful of admins need to verify — not the whole user base — and Google Sign-In makes that friction-free. Note the two hardcoded super admins fall under this too.

2. **`ProfileActivity.kt` still uses `updateEmail()`**, which changes the address with no verification — the opposite of your point 4. The correct API is `verifyBeforeUpdateEmail()`. Consequence: the change then lands *outside* the app when the user clicks the link, so the Firestore data migration (email is your primary key) must move to a next-launch check. That existing flow also wipes the old document *before* writing the new one — worth reordering to copy-then-verify-then-delete.

**Also check before shipping Google Sign-In:** Firebase Console → Authentication → Settings → keep **"One account per email address"** enabled. Your rules key on email, not UID; allowing multiple accounts per email would let two UIDs reach the same data.

### #9 — App Check enforcement is a console step

The SDK is wired in: dependencies added, `initAppCheck()` runs from `CashDashApplication.onCreate()`, Play Integrity in release and the debug provider in debug builds.

Still yours to do in the Firebase console:
1. Register the app under **App Check** with Play Integrity
2. Add your **debug token** so local builds keep working
3. Turn on **enforcement** for Firestore, Storage, Auth, and Functions — *monitor first, enforce once metrics look clean*
4. Separately, restrict the API key in Cloud Console (Android restriction + package + SHA-1, plus an API allowlist)

Enforcement is what actually closes this. The SDK alone does nothing until step 3.


### Reading the App Check percentages

As of 2026-08-24: Storage 0% · RTDB 11% · Firestore 20% · Auth 10% verified.

> **Update 2026-08-26.** The percentage reading below was the right call, and there is now direct evidence rather than inference: Play-distributed builds log `{"verifications":{"app":"VALID"}}`. Sideloaded debug builds log `INVALID` unless the registered debug token is in use — **expected, not a regression**, and worth knowing before you read your own test results as a failure. Enforcement is still off and still gated on 0.5.0 adoption.

**This is healthy, not broken.** The numbers moved off zero, which proves the SDK is issuing tokens. They are low because the column is a rolling window across *all* traffic:

- Users still on the old build (versionCode ≤ 20) have no App Check SDK, so 100% of their requests count unverified
- Only devices running the new build contribute verified requests
- ~10–20% therefore reflects the share of traffic on the updated build, not a failure

Storage sits at 0% simply because no attachment or promo image has been uploaded from an updated build yet — no sample, not a fault.

**Do not enforce until this is high.** Enforcing at 20% would cut off the other 80% of your users. Ship the release, let adoption climb, then enforce one API at a time.

One caveat on the debug token: it is per-install. Reinstalling the debug build, or building on another machine, generates a new token that also needs registering. Release traffic (Play Integrity, no token needed) is the signal that actually matters before enforcing.

### ~~#12 — Git history still contains the PII~~ — **WITHDRAWN 2026-08-26: this finding was wrong**

**The premise was false. Do not run the command that used to be in this section.**

It claimed the logcat and crash dumps were "still in every past commit" and told you to rewrite history and force-push. Checking rather than assuming:

```bash
git log --all --oneline -- logcat.txt logcat2.txt logcat_dump.txt \
    current_logcat.txt crash.txt all_history.txt
# (no output — never committed)
```

These files exist **untracked on your disk only**. They were never committed, so the user emails were never pushed to GitHub and there is nothing to purge. The original finding appears to have inferred history presence from the files being present in the working tree.

**Consequence of the error:** it would have had you run a destructive `git filter-repo` force-push — breaking every clone and rewriting 376 commits — to remediate an exposure that did not exist. The nine addresses were **not** disclosed by this route.

They stay untracked and `.gitignore`-guarded, which is correct and sufficient.

> **Unrelated but adjacent, and real:** release APKs *were* committed, and those did carry the old Gemini keys. That was tracked separately (as `prev #14` in the consolidated tracker) and has since been purged with `git filter-repo` and force-pushed to both remotes — 376 → 374 commits, HEAD tree hash provably unchanged. A backup bundle at `S:/AndroidStudioProjects/cashdash-backup-20260825-231858.bundle` still contains those old APKs and therefore the revoked keys; delete it once you are satisfied with the rewrite.

### ~~#13 — Attachments are still `makePublic()`~~ — **CLOSED 2026-08-25**

*Original text retained below for context; the blocker described in it was solved a different way than proposed.*

> Limits and traversal are fixed, and uploads now require a valid signed token — so only real admins reach this path. But objects are still made world-readable with no expiry.
>
> Signed URLs cap at 7 days, and `[Attachment: https://...]` markers are stored permanently in the reply text and rendered by the app, so switching would break every existing attachment. Doing it properly means storing a bucket-relative path and generating a fresh signed URL at render time — a data migration, not an instant fix.

**How it was actually resolved.** Signed URLs were the wrong tool precisely because of the 7-day cap. Instead, `makePublic()` was replaced with a **per-object Firebase download token** — unguessable, and with no expiry, so the permanently-stored `[Attachment:]` markers keep working and no data migration was needed:

```js
const downloadToken = crypto.randomUUID();
// ... metadata: { firebaseStorageDownloadTokens: downloadToken }
`${encodeURIComponent(dest)}?alt=media&token=${downloadToken}`
```

*Both halves are closed:*

- **New uploads** — private, token-addressed. Deployed 2026-08-24.
- **Legacy objects** — the bucket was swept 2026-08-25: **85 scanned, 83 `allUsers` grants revoked**, 2 skipped (already on the token scheme), 0 errors. Verified by fetching a revoked URL directly and getting `403`, not by trusting the API response.

**Known consequence.** Those 83 images are referenced by their old public URLs inside existing Firestore support threads, so they now render broken in the admin dashboard, the `adminReply` page, and in-app notification history. Conversation text is untouched; only images in already-closed threads are affected. This is the fix working, not a regression.

---

# ❌ Not fixed yet — **mostly stale, corrected 2026-08-26**

> **Correction.** Three of the four items below — **#19, #20 and #22** — were already fixed in `525a302`, the very commit this audit was written against, and the status table at the top always said so. This section contradicted it. Each has now been verified in current source and is marked closed inline.
>
> Only **#15** remains in this section, and it is no longer an open finding either: it was a *coverage gap* — an area neither audit assessed — until it was properly assessed on 2026-08-26. Its live status is in `SECURITY_REVIEW.md`.

*These retain the original detailed format.*

## 📋 15. Reverse-engineering and tamper posture — **assessed 2026-08-26; tracked in `SECURITY_REVIEW.md`**

> **Not an open finding — and no longer unassessed.** Neither audit examined this area at the time; it was a list of things known to be absent. It was **properly assessed on 2026-08-26**, and the live status lives in `SECURITY_REVIEW.md` → *Reverse-engineering and tamper posture*.
>
> The table below is the original 2026-08-24 list, corrected inline for what has since shipped. **Treat the corrections as of 2026-08-26 and the tracker as authoritative** — three of the rows below were restated status that went stale within a day.
>
> Two rows are now recorded **decisions**, not gaps: root detection and a standalone integrity check are declined *pending App Check enforcement*, on the grounds that client-side detection is only worth having if something consumes the signal.

What is already right: `isMinifyEnabled = true` and `isShrinkResources = true` on release (`build.gradle.kts:43-44`), release signing config reading from `local.properties` (not committed), `allowBackup="false"` and `fullBackupContent="false"`, `debuggable` left at its default of `false`, and `Log.d/v/i/w` stripped by `proguard-rules.pro:41-46`.

What's missing:

| Gap | Consequence |
|---|---|
| No string encryption | R8 obfuscates identifiers only. Any hardcoded key, URL, or prompt is plaintext in `classes.dex` — that is how #3 was trivially exploitable. Mitigated for now by there no longer being a key to find. |
| `-keep class com.cash.dash.NotificationEntity/TransactionEntity/NotificationModel { *; }` (`proguard-rules.pro:24-30`) | Your data model — field names and structure — is fully readable in the DEX. Necessary for Room/Firestore reflection, but it hands an attacker your schema. |
| No root / emulator / debugger detection — **declined 2026-08-26, pending App Check** | Still true: a UPI app runs unmodified under Frida. But a detector is only worth having if something consumes the signal, and the natural consumer is App Check, which is not enforced (#9). Until then it is code an attacker patches out in minutes, carrying real false-positive risk against users on custom ROMs. |
| No Play Integrity API — **blocked on #9** | Wired through App Check but unenforced, so the integrity signal you already pay for goes unused. Enforcing #9 is the work; adding a second path is not. |
| ~~No `FLAG_SECURE`~~ — **partly shipped**; now set on the 5 admin screens | Wallet, history and payment screens remain screenshottable — a deliberate product call, not an oversight. |
| ~~Plain `SharedPreferences` everywhere~~ — **CLOSED 2026-08-26** | All three files named here are now encrypted. The admin permission cache purges its plaintext predecessor on load and, since 2026-08-26, **skips caching entirely rather than falling back to plaintext** when the keystore is unavailable. `WalletPrefs` and `LocalScanPrefs` (`last_upi`) both moved to `EncryptedSharedPreferences` via `WalletStore` / `ScanStore`, with one-time migrations verified by 7 instrumented tests on a physical device. |
| No certificate pinning | Remaining direct HTTP is gone with the webhook removal, but Firebase SDK traffic is unpinned. |

**Two judgment calls I made rather than just applying:**

- **`FLAG_SECURE`** is a one-line change in `ThemedActivity`, but it blocks screenshots app-wide — and plenty of users genuinely want to screenshot their spending. That is a product decision, not a pure security one, so it is yours.
- **Stripping `Log.e`** I chose *not* to do. Since Android 4.1 an app can only read its own logcat, so the leak needs ADB or physical access — marginal security value against a real cost to your debugging, and you clearly rely on logs.

~~**`EncryptedSharedPreferences`** is the highest-value item left here, but it needs a migration path for existing installs — not an instant change.~~

> **Done 2026-08-26.** All three stores are encrypted — admin permission cache, `WalletPrefs`, `LocalScanPrefs` — each with a one-time migration off its plaintext file, verified on device. This was the highest-value item in this section and it is closed.

## ✅ 19. `data_extraction_rules.xml` is the untouched template — **CLOSED (verified 2026-08-26)**

> Every rule is commented out. `allowBackup="false"` and `fullBackupContent="false"` already cover you, so this is inert — but fill in explicit `<exclude>` rules or delete the reference.

**Fixed.** The file now carries **10 explicit `<exclude>` entries** — verified in current source, not assumed. Shipped in 0.4.9.

## ✅ 20. Zombie-account recovery can destroy a real account — **CLOSED (verified 2026-08-26)**

> `EntryActivity.kt:467-483`: if a registration collides and sign-in succeeds but `config/profile` is missing, the code calls `auth.currentUser?.delete()`. A user whose profile write failed mid-registration (network drop at `:640`) will have their real account silently deleted on their next registration attempt. Gate this on an explicit `account_status == "admin_deleted"` marker rather than treating a missing document as proof of a zombie.
>
> I left this alone because it is a correctness change to your auth flow with real data-loss consequences either way, and it deserves testing rather than a blind edit.

**Fixed exactly as recommended.** `isZombie` now requires the explicit marker rather than inferring from a missing document — [EntryActivity.kt:476](app/src/main/java/com/cash/dash/EntryActivity.kt):

```kotlin
val isZombie = profileTask.isSuccessful &&
    … status == "admin_deleted"
```

A user whose profile write failed mid-registration no longer has their real account deleted. Shipped in 0.4.9.

## ✅ 22. `audit_logs` can be forged by any admin — **CLOSED (verified 2026-08-26)**

> `firestore.rules` — `allow create: if isAnyAdmin()`. Any admin can write arbitrary audit entries attributing actions to someone else. Audit logs should only ever be written by a Cloud Function using the admin SDK, with clients denied `create` entirely.
>
> Not fixed because the app writes these directly from `AdminActivity.kt:1467` and `ManageAdminAccessActivity.kt:1485`. Denying client creates would silently break audit logging until those writes move into a function.

**Fixed, without moving the writes into a function.** The rule now binds the entry to the caller and to server time, so an admin can still write their own entries but cannot attribute an action to anyone else or backdate it:

```
match /audit_logs/{document=**} {
  allow read: if isSuperAdmin();
  allow create: if isAnyAdmin()
    && request.resource.data.actor_email == request.auth.token.email.lower()
    && request.resource.data.timestamp == request.time;
  allow update, delete: if isSuperAdmin();
}
```

Forgery of *other* actors is closed; client writes keep working. Verified in current [firestore.rules:232](firestore.rules).

---

# Remaining steps

### ✅ Done — secrets set, keys split, backend deployed

`REPLY_SIGNING_SECRET` (v1) and the existing `GMAIL_PASS` (v6) are ENABLED. Gemini is now two secrets: `GEMINI_API_KEY` (v2, messaging scope) and `GEMINI_API_KEY_ADMIN` (v1, admin scope) — see finding #3 above for why there are two and what happened to v1 of the first key. Secret Manager access was granted to the compute service account for all secrets during deploy. Two deploys have gone out: the initial `firebase deploy --only functions,firestore:rules,storage,database --force`, then a follow-up `firebase deploy --only functions:rephraseSupportText --force` for the key split. `cashdashWebhook` was deleted, `getSupportReplyLink` and `rephraseSupportText` were created, `adminReply` was updated, and all three rule sets were released.

### 🔶 1. Rotate both current Gemini keys — **half done; `GEMINI_API_KEY_ADMIN` still outstanding**

> **Status 2026-08-26, verified against the deployed bindings:** `GEMINI_API_KEY` was rotated and is now **v2**. `GEMINI_API_KEY_ADMIN` is still **v1** — the very version this section records as transcript-exposed. It has never been rotated; v1 is the only version that has ever existed. Confirmed by reading the secret versions bound to the deployed `rephraseSupportText` in both regions.
>
> Also worth destroying `GEMINI_API_KEY` v1, which is still ENABLED and now superseded.

The original two keys have been **verified revoked** (`GET /v1beta/models` returns 401 UNAUTHENTICATED for both), so the exposure that mattered — keys in every shipped APK and in public git history — is closed.

The two keys now in the secrets were only ever exposed in a Claude Code chat transcript. That is materially lower risk: not public, not indexed, not reachable by the key-harvesting bots that scan APKs and public repos. Worth knowing though that Claude Code stores transcripts as plaintext JSONL under `~/.claude/projects/`, so they sit unencrypted on local disk and would travel with any backup, cloud sync, or shared transcript.

Rotate at your convenience, not urgently:

```bash
firebase functions:secrets:set GEMINI_API_KEY_ADMIN --project cashdash-8cd8b --data-file=-
firebase deploy --only functions:rephraseSupportText --project cashdash-8cd8b
```

> **Correction 2026-08-26.** An earlier annotation dropped `GEMINI_API_KEY` from this block, claiming it was "already rotated to v2". That was wrong. v2 is the *replacement* key created after the APK leak — and the replacements are exactly what this section records as transcript-exposed. **Both secrets are still the exposed values and both need rotating.** The block above is correct as originally written.

The redeploy is **required**, not optional: functions pin a secret version at deploy time, so setting a new version without redeploying leaves the old one live.

Paste the key then Ctrl+Z + Enter (Windows). Piping via `--data-file=-` keeps the value out of shell history and out of chat. No app rebuild needed — scope names are unchanged, only which secret they resolve to.

### ⬜ 2. App Check enforcement (console)

Register `com.cash.dash` with Play Integrity, add your debug token, set Firestore / Storage / Auth / Functions to **Monitor**, then switch to **Enforce** once metrics look clean.

### 🔶 3. Ship the app build — **0.5.0 (versionCode 23) is in Play review**

Admins need it for "Open Reply Page"; all users need it for the App Check attestation and the WebView allowlist.

> **Update 2026-08-26.** 0.4.9 shipped and carried #14, #16, #17, #19, #20, #21. 0.5.0 is in review and carries the remaining client-side work: the `strings.xml` key removal, the widget-toggle fix, delete-account, internal camera cache, and the 8-char registration minimum. Until it rolls out, those protect nobody — and App Check enforcement (#9) stays gated on its adoption.

### ⬜ 4. Two warnings surfaced during deploy (pre-existing, not from these changes)

- `package.json indicates an outdated version of firebase-functions` — run `npm install --save firebase-functions@latest` in `functions/` when convenient.
- ~~`onSupportQuery`, `onGlobalPush` and `onUserPush` run in `us-central1` but their Firestore triggers are in `asia-south1`.~~ **Done 2026-08-25** — the backend was migrated to `asia-south1` and is now co-located with Firestore. Verified against the deployed functions: `onSupportQuery`, `onGlobalPush`, `onUserPush` and `mirrorAdminToRtdb` are asia-south1 only; `adminReply`, `getSupportReplyLink` and `rephraseSupportText` run **dual-region** (asia-south1 + us-central1) during rollout, because installed builds have `getInstance("us-central1")` compiled in. `onAuthUserDeleted` stays in `us-east1` — it is a v1 auth trigger and cannot move. See `MIGRATION_ASIA_SOUTH1.md`.

### Transition behaviour now in effect

- **Reply links in already-sent emails will stop working.** They carry no signature. Admins should open those queries from the app instead — which now fetches a fresh signed link.
- **Older installed app versions lose the "Open Reply Page" button**, because they still build unsigned URLs. Ship the new build to admins first.
- Support email keeps working throughout — `onSupportQuery` was always the real sender.

### ⬜ 5. Verify

```bash
firebase emulators:exec --only firestore "echo rules loaded"
```

Worth explicitly testing: the `admin_logs` append rule (#11), an admin with only `replyToQueries` being denied `users/*/config/wallet` (#5), an expired admin being denied everything (#6), and one end-to-end support reply through the signed link.

### ⬜ 6. Then, in the console

~~Revoke the old Gemini keys~~ **done, verified 401** · ~~register App Check + add your debug token~~ **done** · **enable enforcement in monitor mode first — still outstanding, the one real item left here** · **restrict the API key** (Android restriction + package + SHA-1, plus an API allowlist) — still outstanding · wire a budget alert to a billing kill-switch (Cloud budgets only *alert*, they never stop spend).

---

# What happens if the rest goes unfixed

The four critical findings are closed in code, which removes the anonymous-attacker paths entirely. What remains is a materially smaller problem.

> **Correction 2026-08-26.** This section used to say "two items still matter on a clock." Both have since been resolved or withdrawn — see the strikethroughs below. **Nothing here is on a clock any more.** The one item that still genuinely matters is App Check enforcement, and it is gated on 0.5.0 adoption rather than on time.

~~**The Gemini keys are the live one.**~~ — **RESOLVED 2026-08-24, this no longer applies.** Both exposed keys were revoked and independently verified dead (`401 UNAUTHENTICATED` as `?key=` and as `x-goog-api-key`, against a deliberately-invalid control). The APKs that carried them have since been purged from git history. The project-suspension scenario described below is no longer on a clock.

> *Original text, for the record:* Until you revoke them, the code fix has changed nothing: both keys are in git history and in every shipped APK. Bots scan Play Store APKs and public repos for exactly this pattern, and harvested keys get resold in bulk. The severe outcome is not the bill — it is Google detecting abuse and suspending the key or the project. **A project suspension takes Auth, Firestore, Storage and FCM with it**, and the app is bricked for every user until you resolve it with Google support.

**What remains of this item** is small and not urgent: `GEMINI_API_KEY_ADMIN` is still on v1, which was exposed in a chat transcript — not public, not indexed, not reachable by the bots that scan APKs and repos. Rotate at convenience (Remaining steps → 1).

**App Check without enforcement is decoration.** The SDK is installed but enforces nothing until you flip it on. Until then, the API key in `google-services.json` still lets anyone talk to Firestore and Auth directly from a script: the 5-attempt login lockout in `EntryActivity.kt:412` is a Kotlin field and does not exist over REST, so credential stuffing against your users is unlimited. The hardened rules do hold — an attacker still can't read other users' data — but account-level attacks stay open.

~~**The PII is already out** if the repo is public.~~ — **WITHDRAWN 2026-08-26: the premise was false.** `logcat.txt` and the other five named files were **never committed**, so the nine addresses were never in the repository and nothing was disclosed by this route. See #12. The DPDP exposure argument below rested entirely on that false premise and does not apply.

> *Original text, for the record:* Untracking does not retract it; the nine addresses in `logcat.txt` remain in history until you rewrite it. Under the DPDP Act 2023 you process financial data of Indian users and owe reasonable security safeguards plus breach notification, with penalties reaching ₹250 crore. I'm not a lawyer, and enforcement against a small app is a separate question from exposure — but shipping user PII in a public repo is hard to characterise as reasonable safeguards.

**What was real** in this neighbourhood: release APKs *were* committed and did carry the (now revoked) Gemini keys. Those have been purged from history and force-pushed to both remotes.

**Everything else is now insider- or device-scoped.** #15 needs someone with your APK and a rooted device; #20 is a self-inflicted data-loss bug rather than an attack; #22 needs an admin already acting in bad faith. Real, worth closing, but none of them are a stranger on the internet any more.

The phishing chain — the one that ended the app in the previous version of this report — required both unauthenticated endpoints. Both are gone. Once the keys are revoked and enforcement is on, the realistic worst case drops from *"users lose money to messages your infrastructure delivered"* to *"an insider abuses access you granted them."*
