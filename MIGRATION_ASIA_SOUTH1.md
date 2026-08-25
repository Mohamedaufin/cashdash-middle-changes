# Migrating Cloud Functions to asia-south1

| | |
|---|---|
| **Goal** | Move the backend from `us-central1` (Iowa) to `asia-south1` (Mumbai), next to Firestore and the users |
| **Written** | 2026-08-25 |
| **Status** | **Steps 0–2 executed 2026-08-25.** Step 4 (Firestore triggers) not started — needs a chosen quiet hour. |
| **Risk** | Medium. Three Firestore triggers require a delete-then-create window; everything else can run in parallel with zero downtime. |

## Progress

| Step | State |
|---|---|
| 0 — settle unknowns | Done. `onAuthUserDeleted` confirmed **gcfv1** in `us-east1`; decision below is to leave it. Storage bucket location still unverified (no CLI access) — informational only, does not block. |
| 1 — callables to both regions | **Done.** `rephraseSupportText` and `getSupportReplyLink` now deployed in `us-central1` *and* `asia-south1`. Client switched to `asia-south1`. |
| 2 — `adminReply` to both regions | **Done.** New URL: `https://asia-south1-cashdash-8cd8b.cloudfunctions.net/adminReply`. Added to `WebViewActivity.ALLOWED_HOSTS`. `buildReplyUrl` still emits the us-central1 URL on purpose — see below. |
| 3 — verify | Both regions confirmed responding: asia-south1 `adminReply` → 403 on unsigned GET, asia-south1 `rephraseSupportText` → 401 unauthenticated (exists, not 404). us-central1 copies still alive. |
| 4 — Firestore triggers | **Not started.** Has a downtime window. |
| 5 — cleanup | Blocked on step 4 and on Play adoption. |

### Decisions taken during execution

- **`onAuthUserDeleted` stays in `us-east1`.** It is a v1 auth trigger, fires once per account
  deletion, and nothing waits on its latency. Moving a v1 auth trigger risks breaking
  deletion cleanup for no measurable gain.
- **`buildReplyUrl` deliberately still emits the us-central1 URL.** Switching it before a
  client build ships would hand admins on the current build a link whose host is not in
  their `ALLOWED_HOSTS`, and `WebViewActivity` would silently load the default page
  instead of the reply form. Switch it only after the new build has rolled out.
- **Artifact cleanup policy** set for `asia-south1` (deletes container images older than
  1 day), matching the existing behaviour in us-central1.

### Next actions, in order

1. Ship a client build. It points callables at `asia-south1` and allowlists the new
   `adminReply` host. Until it rolls out, existing installs keep using us-central1 —
   which still works, because both regions are live.
2. Once Play adoption shows old versions are gone, switch `buildReplyUrl` to the
   asia-south1 URL, wait 7 days for outstanding links to expire, then drop
   `us-central1` from the three functions' region arrays and redeploy.
3. Do step 4 (triggers) at a quiet hour — see the warning in that section.

---

## Why

Two reasons. The second is the one that should decide it.

### 1. Every Firestore call currently crosses the Pacific

Verified against the live project:

| Resource | Location | Verified by |
|---|---|---|
| Firestore | `asia-south1` | `firebase firestore:databases:get "(default)"` |
| All 7 functions | `us-central1` | `firebase functions:list` |
| RTDB | US (legacy `.firebaseio.com` host) | `firebase database:instances:list` |
| Storage bucket | **unverified** | no CLI available — check before migrating |

`firebase deploy` warns about this directly:

```
onSupportQuery (us-central1, Trigger: asia-south1)
onGlobalPush   (us-central1, Trigger: asia-south1)
onUserPush     (us-central1, Trigger: asia-south1)
```

**Measured**, from the successful rephrase on 2026-08-25:

```
09:41:04.833  App Check verification done
09:41:09.131  Rephrase served by gemini-3.5-flash-lite in 913ms
```

4.30s server-side, of which Gemini was 0.91s. The remaining ~3.4s ran before Gemini
was called. The caller is a super admin, so `assertAdmin` short-circuits without a
Firestore read — which leaves `enforceRateLimit`, a Firestore transaction issued
from Iowa against a database in Mumbai.

**Caveat on that number:** single sample, on an instance serving its first request,
so it also includes one-time Firestore client init (gRPC channel + auth token
fetch, both of which also crossed the Pacific). Cold-start cost and cross-region
cost cannot be separated from one data point. Collect two or three more warm-instance
samples from the `Rephrase served by ... in Nms` log line before quoting a figure.

**What does not depend on that sample:** a Firestore transaction is a minimum of two
sequential round trips (begin+read, then commit). Iowa↔Mumbai is ~200–250ms — a
distance floor, not a tuning problem. So `enforceRateLimit` alone costs ~400–750ms
of pure network on every rephrase, warm or cold. Co-locating collapses that to
~5–20ms. With the client round trip, expect **~0.9–1.2s** back.

Same tax applies everywhere else, on every invocation:

| Function | Cross-region round trips per call |
|---|---|
| `onSupportQuery` | trigger event Mumbai→Iowa, then a read+update transaction back |
| `onUserPush` | trigger event, `users/{email}` get, notification doc set |
| `onGlobalPush` | trigger event |
| `adminReply` | 3+ Firestore ops (GET doc, POST get + set + user get) plus Storage upload |
| `onAuthUserDeleted` | config collection enumerate, batch delete, user doc get |

### 2. Data residency — the actual reason to schedule this

CashDash stores UPI IDs, transaction amounts and financial history for Indian users.
Firestore already lives in Mumbai; the compute that processes all of it does not.

RBI's 2018 *Storage of Payment System Data* directive requires payment system data to
be stored in India, and the DPDP Act 2023 governs cross-border transfer of personal
data. **Whether this app is in scope is a question for a lawyer, not for this
document** — it is an expense tracker that launches UPI intents, not a payment
processor.

But the exposure is directional: if a bank or PSP partnership is ever on the table,
or finance-category scrutiny tightens, "our functions process Indian users' financial
data in Iowa" is a materially weaker position than "everything runs in Mumbai." This
reason does not depend on any latency measurement.

---

## What is coupled to the region

Found by grepping the tree — all of it has to move in lockstep.

| Location | Coupling |
|---|---|
| [`functions/index.js:300`](functions/index.js) | `getSupportReplyLink` → `region: "us-central1"` |
| [`functions/index.js:324`](functions/index.js) | `rephraseSupportText` → `region: "us-central1"` |
| [`functions/index.js:473`](functions/index.js) | `adminReply` → `region: "us-central1"` |
| [`functions/index.js:1032`](functions/index.js) | `onGlobalPush` → `region: "us-central1"` |
| [`functions/index.js:1080`](functions/index.js) | `onUserPush` → `region: "us-central1"` |
| [`functions/index.js:176`](functions/index.js) | `onSupportQuery` — **no region declared**, defaults to us-central1 |
| [`functions/index.js:1149`](functions/index.js) | `onAuthUserDeleted` — v1 auth trigger, currently deployed to `us-east1` |
| [`functions/index.js:89`](functions/index.js) | `buildReplyUrl` hardcodes `https://adminreply-khhfw7mtba-uc.a.run.app` |
| [`GenerativeAiManager.kt:107`](app/src/main/java/com/cash/dash/GenerativeAiManager.kt) | `getInstance("us-central1")` |
| [`SupportReplyLink.kt:27`](app/src/main/java/com/cash/dash/SupportReplyLink.kt) | `getInstance("us-central1")` |
| [`WebViewActivity.kt:33`](app/src/main/java/com/cash/dash/WebViewActivity.kt) | allowlists `adminreply-khhfw7mtba-uc.a.run.app` |

### Two things to verify first

1. **`onAuthUserDeleted` is a v1 auth trigger.** Firebase Auth background triggers have
   historically been restricted in which regions they support, and this one currently
   sits in `us-east1` despite no region being declared. Confirm `asia-south1` is even
   available for v1 auth triggers before planning its move. If it is not, leave it
   where it is — it runs once per account deletion and its latency is irrelevant.
2. **Storage bucket location.** If the bucket is in the US, `adminReply`'s attachment
   uploads keep crossing regardless. Check in the console; a bucket cannot be moved,
   only recreated.

---

## Sequence

Order matters. Steps 1–3 are zero-downtime. Step 4 is the one with a window.

### Step 0 — before touching anything

- Confirm the two unknowns above.
- Note the current `Rephrase served by ... in Nms` numbers so the improvement is measurable.
- Do this at a quiet hour for Indian users. Step 4 has a gap.

### Step 1 — callables, parallel deploy (no downtime)

`rephraseSupportText` and `getSupportReplyLink` are called by the app with an explicit
region, so old installs keep working as long as the Iowa copies stay alive.

1. Deploy both to `asia-south1` **in addition to** us-central1. With firebase-functions v2
   this is `region: ["us-central1", "asia-south1"]` on each.
2. Update both client call sites to `getInstance("asia-south1")` and ship a build.
3. Leave the us-central1 copies running until old app versions have aged out —
   check Play Console's version-adoption numbers, not a guess.
4. Only then drop `us-central1` from the region list and redeploy.

### Step 2 — `adminReply`, parallel deploy (no downtime)

This one is an HTTP function whose URL is embedded in already-sent support emails
with a 7-day TTL.

1. Deploy `adminReply` to `asia-south1` alongside us-central1. Note the new Run URL.
2. Update `buildReplyUrl` to emit the new URL, and add the new host to
   `WebViewActivity.ALLOWED_HOSTS` — **keep the old host in the allowlist** so
   in-app opening of older emailed links still works.
3. Wait **at least 7 days** (the `REPLY_LINK_TTL_MS` window) so every outstanding
   emailed link expires naturally.
4. Delete the us-central1 copy and remove the old host from the allowlist.

### Step 3 — deploy, verify, then step 4

Do not proceed to the triggers until steps 1 and 2 are confirmed working in Mumbai.

### Step 4 — Firestore triggers, delete-then-create (short gap)

`onSupportQuery`, `onGlobalPush`, `onUserPush`.

**These cannot be parallel-run.** Both copies would fire on the same document write:
duplicate support emails to the team, duplicate push notifications to every user.

1. Delete the three us-central1 functions:
   ```
   firebase functions:delete onSupportQuery onGlobalPush onUserPush --region us-central1 --project cashdash-8cd8b
   ```
2. Immediately deploy them with `region: "asia-south1"` (and add an explicit region to
   `onSupportQuery`, which currently has none).
3. Verify: send a test support query, and a test push, and confirm exactly one of each arrives.

**The gap:** writes landing between delete and create fire no trigger, and Firestore does
not replay them. A support query written in that window sets `needs_admin_email: true`
and no email is ever sent — the user sees their message accepted with no reply. Keep the
window to a couple of minutes and do it when nobody is filing support requests.

### Step 5 — cleanup

- Drop `us-central1` from the callables' region lists once adoption allows.
- Delete the us-central1 `adminReply` after the 7-day link window.
- Re-measure the rephrase timing and compare against the Step 0 baseline.

---

## Not covered by this migration

- **RTDB is in the US.** Presence writes from Indian users cross the Pacific and this
  migration does not change that. A Realtime Database instance cannot be relocated;
  it would need a new instance plus a data copy, and `CashDashApplication`'s presence
  code points at the default instance. Separate exercise, lower value — presence is
  fire-and-forget and nobody waits on it.
- **Storage bucket**, if it turns out to be US-located. Also not relocatable in place.
- **Gemini latency.** `generativelanguage.googleapis.com` is a global endpoint; moving
  the caller to Mumbai may or may not change which Google datacentre serves it. Do not
  count this as part of the expected gain.
