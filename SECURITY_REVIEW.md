# CashDash — Security Audit (consolidated)

| | |
|---|---|
| **Date** | 2026-08-26 |
| **HEAD** | `4e180b4` on `main` |
| **App version** | 0.5.0 (versionCode 23) — in Play review |
| **Scope** | Android client, Cloud Functions, Firestore / RTDB / Storage rules, dependencies, build config, repo hygiene |
| **Supersedes** | `CASHDASH_SECURITY_AUDIT.md` (2026-08-24, 22 findings, at `525a302`) and the earlier 15-finding review. Both are merged here. |

**Status: 27 closed · 1 accepted risk · 2 open · 1 unfixable upstream · 1 area newly assessed.**

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

---

## Recently resolved

### C. Both Gemini keys rotated and the old values revoked — **resolved 2026-08-26**

*Kept in full below rather than compressed into the closed table: the rollout evidence and the two firebase-tools traps are the reusable part.*

**Corrected 2026-08-26.** This entry previously said only `GEMINI_API_KEY_ADMIN` needed rotating, on the reasoning that `GEMINI_API_KEY` "had already moved to v2". That was wrong, and the error was mine: v2 was **not** a rotation after the transcript exposure — v2 *is* the replacement key that was created in response to the APK leak, and the replacements are precisely what the 2026-08-24 audit recorded as *"both replacement keys are now also compromised, via this chat transcript"*.

So both live values were written to disk in plaintext:

| Secret | Exposed version | Live version | Status |
|---|---|---|---|
| `GEMINI_API_KEY` | v2 | **v3** | rotated 2026-08-26; v2 DESTROYED, **not yet revoked** |
| `GEMINI_API_KEY_ADMIN` | v1 | **v2** | rotated 2026-08-26; v1 DESTROYED, **not yet revoked** |

Neither is the key that leaked via the APK — that one returns 401 and is dead. This is materially lower risk: a local transcript is not public, not indexed, and not reachable by the bots that scrape APKs and repos. The two transcript files were deleted on 2026-08-26 and no key material remains under `~/.claude`, but deletion does not invalidate a credential, and copies may survive in File History, a restore point, or folder sync.

Rotate both. One redeploy covers both secrets:

```bash
firebase functions:secrets:set GEMINI_API_KEY --project cashdash-8cd8b --data-file=-
firebase functions:secrets:set GEMINI_API_KEY_ADMIN --project cashdash-8cd8b --data-file=-
firebase deploy --only functions:rephraseSupportText --project cashdash-8cd8b
```

The redeploy is required, not optional — functions pin a secret version at deploy time, so setting a new version without redeploying leaves the old one serving traffic.

**Done 2026-08-26.** Both secrets were rotated and the superseded versions destroyed. Verified end state: `GEMINI_API_KEY` v3 ENABLED (v1, v2 DESTROYED); `GEMINI_API_KEY_ADMIN` v2 ENABLED (v1 DESTROYED).

Rollout, from the Cloud Functions audit log (UTC):

| Time | Event |
|---|---|
| 08:26 | Deploy binds `GEMINI_API_KEY=3` **and** `GEMINI_API_KEY_ADMIN=2` in both `us-central1` and `asia-south1` |
| 08:34:50 | `scope=messaging` served 200 — exercises v3 |
| 08:35:06 | `scope=admin` served 200 — exercises v2 |
| 08:37–08:38 | Second deploy, **code only** (`firebase-functions-hash` 62b769a7 → aa36f3cd); secret bindings byte-identical |

Both scopes were verified against the new keys *before* the second deploy, and that deploy changed no bindings — so the 08:35 admin success is valid evidence for v2.

**Revoked at Google AI Studio on 2026-08-26**, closing the finding. Destroying a Secret Manager version only deletes Google's stored copy; revocation at the provider is what actually invalidates the string, and until it happened the exposed values stayed callable by anyone holding the transcript.

Note the evidence standard differs from the APK-leaked key in the resolved table below, which was confirmed dead with a 401 probe. That is not reproducible here: the exposed values are gone from our side too — Secret Manager versions destroyed, transcripts deleted — so there is no string left to probe with. This entry rests on the AI Studio console state rather than an observed 401. If a copy of either value does resurface, probe it then.

Two CLI traps found while doing this, both worth remembering:

- **`functions:secrets:prune` silently does nothing for these secrets.** It filters to `labels.firebase-managed=true` (`firebase-tools/lib/functions/secrets.js:164`), and neither secret carries that label — they were created outside the Firebase CLI. It reports *"All secrets are in use. Nothing to prune today"* even with three unused versions sitting there. Don't read that as a clean bill of health.
- **`functions:secrets:destroy`'s "in use" warning is version-blind.** It calls `inUse(…, sv.secret, e)` (`lib/commands/functions-secrets-destroy.js:32`), which matches on secret *name* only; the version-aware `versionInUse` right beside it is never called. The warning fires identically for the live version, so `-f` carries no protection — verify the version against the deployed bindings by hand before destroying.

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
| `old #10` | XSS in the admin reply page | `esc()` everywhere, attachment **bucket** allowlist (tightened 2026-08-26, see `F`), CSP |
| `old #11` | Any user can rewrite `admin_logs` click arrays | Append-own-email only, removals rejected |
| `old #18` | RTDB: users can't read own presence | Fixed |
| `old #22` | `audit_logs` forgeable by any admin | `actor_email` must equal caller, server timestamp |
| `prev #3` | `getSupportReplyLink` ignored `replyToQueries` | `assertAdmin` takes a granular permission |
| `prev #4` | Any admin could force-update-lock every user | `system/**` writes require `canAllocateAdmins()` |
| `prev #5` | `admins` self-escalation via `fullAccess` | Blocked alongside `isOwner` — the older audit missed this path |
| `prev #6` | `user_pushes` readable by every signed-in user | Restricted to broadcasters and the addressed user |
| `prev #7` / `old #13` | Attachments permanently world-public | Download tokens replace `makePublic()`; **83 legacy public objects revoked, verified 403** |
| `prev #15` | `viewAdminLogs` / `viewLastSeen` grants unenforced | `admin_logs` gated; `mirrorAdminToRtdb` + RTDB rules now enforce `viewLastSeen` |
| `F` — found & fixed 2026-08-26 | Attachment allowlist matched **host**, not bucket — any public bucket on `storage.googleapis.com` rendered on the admin reply page | `isTrustedAttachment()` parses the URL and requires one of our own buckets; both URL shapes and the legacy `appspot.com` name accepted, foreign-bucket and suffix-confusion hosts rejected |
| `G` — found & fixed 2026-08-26 | `rephraseSupportText` called `assertAdmin()` with no grant, so any admin could spend the shared Gemini quota | `assertAdmin` now accepts an any-of array; the requested scope selects the grants, mirroring `canBroadcast()` |

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
| ~~**`WalletPrefs` stored plaintext**~~ | **Fixed 2026-08-26.** Now encrypted via [SecurePrefsStore.kt](app/src/main/java/com/cash/dash/SecurePrefsStore.kt) — same `MasterKey` / `EncryptedSharedPreferences` scheme as the admin cache, with a one-time migration off the plaintext file. **Migration verified on a physical device** (7 instrumented tests). Ships in the next release. |
| ~~**`LocalScanPrefs` stores `last_upi` in plaintext**~~ | **Fixed 2026-08-26.** Now encrypted via `ScanStore`, sharing the same base class. The Firestore undo round-trip keeps the original wire name, so existing cloud documents stay readable — `prefsByWireName()` in `FirestoreSyncManager` maps the wire name onto the encrypted store. **Migration verified on device.** Ships in the next release. |
| ~~No root / emulator / debugger detection~~ | **Implemented 2026-08-26** — [TamperCheck.kt](app/src/main/java/com/cash/dash/TamperCheck.kt). Root paths incl. Magisk, `test-keys`, writable system dirs, emulator fingerprints, attached debugger, `FLAG_DEBUGGABLE` on a release build, and non-Play install source. Recorded app-wide; **acted on only in the payment path** (`ScannerActivity`), which warns the user. Emulator and sideload are reported but never enforced — QA devices and internal-testing builds are legitimate. |
| ~~No certificate pinning~~ | **Implemented 2026-08-26, scoped** — [network_security_config.xml](app/src/main/res/xml/network_security_config.xml) pins `cashdash.co.in` and forbids cleartext app-wide. **Firebase is deliberately not pinned.** Pins are the GTS **intermediate + root**, not the leaf: the live leaf expires 2026-09-28 and GTS rotates on ~90 days, so a leaf pin would have broken the WebView within weeks. `expiration="2027-06-01"` degrades to normal CA validation rather than an outage if a rotation is missed. |
| ~~No string encryption~~ | **Still declined.** Nothing left to hide: the Gemini keys that justified it no longer ship in the client, and there are no other secrets in `res/` or `BuildConfig`. Adding it means a paid obfuscator or a build plugin, for constants an attacker gains nothing from. |
| ~~`-keep` exposes the data model~~ | **Fixed 2026-08-26 — and the original justification was wrong.** The keeps were labelled "Firestore serialization", but the codebase does no reflective mapping: `toObject()` is never called, documents are read field-by-field, entities are built via explicit constructors, RTDB `getValue()` is only used with `String`/`Long`, and there is no Gson/Moshi/Jackson. Room needs no entity keeps either — DAOs are generated at compile time and renamed consistently. Keeps removed; **verified in `mapping.txt` that `NotificationModel -> d2.F4` with fields renamed to `a`, `b`, `c`**. Only `-keep class * extends RoomDatabase` remains, and it is genuinely required: `Room.databaseBuilder` resolves `AppDatabase_Impl` by name. |
| Encrypted prefs fall back to plaintext | **Closed for the admin cache 2026-08-26** — `securePrefs()` now returns null and the cache is skipped rather than written in the clear, which had quietly reopened the exact tamper path the encryption exists to close, on the least trustworthy devices. Safe because permissions reload from Firestore on a miss. **Still applies to `WalletStore` / `ScanStore` by design**: their data cannot be refetched, so losing it is worse than storing it in the clear on the minority of devices with no working keystore. |
| No standalone integrity check | Play Integrity exists only via App Check, which is not enforced (see B). Effectively zero tamper signal today. |

### Recommendation

1. ~~**Encrypt `WalletPrefs`**~~ — **done 2026-08-26.** See the implementation note below.
2. ~~**Encrypt `LocalScanPrefs`**~~ — **done 2026-08-26.**
3. ~~**Root detection**~~ — **implemented 2026-08-26** on request, after the tradeoffs were put and accepted. Built as detect-and-warn rather than detect-and-block: the payment screen tells the user plainly and lets them decide, so a customer on a custom ROM is not locked out of a feature to stop an attacker who is only attacking their own device. Escalating to a hard block is a two-line change, documented at the call site.
4. **Standalone integrity check — still blocked on B.** Play Integrity is already wired through App Check; enforcing it is the work, not adding a second path. `TamperCheck` gives a local signal in the meantime, but nothing server-side consumes it until B is on.
5. ~~**Narrowing the `-keep` rules**~~ — **done 2026-08-26.** My earlier reasoning for declining this was wrong: I asserted the classes were round-tripped through Firestore's `toObject()`, without checking. `toObject()` is never called in this codebase. With that established the change is safe, and it is verified in the release mapping.
6. ~~**Certificate pinning**~~ — **done 2026-08-26, for `cashdash.co.in` only.** The original objection stands for Firebase and that is still unpinned; it does not apply to a domain we control and can re-pin ahead of.
7. **String encryption — still declined.** Its entire justification was the Gemini keys, and none remain in the client.

Two deliberate calls, unchanged and still correct: `FLAG_SECURE` is *not* applied to wallet/history/payment screens, because plenty of users legitimately screenshot their spending — a product decision. And `Log.e` is *not* stripped, since an app can only read its own logcat since Android 4.1, making the leak require ADB or physical access — marginal security value against a real cost to debugging.

### Hygiene

~~`app/src/main/res/xml/accessibility_service_config.xml` exists but no accessibility service is declared in the manifest.~~ **Deleted 2026-08-26** after confirming nothing referenced it.

### Implementation note — `SecurePrefsStore` (2026-08-26)

`WalletStore` and `ScanStore` are both `object`s extending one `SecurePrefsStore` base class (42 call sites across the two), so the migration and caching logic exists once and the two cannot drift apart.

Encryption is applied to a **new file** (`WalletPrefs_v2`) rather than in place, because `EncryptedSharedPreferences` cannot read a plaintext file. The old contents are copied once and the old file is then cleared, using `commit()` rather than `apply()` so the new file is durable *before* the old one is emptied — a crash between the two would otherwise lose a user's balance. If the write fails the legacy file is left intact and the copy retries next launch.

Three things checked before writing any code, each of which would have made this unsafe:

- **Change listeners still work.** [CashDashApplication](app/src/main/java/com/cash/dash/CashDashApplication.kt) watches `wallet_balance` to drive `Finminder.pushUpdate`. Verified against the `security-crypto` 1.1.0-alpha06 bytecode that `Editor.notifyListeners()` dispatches from `mKeysChanged`, which records the **plaintext** key before encryption — so the listener keeps firing.
- **Single process.** No `android:process` anywhere in the manifest. `EncryptedSharedPreferences` is explicitly not multi-process safe, so this mattered.
- **The account-wipe paths enumerate prefs by filename.** `WalletPrefs_v2` was added alongside `WalletPrefs` in all five lists (`EntryActivity`, `FirestoreSyncManager`, `ProfileActivity` ×2, `SecurityManager`); without that, deleting an account would have left the encrypted wallet behind.

The instance is cached, because `EncryptedSharedPreferences.create()` does keystore work per call and the wallet is read on widget refreshes and every HomeFragment bind.

### Launch crash, 2026-08-26 — found on device, fixed

The first version of this shipped two defects that only appear once the file exists and the keys stop matching it.

**The store had no recovery path.** `EncryptedSharedPreferences.create()` succeeds even when the file cannot be decrypted; the failure surfaces later, on the first read or on `edit().clear()`, which calls `getAll()` internally and decrypts every key. When the Tink keyset and the master key drift apart — keystore reset, restored data directory, regenerated keyset — every launch threw `SecurityException: Could not decrypt key` from `FirestoreSyncManager`'s cloud-pull path. **The app could not start at all.**

The store now verifies readability at open time by forcing `prefs.all`, and on failure deletes the file and rebuilds. Discarding the local cache is the right trade: this data is mirrored in Firestore and re-pulls on the next sync, against an alternative of an app that never opens.

**The plaintext fallback shared the encrypted filename.** That was deliberate and wrong — it produces a file holding both encrypted and plaintext entries, and the first undecryptable one throws. The fallback now uses a separate file, and anything written there is migrated in when the keystore recovers.

Both are covered by regression tests that fail against the old code.

**Verified on device 2026-08-26.** [SecurePrefsStoreMigrationTest](app/src/androidTest/java/com/cash/dash/SecurePrefsStoreMigrationTest.kt) — 7 instrumented tests, all passing on a physical Galaxy S23 against a real Android keystore:

- every value survives migration with its type intact (int / string / boolean / long / float / string-set)
- the legacy plaintext file is emptied afterwards
- **the new file is genuinely encrypted** — reading it with a plain `getSharedPreferences` shows neither the plaintext key names nor the values, which is what catches a silent fallback
- a fresh install with no legacy file is a no-op, not a wipe
- a relaunch after migrating does not clobber newer data
- `WalletStore` / `ScanStore` are pinned to the filenames the wipe and sync lists use
- the change listener still receives **plaintext** keys, so `Finminder.pushUpdate` keeps firing — previously confirmed only by reading library bytecode, now pinned on-device so a `security-crypto` upgrade cannot regress it unnoticed

The tests construct their own `SecurePrefsStore` over throwaway filenames rather than driving the `WalletStore` singleton. That is deliberate: a test that migrated the *real* `WalletPrefs` file would destroy live data on any device it were ever run against. The class under test is identical; only the filenames differ, and the last test pins those.

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
2. ~~**Revoke the old Gemini keys at AI Studio**~~ (C) — done 2026-08-26; rotation, Secret Manager cleanup and revocation all complete.
3. **App Check → Monitor, then Enforce** (B) once adoption climbs.
4. **Finish the region migration** after adoption.
5. **Email verification** (A) — the remaining High, alongside Google Sign-In.
6. ~~Encrypt `WalletPrefs` and `LocalScanPrefs`~~ — done and **verified on device** 2026-08-26 (7 instrumented tests). One thing the tests deliberately do not cover, worth a manual look when 0.5.0 reaches a real install: that a **cloud sync round-trips** after migration — the tests cover the on-device migration, not `FirestoreSyncManager`'s push/restore against a live project.
7. **Write rules tests.** `firestore.rules` is 261 lines with 12 helper predicates and has never been executed against a test. It is the control everything else in this document leans on. No emulator block exists in `firebase.json` and there is no test infrastructure in the repo. Start with the four cases the original audit named: the `admin_logs` append rule, an admin holding only `replyToQueries` being denied `users/*/config/wallet`, an expired admin being denied everything, and one end-to-end support reply through a signed link.
8. **`firebase-functions` 7.2.5 → 7.3.2** — already within the `^7.2.5` range, so a plain `npm update` in `functions/`. Leave `firebase-admin` at 13.x; 14 removes the legacy `admin.*` namespace and breaks 8 call sites.

Delete the backup bundle `S:/AndroidStudioProjects/cashdash-backup-20260825-231858.bundle` once satisfied with the history rewrite; it still contains the old APKs and therefore the revoked keys.

**This file documents unfixed weaknesses with exact rule paths.** Fine while both repos are private. Reconsider before making either public or adding outside collaborators.
