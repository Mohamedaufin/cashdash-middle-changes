# CashDash — Security Audit (consolidated)

| | |
|---|---|
| **Date** | 2026-08-26 |
| **HEAD** | `4e180b4` on `main` |
| **App version** | 0.5.0 (versionCode 23) — in Play review |
| **Scope** | Android client, Cloud Functions, Firestore / RTDB / Storage rules, dependencies, build config, repo hygiene |
| **Supersedes** | `CASHDASH_SECURITY_AUDIT.md` (2026-08-24, 22 findings, at `525a302`) and the earlier 15-finding review. Both are merged here. |

**Status: 24 closed · 1 accepted risk · 3 open · 1 unfixable upstream · 1 area newly assessed.**

Reverse-engineering and tamper posture — carried as *"not covered"* by both earlier reviews — was assessed on 2026-08-26 and now has its own section. Everything actionable it produced has been done: `WalletPrefs` and `LocalScanPrefs` are both encrypted at rest, and the admin cache no longer falls back to plaintext. What remains is either blocked on App Check enforcement or deliberately declined — see that section's recommendations, which record the reasoning rather than leaving them as open to-dos.

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

## Reverse-engineering and tamper posture `[old #15]` — assessed 2026-08-26

Previously listed as *"not covered"* in both reviews. Now actually examined against current source. Nothing here exposes another user's data — server-side rules do that work and they hold. This is a **device-scoped** problem: it concerns a user tampering with their own client, and an attacker reading your schema out of the APK.

### Verified sound

`isMinifyEnabled` + `isShrinkResources` on release with `proguard-android-optimize`; release signing read from gitignored `local.properties` with the keystore outside the repo; `allowBackup="false"`, `fullBackupContent="false"` and 10 explicit extraction excludes; `debuggable` never set; `Log.d/v/i/w` stripped via `-assumenosideeffects`; no hardcoded API keys anywhere in `res/`.

**The admin permission cache is genuinely encrypted** — [AdminManager.kt:65](app/src/main/java/com/cash/dash/AdminManager.kt) builds a `MasterKey` (AES256_GCM) and uses `EncryptedSharedPreferences` with `AES256_SIV` keys / `AES256_GCM` values, and `purgeLegacyPlaintextCache()` clears the old plaintext `admin_perms_cache` on every load. `FLAG_SECURE` is applied through [SecureScreen.kt](app/src/main/java/com/cash/dash/SecureScreen.kt) on all 5 admin screens.

### Residual gaps

| Gap | Assessment |
|---|---|
| ~~**`WalletPrefs` stored plaintext**~~ | **Fixed 2026-08-26.** Now encrypted via [WalletStore.kt](app/src/main/java/com/cash/dash/WalletStore.kt) — same `MasterKey` / `EncryptedSharedPreferences` scheme as the admin cache. 34 call sites across 14 files routed through it, with a one-time migration off the plaintext file. **Ships in the next release; migration is not runtime-tested.** |
| ~~**`LocalScanPrefs` stores `last_upi` in plaintext**~~ | **Fixed 2026-08-26.** Now encrypted via `ScanStore`. The Firestore undo round-trip keeps the original wire name, so existing cloud documents stay readable — `prefsByWireName()` in `FirestoreSyncManager` maps the wire name onto the encrypted store. **Ships in the next release; migration not runtime-tested.** |
| No root / emulator / debugger detection | Nothing present. A UPI payment app runs unmodified under Frida. |
| No certificate pinning | No `networkSecurityConfig` declared at all. |
| No string encryption | R8 obfuscates identifiers, never string constants. |
| `-keep` exposes the data model | `NotificationEntity`, `TransactionEntity`, `NotificationModel` and `@Room.Entity` kept whole — schema fully readable in the DEX. Required for Room/Firestore reflection, so not removable without breaking them. |
| Encrypted prefs fall back to plaintext | **Closed for the admin cache 2026-08-26** — `securePrefs()` now returns null and the cache is skipped rather than written in the clear, which had quietly reopened the exact tamper path the encryption exists to close, on the least trustworthy devices. Safe because permissions reload from Firestore on a miss. **Still applies to `WalletStore` / `ScanStore` by design**: their data cannot be refetched, so losing it is worse than storing it in the clear on the minority of devices with no working keystore. |
| No standalone integrity check | Play Integrity exists only via App Check, which is not enforced (see B). Effectively zero tamper signal today. |

### Recommendation

1. ~~**Encrypt `WalletPrefs`**~~ — **done 2026-08-26.** See the implementation note below.
2. ~~**Encrypt `LocalScanPrefs`**~~ — **done 2026-08-26.**
3. **Root detection — recommended against, for now.** Not that it is worthless, but that it is worthless *here*: client-side detection is only meaningful if something consumes the signal, and the natural consumer is App Check, which is not enforced (item B). Until then a detector is code an attacker patches out in minutes, carrying real false-positive risk against users on custom ROMs — a blocking check would deny service to legitimate customers to stop an attacker who is only attacking their own device. **Revisit once B is enforced**, when the signal has somewhere to go.
4. **Standalone integrity check — blocked on B**, same reasoning. Play Integrity is already wired through App Check; enforcing it is the work, not adding a second path.
5. **Narrowing the `-keep` rules — recommended against.** In principle some could be tightened, since Room generates its DAOs at compile time and does not need runtime reflection. But the same classes are round-tripped through Firestore's `toObject()`, which does need field names. Getting it wrong silently corrupts data mapping in release builds only — debug builds do not minify, so no local build would catch it. A schema visible in the DEX is a low-severity trade against a data-corruption risk that only surfaces in production.
6. **Certificate pinning — recommended against.** Pinning Firebase SDK traffic is a known operational footgun: Google rotates certificates, and a stale pin bricks the app for every user with no server-side remedy.
7. **String encryption — now low value.** Its entire justification was the Gemini keys, and none remain in the client.

Two deliberate calls, unchanged and still correct: `FLAG_SECURE` is *not* applied to wallet/history/payment screens, because plenty of users legitimately screenshot their spending — a product decision. And `Log.e` is *not* stripped, since an app can only read its own logcat since Android 4.1, making the leak require ADB or physical access — marginal security value against a real cost to debugging.

### Hygiene

~~`app/src/main/res/xml/accessibility_service_config.xml` exists but no accessibility service is declared in the manifest.~~ **Deleted 2026-08-26** after confirming nothing referenced it.

### Implementation note — `WalletStore` (2026-08-26)

Encryption is applied to a **new file** (`WalletPrefs_v2`) rather than in place, because `EncryptedSharedPreferences` cannot read a plaintext file. The old contents are copied once and the old file is then cleared, using `commit()` rather than `apply()` so the new file is durable *before* the old one is emptied — a crash between the two would otherwise lose a user's balance. If the write fails the legacy file is left intact and the copy retries next launch.

Three things checked before writing any code, each of which would have made this unsafe:

- **Change listeners still work.** [CashDashApplication](app/src/main/java/com/cash/dash/CashDashApplication.kt) watches `wallet_balance` to drive `Finminder.pushUpdate`. Verified against the `security-crypto` 1.1.0-alpha06 bytecode that `Editor.notifyListeners()` dispatches from `mKeysChanged`, which records the **plaintext** key before encryption — so the listener keeps firing.
- **Single process.** No `android:process` anywhere in the manifest. `EncryptedSharedPreferences` is explicitly not multi-process safe, so this mattered.
- **The account-wipe paths enumerate prefs by filename.** `WalletPrefs_v2` was added alongside `WalletPrefs` in all five lists (`EntryActivity`, `FirestoreSyncManager`, `ProfileActivity` ×2, `SecurityManager`); without that, deleting an account would have left the encrypted wallet behind.

The instance is cached, because `EncryptedSharedPreferences.create()` does keystore work per call and the wallet is read on widget refreshes and every HomeFragment bind. As on the admin cache, it falls back to plaintext if the keystore is unavailable — but to the *same* filename, so a device that later gains keystore support does not start from an empty wallet.

**Verified:** `:app:assembleDebug` passes. **Not verified:** the migration has not been exercised on a device with real data. Worth one manual check on an install that has a balance set before shipping.

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
6. ~~Encrypt `WalletPrefs` and `LocalScanPrefs`~~ — done 2026-08-26. Before shipping, **exercise both migrations once on an install that already has a balance and a saved UPI** — this is the one part that compiles but has not been run against real data. Check specifically that the balance survives, the scanner still offers the last UPI, and a cloud sync round-trips.

Delete the backup bundle `S:/AndroidStudioProjects/cashdash-backup-20260825-231858.bundle` once satisfied with the history rewrite; it still contains the old APKs and therefore the revoked keys.

**This file documents unfixed weaknesses with exact rule paths.** Fine while both repos are private. Reconsider before making either public or adding outside collaborators.
