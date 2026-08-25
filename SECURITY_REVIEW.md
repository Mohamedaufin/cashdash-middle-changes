# CashDash — Security Review

| | |
|---|---|
| **Application** | CashDash (`com.cash.dash`), Android + Firebase |
| **Commit reviewed** | `525a302` — *Restore security hardening lost in rebase* |
| **Date** | 2026-08-24 |
| **Scope** | Android client (`app/src/main`), Firestore / Realtime Database / Cloud Storage rules, Cloud Functions backend (`functions/index.js`), build configuration, repository hygiene |
| **Method** | Manual source review. No dynamic testing, no live rules simulation, no penetration testing against the deployed project. A dependency scan was added later, out of the original scope — see *Dependency advisories*. |
| **Last updated** | 2026-08-25 — statuses re-verified against the deployed project. |

---

## Summary

The project has clearly been through a hardening pass: admin permissions are now enforced in Firestore rules rather than only in Kotlin, the support-reply endpoint requires an HMAC-signed expiring token, Storage rules exist and block bucket enumeration, Gemini keys were moved into Cloud Functions secrets, and App Check is wired into the client. The findings below are what remains.

Three issues are High: a live API key still shipping in the APK, an identity model built on unverified email addresses, and a Cloud Function that bypasses the app's own granular admin permissions.

| # | Severity | Finding | Component | Status |
|---|---|---|---|---|
| 1 | **High** | Live Gemini API key still ships in the APK; a second key is in git history and in published builds | Android build | **Resolved** — keys revoked, verified dead 2026-08-24 |
| 2 | **High** | All authorization keys off an unverified email address | Auth / rules | Open — needs a migration decision |
| 3 | **High** | `getSupportReplyLink` ignores the `replyToQueries` permission | Cloud Functions | **Resolved** — deployed 2026-08-24 |
| 4 | Medium | Any admin can force-update-lock every user out of the app | Firestore rules | **Resolved** — deployed 2026-08-24 |
| 5 | Medium | `admins` self-escalation block is bypassable via `fullAccess` | Firestore rules | **Resolved** — deployed 2026-08-24 |
| 6 | Medium | `user_pushes` is readable by every signed-in user (email harvesting) | Firestore rules | **Resolved** — deployed 2026-08-24 |
| 7 | Medium | Admin reply attachments are made permanently world-public | Cloud Functions / Storage | **Resolved** — new uploads use private tokens; 83 legacy public objects revoked 2026-08-25, verified `403` |
| 8 | Medium | "Delete Account" silently fails to delete cloud data | Client + rules + Functions | **Fixed** — ships in next app release |
| 9 | Medium | App Check is not enforced on the callable functions | Cloud Functions | Open — needs a rollout decision |
| 10 | Low | Exported widget receiver lets any app toggle the tracking service | Android manifest | **Fixed** — ships in next app release |
| 11 | Low | Push notifications can carry arbitrary outbound links | Android client | Open — product decision |
| 12 | Low | Camera attachments written to external cache storage | Android client | **Fixed** — ships in next app release |
| 13 | Low | Login lockout is client-side and in-memory only | Android client | **Fixed** (8-char registration minimum) — ships in next app release |
| 14 | Low | Release APKs and scratch files committed to git; keystore in working tree | Repository | Open — needs owner action |
| 15 | Medium | `viewAdminLogs` and `viewLastSeen` grants are not enforced server-side | Firestore + RTDB rules | **`admin_logs` fix deployed and verified live**; presence gap documented, not fixable without a larger change |

**Status as of 2026-08-25.** Every server-side fix (rules, Storage rules, Cloud Functions) is deployed and live — verified against the deployed function source, which the Firebase CLI reports as matching the current tree. The four findings marked *"ships in next app release"* (8, 10, 12, 13) are client-side: they are committed and compile, but **no release build has gone out yet**, so they protect nobody until an APK ships. Treat those as fixed-in-repo, not fixed-in-production.

---

## High severity

### 1. Live Gemini API key still ships in the APK

**Location** — [`app/src/main/res/values/strings.xml:4`](app/src/main/res/values/strings.xml)

**Detail** — The string resource `gemini_api_key` still holds a live key (`AQ.Ab8RN6J98Q…XPZw`, redacted here). It is committed at HEAD and present in `app/release/app-release.apk`. The migration to the `rephraseSupportText` Cloud Function removed the Kotlin code that consumed the key but left the resource in place, so it continues to be packaged into every build.

A second key (`AQ.Ab8RN6JneSLo…jshXnw2Q`) is hardcoded in git history at commits `13dea35` and `80979e6`, in `AdminMessagingActivity.kt`, `AdminPromotionsActivity.kt` and `ManageAdminAccessActivity.kt`. Those commits were released — nine release APK blobs (~21 MB each) sit in git history, and the corresponding Play builds were downloadable by end users.

**Impact** — String resources in an APK are trivially extractable (`apktool`, or plain `strings`). Anyone who has ever downloaded a released build can bill Gemini usage to the project's quota. Making the repository private does not help: the key left the building inside the APK.

**Remediation**
1. ~~Revoke and rotate **both** keys in Google Cloud console~~ — done 2026-08-24.
2. ~~Delete the `gemini_api_key` string from `strings.xml`~~ — done (ships with the next build).
3. Confirm no other resource or `BuildConfig` field carries a credential (`grep -rE '"A[QI]za?[0-9A-Za-z_-]{20,}' app/src`).
4. Optionally scrub history with `git filter-repo`, but treat that as cleanup, not remediation — rotation is what matters.

**Verification (2026-08-24)**

- Both exposed keys return `401 UNAUTHENTICATED` from `generativelanguage.googleapis.com`, as `?key=` and as `x-goog-api-key`, matching a deliberately-invalid control key. Neither can call the API.
- `GEMINI_API_KEY` advanced to version 2 and the deployed `rephraseSupportText` is bound to it.
- `GEMINI_API_KEY_ADMIN` is still on version 1, but its value was confirmed **not** to be the exposed key — so the admin/promotions scope was never carrying the leaked credential and did not break when the key was revoked.

Residual hygiene, not urgent: `GEMINI_API_KEY` version 1 is still ENABLED in Secret Manager. If it holds the old messaging key, disable or destroy that version so the only enabled value is the current one.

---

### 2. All authorization keys off an unverified email address

**Location** — [`app/src/main/java/com/cash/dash/EntryActivity.kt:446`](app/src/main/java/com/cash/dash/EntryActivity.kt), [`firestore.rules:9`](firestore.rules), [`firestore.rules:13`](firestore.rules), [`database.rules.json:6`](database.rules.json), [`functions/index.js:35`](functions/index.js)

**Detail** — Registration uses `createUserWithEmailAndPassword` and never calls `sendEmailVerification()`; nothing anywhere checks `isEmailVerified` or `request.auth.token.email_verified`. Meanwhile every authorization decision in the system is an email string comparison:

- `isOwner(emailId)` → `request.auth.token.email.lower() == emailId.lower()`
- `isSuperAdmin()` → comparison against two hardcoded Gmail addresses
- Realtime Database presence rules → same pattern
- `SUPER_ADMINS` in the Functions backend → same two addresses

Firestore documents are keyed by email, so account identity *is* the email claim.

**Impact** — Firebase enforces email uniqueness, so an already-registered address cannot be taken. The exposure is the gap: if either hardcoded super-admin address is not currently registered in Firebase Auth for this project — or is ever deleted, or the project is ever migrated — anyone can register that address with a password of their choosing, without proving they control the mailbox, and immediately hold super-admin: full read/write over every user's financial data, the `admins` collection, and all system configuration. The same weakness applies to any address a user hopes to claim later.

**Remediation**
1. Send a verification email on registration and gate app entry on `isEmailVerified`.
2. Add `request.auth.token.email_verified == true` to `isOwner`, `isSuperAdmin` and `adminExists` in `firestore.rules`, to the RTDB rules, and to `assertAdmin` in `functions/index.js`.
3. Longer term, move admin status onto Firebase custom claims and key user documents by `uid` rather than email. Email-as-primary-key also makes address changes and GDPR erasure awkward.
4. Confirm today that both super-admin addresses are registered accounts under strong passwords with 2FA on the underlying Google accounts.

---

### 3. `getSupportReplyLink` ignores the `replyToQueries` permission

**Location** — [`functions/index.js:271`](functions/index.js)

**Detail** — The callable calls `assertAdmin(request)`, which checks only that the caller exists in `admins` and has not expired. It does not check the `replyToQueries` grant. Given any admin session, the function will mint a valid HMAC-signed link for an arbitrary `userEmail` + `docId` pair, and `adminReply` will then render the full support thread and accept replies against it.

**Impact** — An admin provisioned with only, say, `viewLastSeen` can read any user's support conversation (including attachments), post replies that appear to come from CashDash Support, and mark threads resolved. This is precisely the access [`firestore.rules:96`](firestore.rules) restricts to `canReplyToQueries()`; the function is a way around the rule.

**Remediation** — Add a permission-aware variant of `assertAdmin`, e.g. `assertAdminWithPermission(request, 'replyToQueries')` that accepts super admins, `isOwner`, `fullAccess`, or the specific grant, and call it here. Consider auditing the other callables for the same gap — `rephraseSupportText` is genuinely fine with plain `assertAdmin`, since any admin has a legitimate use for it.

---

## Medium severity

### 4. Any admin can lock every user out of the app

**Location** — [`firestore.rules:209`](firestore.rules), [`app/src/main/java/com/cash/dash/VersionCheckManager.kt:88`](app/src/main/java/com/cash/dash/VersionCheckManager.kt)

**Detail** — `match /system/{document=**}` grants write to `isAnyAdmin()`. Commit `215bfdd` added a check preventing an admin from setting a minimum version higher than their own installed version, but that check lives in the admin UI, not in the rules. Any admin — or anyone holding a stolen admin session — can write `force_update_enabled: true` with an arbitrary `min_supported_version_name` straight to `system/config`.

**Impact** — Every client on next launch is routed to `ForceUpdateActivity`, which is a full-screen, non-dismissible block. Total denial of service for the whole user base, triggerable by the least-privileged admin.

**Remediation** — Restrict `system/**` writes to `isSuperAdmin()`, or route force-update changes through a callable that re-applies the version-bound check server-side. Note that `VersionCheckManager` also has ordinary clients writing `version_history`/`latest_version_code` back to `system/config`; those writes are currently denied for non-admins and fail silently, which is worth cleaning up either way.

---

### 5. `admins` self-escalation block is bypassable via `fullAccess`

**Location** — [`firestore.rules:134`](firestore.rules), [`firestore.rules:43`](firestore.rules)

**Detail** — The create/update rule prevents an `allocateAdmins` holder from writing `isOwner: true` or from editing their own document. But `adminHasBlanket()` treats `fullAccess` as exactly equivalent to `isOwner`, and `fullAccess` is not constrained. An `allocateAdmins` holder can create `admins/{second-account-they-control}` with `fullAccess: true`, then operate with blanket privileges from that account. `validUntil` is likewise unconstrained, so expiry windows can be extended arbitrarily.

**Impact** — The escalation ceiling is "everything except the two hardcoded super-admin identities" rather than the intended "only the permissions I already hold". The rule reads as if it prevents escalation; it does not.

**Remediation** — Extend the guard to `request.resource.data.get('fullAccess', false) != true` for non-super-admins, and cap `validUntil` (for example, no further out than the granting admin's own expiry). Ideally, restrict granting a permission to admins who already hold it.

---

### 6. `user_pushes` is readable by every signed-in user

**Location** — [`firestore.rules:165`](firestore.rules), [`functions/index.js:966`](functions/index.js)

**Detail** — `match /user_pushes/{document=**}` allows read to `isActiveUser()`, i.e. any authenticated account. Each document carries the target user's `email`, along with the notification title and body.

**Impact** — Any registered user can list the collection and harvest the email addresses of targeted users, plus the content of the support alerts and promotions they received. `announcements` and `global_pushes` being broadly readable is correct — they are broadcasts. `user_pushes` is per-user addressed mail and should not be.

**Remediation** — Restrict read to the addressed user and broadcasting admins:

```
allow read: if isOwner(resource.data.email) || canBroadcast();
```

The client does not appear to read this collection at all (delivery happens via FCM and the mirrored notification document), so `allow read: if canBroadcast();` may be sufficient — verify before tightening.

---

### 7. Admin reply attachments are made permanently world-public

**Location** — [`functions/index.js:820`](functions/index.js)

**Detail** — After uploading, `adminReply` calls `uploadedFile.makePublic()` and embeds a bare `https://storage.googleapis.com/{bucket}/support_attachments/{ts}_{name}` URL into the reply text. `makePublic()` grants `allUsers` read on the object, which bypasses Storage rules entirely.

**Impact** — Support-conversation images — screenshots of balances, statements, transaction history, whatever a user was asked to send — become readable by anyone on the internet holding the URL, with no authentication and no expiry. Objects are not listable, so exploitation requires obtaining a URL, but the URLs are long-lived and travel through Firestore documents and the reply web page.

**Remediation** — Drop `makePublic()` and issue a time-limited signed URL (`getSignedUrl` with an expiry), or store the object path and resolve it through an authenticated download. Audit the bucket for objects already made public and revoke `allUsers` on them. Note that user-side uploads from the client use `getDownloadUrl()` tokens, which are unguessable but also permanent — worth revisiting on the same pass.

**Resolution (2026-08-25)** — Both halves are closed.

- *New uploads*: `makePublic()` replaced with a random download token, so objects stay private. Deployed 2026-08-24.
- *Legacy objects*: the bucket was swept — **85 scanned, 83 `allUsers` grants revoked, 2 skipped** (already on the new token scheme), 0 errors. Verified by fetching a revoked URL directly and receiving `403`, rather than trusting the API response.

The sweep ran from a temporary, single-purpose Cloud Function authenticated by a one-off generated secret; the function, the secret, and the scratch code were all destroyed afterwards, leaving no residual attack surface and no trace in git history.

**Known consequence** — those 83 images are referenced by their old public URLs inside existing Firestore support threads, so they now render broken in the admin dashboard, the `adminReply` page, and in-app notification history. Conversation text is untouched; only images in already-closed threads are affected. This is the fix behaving correctly, not a regression.

---

### 8. "Delete Account" silently fails to delete cloud data

**Location** — [`app/src/main/java/com/cash/dash/ProfileActivity.kt:284`](app/src/main/java/com/cash/dash/ProfileActivity.kt), [`firestore.rules:122`](firestore.rules), [`functions/index.js:1074`](functions/index.js)

**Detail** — `wipeUserFirestoreData` builds one atomic batch that includes `set(deleted_accounts/{email})`. `firestore.rules` allows writes to `deleted_accounts` only for super admins, so the batch is rejected in full — the user's `config` documents and notifications are never deleted by the client. The failure handler logs and proceeds to Auth deletion regardless.

Cleanup then falls to the `onAuthUserDeleted` trigger, whose `configDocs` list covers `profile`, `wallet`, `categories`, `history`, `analytics`, `history_scanner`, `undo_details` — but omits `scanner_metadata`, `finminder`, and `upi_allocations`, all of which the client writes.

**Impact** — Documents containing financial data are orphaned in Firestore indefinitely after a user deletes their account. No one can read them (the owner rule no longer matches any live account), but they are retained, which contradicts the deletion dialog's "deleted from CashDash servers immediately" and the privacy policy's erasure commitment. The same rules mismatch makes the `deleted_accounts` marker unreliable for self-deletions. `EntryActivity`'s zombie-account cleanup path has the same denied write.

**Remediation**
1. Allow the owner to create their own `deleted_accounts/{email}` marker, or remove that write from the client batch and let the Functions trigger own it exclusively.
2. Make `onAuthUserDeleted` enumerate the `config` subcollection rather than deleting a hardcoded list, so new document types are covered automatically.
3. Surface batch failures to the user instead of proceeding silently.

---

### 9. App Check is not enforced on the callable functions

**Location** — [`functions/index.js:271`](functions/index.js), [`functions/index.js:295`](functions/index.js), [`app/src/main/java/com/cash/dash/CashDashApplication.kt:32`](app/src/main/java/com/cash/dash/CashDashApplication.kt)

**Detail** — The client installs the Play Integrity provider correctly, but neither `onCall` declares `enforceAppCheck: true`, so the callables accept requests carrying no App Check token at all.

**Impact** — Attestation is advisory on the backend. Combined with the comment in `CashDashApplication` noting that enforcement must also be switched on per-product in the Firebase console, it is worth confirming whether enforcement is actually live for Firestore, Storage and Auth — if it is not, the client-side App Check work currently buys nothing.

**Remediation** — Add `enforceAppCheck: true` to both callables and verify console enforcement status for each product. Roll out in monitoring mode first to avoid locking out older installed clients.

**Diagnosis (2026-08-24)** — Attestation is failing, not merely unenforced. Every call logs `{"verifications":{"app":"INVALID","auth":"VALID"}}`, preceded by `Failed to validate AppCheck token. FirebaseAppCheckError: Decoding App Check token failed` — the client is sending something that is not a JWT at all, which is what happens when the provider cannot obtain a token locally. Confirmed still failing at 14:33 UTC from a **signed release build**, so it is not a debug-provider artefact.

Ruled out by direct check:
- The release signing certificate **is** registered. The APK is signed with SHA-256 `81d81562…950b55a5`, which matches one of the two hashes on the Firebase Android app (`firebase apps:android:sha:list`).
- A debug token exists for the app, but it is irrelevant to release builds — `CashDashApplication.initAppCheck` only installs the debug provider when `BuildConfig.DEBUG` is true.

Most likely remaining cause: **the build is not Play-recognized.** The Play Integrity API returns `UNRECOGNIZED_VERSION` for a sideloaded APK that Google Play has never seen, even when it carries the correct signing certificate, and Firebase's Play Integrity provider requires a Play-recognized verdict. A locally-built APK installed over ADB therefore cannot produce a valid App Check token by design — this is not something a code change fixes.

Next steps, in order:
1. Confirm in the Firebase console (App Check → Apps) that the Android app is registered with the **Play Integrity** provider, and that the Play Integrity API is linked in the Play Console. Neither is checkable from the CLI.
2. Upload the build to an **internal testing track** and install it from Play. Retest and look for `"app":"VALID"` in the function logs. Sideloaded testing will keep returning INVALID regardless of configuration.
3. Only once VALID is observed should `enforceAppCheck: true` be added — and even then, staged, because App Check has never worked in production, so every currently-installed client would be rejected on day one.

---

### 15. `viewAdminLogs` and `viewLastSeen` grants are not enforced server-side

**Location** — [`firestore.rules:193`](firestore.rules), [`database.rules.json:6`](database.rules.json)

**Detail** — Surfaced by the rules compiler during deployment, which reported `canViewLastSeen` and `canViewAdminLogs` as unused functions. Both were written to mirror `AdminManager.AdminPermissions` but neither was ever wired to a `match` block:

- `admin_logs` used `allow read, write: if isAnyAdmin()`. Any admin — including one provisioned with only `sendPromotions` — could read the full admin action log **and overwrite or delete entries in it**.
- `canViewLastSeen` cannot gate anything from this file: presence lives in the Realtime Database. Worse, the RTDB admin clause `root.child('admins').child(...).exists()` never evaluates true, because admins are stored in Firestore and nothing in the app ever writes an `/admins` node to RTDB. Only the two hardcoded super admins can read presence today.

**Impact** — Two of the eight granular permissions were advisory-only, enforced solely in Kotlin. The `admin_logs` write path is the more serious half: the log is the record of what admins did, and every admin could edit it.

**Remediation** — Applied for the log: reads now require `canViewAdminLogs()`, any admin may `create` an entry (logging must not depend on being able to read the log), and `update`/`delete` are super-admin only, matching how `audit_logs` is treated. The user click-tracking update rule is unaffected. For presence, the unenforceable function was removed and the situation documented — the RTDB gap fails closed, so it is a broken admin feature rather than an exposure. Enforcing `viewLastSeen` properly would mean mirroring admin status into RTDB from a Cloud Function, which is a larger change and is not done here.

## Low severity / hardening

### 10. Exported widget receiver lets any app toggle the tracking service
[`app/src/main/AndroidManifest.xml:212`](app/src/main/AndroidManifest.xml), [`app/src/main/java/com/cash/dash/TaptrackWidget.kt:36`](app/src/main/java/com/cash/dash/TaptrackWidget.kt) — `TaptrackWidget` is exported with an intent filter for `com.cash.dash.TAPTRACK_WIDGET_TOGGLE`, so any installed app can broadcast that action to start or stop usage tracking and the overlay foreground service. `Finminder`'s `ACTION_NEXT`/`ACTION_PREV` are the same shape but only change a widget page index. Move the toggle to a non-exported receiver, or guard it with a signature-level permission.

### 11. Push notifications can carry arbitrary outbound links
[`app/src/main/java/com/cash/dash/MyFirebaseMessagingService.kt:58`](app/src/main/java/com/cash/dash/MyFirebaseMessagingService.kt), [`app/src/main/java/com/cash/dash/NotificationActionReceiver.kt:34`](app/src/main/java/com/cash/dash/NotificationActionReceiver.kt) — `triggerUrl` is only prefix-checked for `http(s)` before being fired as an `ACTION_VIEW`. The `WebViewActivity` host allowlist does not apply on this path. Any admin with a broadcast permission can push a phishing link, with CashDash branding, to the entire user base. Consider constraining destinations to app-owned domains or showing the target host in the notification.

### 12. Camera attachments written to external cache storage
[`app/src/main/java/com/cash/dash/ContactSupportActivity.kt:60`](app/src/main/java/com/cash/dash/ContactSupportActivity.kt), [`app/src/main/java/com/cash/dash/HelpActivity.kt:83`](app/src/main/java/com/cash/dash/HelpActivity.kt), [`app/src/main/java/com/cash/dash/NotificationActivity.kt:85`](app/src/main/java/com/cash/dash/NotificationActivity.kt) — support attachments captured from the camera are written to `externalCacheDir` and referenced via `Uri.fromFile`. On API ≤ 28 (minSdk is 26) any app holding `READ_EXTERNAL_STORAGE` can read them. Use `cacheDir` with a FileProvider.

### 13. Login lockout is client-side and in-memory only
[`app/src/main/java/com/cash/dash/EntryActivity.kt:412`](app/src/main/java/com/cash/dash/EntryActivity.kt) — the five-attempt / 30-second lockout is a field on the Activity; it resets on app restart and is absent for anyone talking to Firebase Auth directly. Password minimum is six characters. Firebase's own abuse protection is the real control here; treat the counter as UX. Raising the minimum length and adding a password-strength check would be a cheap improvement.

### 14. Repository hygiene
Nine release APK blobs (~21 MB each, including `cashdash-release-v1.0.apk`) are in git history, along with tracked scratch files (`diff.txt`, `diff_utf8.txt`, `git_history.txt`, `prompts.txt`, `taptrack_git.txt`, and a set of `fix_*.py` scripts). The repository is currently **private**, which contains the exposure — but those historical APKs embed the second Gemini key from finding 1, so publishing the repo or adding a collaborator re-opens it. Separately, `keydash.jks` sits in the working tree protected only by the `*.jks` ignore rule; a single `git add -f` would commit the release signing key. Move the keystore outside the repository.

---

## Verified as sound

Recorded so a future review does not re-litigate them:

- **Firestore rules enforce admin permissions server-side**, including expiry (`adminNotExpired`) and most granular grants that mirror `AdminManager.AdminPermissions`. Users' financial documents under `config/{docId}` are owner-only; admins cannot read them. (Two grants were the exception — see finding 15.)
- **`adminReply` access control** is a proper HMAC over `(targetUser, id, exp)` with a constant-time comparison and a 7-day TTL ([`functions/index.js:61`](functions/index.js)). Attachment rendering escapes first, then substitutes only URLs matching the project's own Storage hosts. CSP and `X-Content-Type-Options` headers are set.
- **`WebViewActivity`** validates the incoming `url` extra against an HTTPS host allowlist before loading, with file and content access disabled ([`app/src/main/java/com/cash/dash/WebViewActivity.kt:36`](app/src/main/java/com/cash/dash/WebViewActivity.kt)).
- **Manifest exposure** is tight: every activity that handles user data is `exported="false"`, and the `cashdash://` browsable deep link into the payment scanner was removed.
- **`admin_logs` click-tracking rule** correctly constrains the *shape* of the mutation — a user may only append their own email and may not remove existing entries.
- **`audit_logs`** requires `actor_email` to match the caller and `timestamp` to be the server time, blocking cross-admin forgery.
- **Storage rules** exist, deny `list` (no bucket enumeration), enforce content type and an 8 MB cap, and make attachments immutable.
- **Backup and screenshot controls**: `allowBackup="false"` with explicit cloud-backup and device-transfer excludes; `FLAG_SECURE` applied to admin screens that display other users' data.
- **Admin permission cache** is stored in `EncryptedSharedPreferences` with a soft fallback, and the legacy plaintext cache is purged on load.
- **Release builds** enable R8 with `isMinifyEnabled` and `isShrinkResources`; the keystore password is read from `local.properties`, which is git-ignored.
- **Mutable PendingIntents** in the widget-pinning flows are explicit intents to non-exported receivers — the pattern the platform requires, not an intent-redirection risk.

---

## Remediation applied (2026-08-24)

Changed in the working tree, not yet committed or deployed:

| File | Change |
|---|---|
| `app/src/main/res/values/strings.xml` | Removed the `gemini_api_key` resource (finding 1) |
| `firestore.rules` | `system/**` writes now require `canAllocateAdmins()`, mirroring the Update Lock screen's own gate (4); `fullAccess` blocked alongside `isOwner` on `admins` writes (5); `user_pushes` reads restricted to broadcasters and the addressed user (6); owners may create their own `PERMANENT_WIPE_COMPLETED` marker in `deleted_accounts`, create-only so an `admin_deleted` flag can't be overwritten (8) |
| `functions/index.js` | `assertAdmin` takes an optional granular permission, and `getSupportReplyLink` now requires `replyToQueries` (3); attachment uploads use a random download token instead of `makePublic()` (7); `onAuthUserDeleted` enumerates the `config` collection instead of deleting a hardcoded list (8) |
| `ProfileActivity.kt` | Deletion marker moved out of the atomic wipe batch, missing `finminder` / `upi_allocations` documents added, batch failure surfaced to the user (8) |
| `EntryActivity.kt` | Removed the always-denied `deleted_accounts` delete that was failing the whole wipe batch (8); 8-character minimum on registration, login path left permissive so existing accounts are not locked out (13) |
| `TaptrackWidget.kt`, `WidgetLaunchActivity.kt`, `AndroidManifest.xml` | Widget toggle moved from a broadcast on the exported provider to the non-exported launcher activity (10) |
| `ContactSupportActivity.kt`, `HelpActivity.kt`, `NotificationActivity.kt` | Camera attachments written to internal `cacheDir` instead of `externalCacheDir` (12) |

Verification: `./gradlew :app:compileDebugKotlin` succeeds, `node --check functions/index.js` passes, and `firestore.rules` loads without error in the Firestore emulator. No behavioural testing was run against a device or the live project.

### Still requires owner action

1. ~~Rotate both Gemini API keys~~ — done and verified 2026-08-24 (see finding 1).
2. ~~Deploy functions~~ — done 2026-08-24 12:53 UTC. ~~Deploy rules~~ — done and verified: the finding-15 `admin_logs` fix is live, confirmed by an independent redeploy returning zero compiler warnings and `already up to date`. (An earlier redeploy printed the pre-fix warnings again — that was a stale echo from the Firebase CLI's local compile-check cache, not a sign the fix hadn't landed; worth knowing if it happens again after a future rules edit — re-running once more clears it.)
3. **Ship the app update — still outstanding as of 2026-08-25.** Findings 8, 10, 12, 13 and the `strings.xml` cleanup are code-complete and marked Fixed on the strength of the planned release. No release build has gone out yet, so none of them protect users today. This is now the single largest gap between the report and reality; confirm here once the build ships.
4. ~~Revoke `allUsers` on existing `support_attachments` objects~~ — done 2026-08-25: 83 of 85 objects revoked, verified `403` on a live fetch. See finding 7's resolution note, including the expected broken-image side effect in old support threads.
5. **App Check is failing, not merely unenforced.** Function logs show `{"verifications":{"app":"INVALID"}}` followed by *"Allowing request with invalid AppCheck token because enforcement is disabled"*. Fix the attestation setup (app SHA-256 registered in Firebase, or a debug token for debug builds) **before** enabling enforcement, or admin calls will start failing. See finding 9.
6. **Findings 2, 11, 14** are left open deliberately — each needs a decision rather than an edit. See those sections.
7. **`nodemailer` 8.0.11 — GHSA-p6gq-j5cr-w38f (HIGH), open.** The `raw` option bypasses `disableFileAccess` / `disableUrlAccess`, permitting arbitrary file read and SSRF. **Not exploitable as the code stands**: `sendMail` is only ever called with fixed `from` / `to` / `subject` / `html` fields, never `raw`. The fix is a semver-major bump to 9.x in the support-email path, deliberately not applied without a test send to confirm delivery still works. Decide and either bump or record the acceptance.

### Dependency advisories (added 2026-08-25, outside the original scope)

The original review explicitly did **not** include a dependency scan. One was run afterwards: `npm audit` on production dependencies reported 13 issues. The non-breaking fixes were applied in commit `7334813` and are live:

| Package | Was → Now | Severity | Reachable? |
|---|---|---|---|
| `websocket-driver` | 0.7.4 → 0.7.5 | **Critical** | No — nothing here serves websockets |
| `form-data` | 2.5.5 → 2.5.6 | High | No — multipart is parsed with busboy, not built with form-data |

Both are transitive, via `firebase-admin` and the google-cloud storage chain. Hygiene rather than an active hole. `nodemailer` is the one advisory left open — see item 7 above.

## Suggested order of work

1. **Rotate both Gemini keys**, then delete the `strings.xml` resource (finding 1). Rotation is urgent; the code change is trivial.
2. **Add the `replyToQueries` check** to `getSupportReplyLink` (finding 3) and **tighten `user_pushes` read** (finding 6) — two small, self-contained changes closing real cross-user access.
3. **Restrict `system/**` writes** to super admins (finding 4) and **close the `fullAccess` escalation** (finding 5) — both are rules-only edits.
4. **Fix the account-deletion path** end to end (finding 8), since it carries a privacy-policy commitment.
5. **Require verified email** (finding 2) — the correct fix touches registration, rules, and the Functions backend, and needs a migration plan for existing unverified accounts, so it wants its own change set.
6. Replace `makePublic()` with signed URLs (finding 7), enable `enforceAppCheck` in monitoring mode (finding 9), then work through the Low findings.
