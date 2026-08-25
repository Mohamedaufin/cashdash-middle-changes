const { onRequest, onCall, HttpsError } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const nodemailer = require("nodemailer");
const crypto = require("crypto");

admin.initializeApp();
const db = admin.firestore();

const { defineSecret } = require("firebase-functions/params");

// ─────────────────────────────────────────────
// CONFIGURATION & SECRETS
// ─────────────────────────────────────────────
const ADMIN_EMAIL = "support@cashdash.co.in";
const GMAIL_USER = "support@cashdash.co.in";
const EMAIL_PASS_SECRET = defineSecret("GMAIL_PASS"); // Holds the Zoho App Password.

// Signs the one-click admin reply links. Set with:
//   firebase functions:secrets:set REPLY_SIGNING_SECRET
const REPLY_SIGNING_SECRET = defineSecret("REPLY_SIGNING_SECRET");

// Gemini keys, moved off the Android client. Two of them, mirroring the split the
// app used to have hardcoded, so the two areas draw on separate billing quotas:
//   GEMINI_API_KEY       -> announcements + push notifications (AdminMessagingActivity)
//   GEMINI_API_KEY_ADMIN -> promotions + admin access (AdminPromotions/ManageAdminAccess)
// Set with: firebase functions:secrets:set <NAME>
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
const GEMINI_API_KEY_ADMIN = defineSecret("GEMINI_API_KEY_ADMIN");

const REPHRASE_SCOPES = {
    messaging: () => GEMINI_API_KEY.value(),
    admin: () => GEMINI_API_KEY_ADMIN.value(),
};

// Every function in this file pays for the module-scope requires above
// (firebase-admin, nodemailer, busboy), which measured 133-135 MiB at startup.
// At 128MiB the container failed its readiness probe and never started —
// "Memory limit of 128 MiB exceeded with 135 MiB used" — so rephraseSupportText
// and onSupportQuery were dead on every invocation. 256MiB is the floor here;
// do not lower it without checking actual startup memory in the logs.

const SUPER_ADMINS = ["mohamedaufin64@gmail.com", "arunbhalaji200904@gmail.com"];

const REPLY_LINK_TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7 days
const MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
const MAX_UPLOAD_FILES = 4;
const ALLOWED_UPLOAD_TYPES = ["image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif"];

// ─────────────────────────────────────────────
// SECURITY HELPERS
// ─────────────────────────────────────────────

/** Escape a value for interpolation into HTML text or a quoted attribute. */
function esc(value) {
    return String(value == null ? "" : value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

/**
 * The admin reply page is opened from an email client and from an in-app WebView,
 * neither of which carries a Firebase session. Access is therefore granted by an
 * HMAC-signed, expiring token bound to the exact (targetUser, notificationId) pair.
 */
function signReplyToken(targetUser, id, exp) {
    return crypto
        .createHmac("sha256", REPLY_SIGNING_SECRET.value())
        .update(`${targetUser}|${id}|${exp}`)
        .digest("base64url");
}

function verifyReplyToken(targetUser, id, exp, sig) {
    if (!targetUser || !id || !exp || !sig) return false;
    const expNum = Number(exp);
    if (!Number.isFinite(expNum) || expNum < Date.now()) return false;

    const expected = Buffer.from(signReplyToken(targetUser, id, expNum));
    const provided = Buffer.from(String(sig));
    if (expected.length !== provided.length) return false;
    return crypto.timingSafeEqual(expected, provided);
}

function buildReplyUrl(targetUser, id, email) {
    const exp = Date.now() + REPLY_LINK_TTL_MS;
    const sig = signReplyToken(targetUser, id, exp);
    const q = new URLSearchParams({
        uid: String(targetUser),
        id: String(id),
        email: String(email || ""),
        exp: String(exp),
        sig,
    });
    return `https://adminreply-khhfw7mtba-uc.a.run.app?${q.toString()}`;
}

/**
 * Reject anything that is not a currently-valid admin. Returns the caller's email.
 *
 * [requiredPermission] names a granular grant from AdminManager.AdminPermissions
 * (e.g. "replyToQueries"). Pass it whenever the function does something the
 * Firestore rules gate on that same grant — otherwise the callable becomes a way
 * around the rule, which is exactly what getSupportReplyLink used to be.
 * `isOwner` / `fullAccess` are blanket grants, matching adminHasBlanket() in
 * firestore.rules.
 */
async function assertAdmin(request, requiredPermission = null) {
    const email = request.auth && request.auth.token && request.auth.token.email
        ? String(request.auth.token.email).toLowerCase()
        : null;
    if (!email) throw new HttpsError("unauthenticated", "Sign in required.");
    if (SUPER_ADMINS.includes(email)) return email;

    const snap = await db.collection("admins").doc(email).get();
    if (!snap.exists) throw new HttpsError("permission-denied", "Admin access required.");

    const data = snap.data() || {};
    const validUntil = Number(data.validUntil || 0);
    if (validUntil > 0 && validUntil < Date.now()) {
        throw new HttpsError("permission-denied", "Admin access has expired.");
    }

    if (requiredPermission) {
        const hasBlanket = data.isOwner === true || data.fullAccess === true;
        if (!hasBlanket && data[requiredPermission] !== true) {
            throw new HttpsError("permission-denied", "Your admin access does not allow this.");
        }
    }
    return email;
}

/** Fixed-window rate limit, enforced server-side so it cannot be bypassed by the client. */
async function enforceRateLimit(key, maxPerWindow, windowMs) {
    const ref = db.collection("rate_limits").doc(key);
    const now = Date.now();
    await db.runTransaction(async (t) => {
        const snap = await t.get(ref);
        const data = snap.exists ? snap.data() : null;
        if (!data || now - Number(data.windowStart || 0) >= windowMs) {
            t.set(ref, { windowStart: now, count: 1 });
            return;
        }
        if (Number(data.count || 0) >= maxPerWindow) {
            throw new HttpsError("resource-exhausted", "Rate limit exceeded. Please try again shortly.");
        }
        t.update(ref, { count: Number(data.count || 0) + 1 });
    });
}

// ─────────────────────────────────────────────
// NODEMAILER HELPER
// ─────────────────────────────────────────────
function createTransporter() {
    return nodemailer.createTransport({
        host: "smtp.zoho.in",
        port: 465,
        secure: true,
        auth: {
            user: GMAIL_USER,
            pass: EMAIL_PASS_SECRET.value(),
        },
    });
}

const { onDocumentWritten } = require("firebase-functions/v2/firestore");

// ─────────────────────────────────────────────
// FUNCTION 1: onSupportQuery
// Fires on the Firestore write the app makes. Firestore rules already restrict
// that write to the document owner, so this trigger is the authenticated path
// for support mail. (The old unauthenticated `cashdashWebhook` duplicated this
// and has been removed.)
// ─────────────────────────────────────────────
exports.onSupportQuery = onDocumentWritten({
    document: "users/{userEmail}/notifications/{notificationId}",
    secrets: [EMAIL_PASS_SECRET, REPLY_SIGNING_SECRET],
    memory: "256MiB",
    maxInstances: 10
}, async (event) => {
    const newData = event.data.after.data();
    if (!newData) return; // Document was deleted, ignore

    // Only process if the offline queue flag was set to true by the Android app
    if (newData.needs_admin_email === true) {
        // Use a transaction to atomically check & clear the flag, preventing duplicate sends
        const docRef = event.data.after.ref;
        const shouldSend = await db.runTransaction(async (t) => {
            const freshDoc = await t.get(docRef);
            if (freshDoc.exists && freshDoc.data().needs_admin_email === true) {
                t.update(docRef, { needs_admin_email: false });
                return true;
            }
            return false; // Another instance already cleared it
        });
        if (!shouldSend) return; // Duplicate detected, skip

        const { name, email, subject, query } = newData;
        const targetUser = email || event.params.userEmail;
        const id = event.params.notificationId;

        // For emails: swap the user's real name label to "User:" for clean admin display
        const userName = name || "User";
        let emailQuery = (query || "").replace(new RegExp(userName + ":", "g"), `User (${userName}):`);
        // Ensure the message has a clear label in emails
        if (!emailQuery.startsWith(`User (${userName}):`) && !emailQuery.startsWith("Team Cashdash:")) {
            emailQuery = `User (${userName}): ` + emailQuery;
        }

        // Remove attachment links/tags from the email body
        let cleanEmailQuery = emailQuery.replace(/\[Attachment:\s*(https?:\/\/[^\s\]]+)\]/g, "").trim();

        // If the query is just the prefix, it means only an attachment was sent
        if (cleanEmailQuery.trim() === `User (${userName}):`) {
            cleanEmailQuery = `User (${userName}): (Sent an Attachment)`;
        }

        const dateStr = newData.time || new Date(newData.timestamp || Date.now()).toLocaleString("en-US", {
            month: "short", day: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", hour12: false
        });

        const replyUrl = buildReplyUrl(targetUser, id, email);

        let emailBody = "";
        let emailSubject = "";

        if (newData.is_reply) {
            emailSubject = `Follow-up: ${subject || "User Query"}`;
            emailBody = `
NEW FOLLOW-UP MESSAGE

Name: ${name || "User"}
Email: ${email || "No Email"}
Time: ${dateStr}

-----------------------------------
CONVERSATION THREAD:
-----------------------------------
${cleanEmailQuery || "No query text found."}
-----------------------------------

REPLY TO THIS QUERY:
Click the link below to send your reply directly to the user's app.
This link expires in 7 days.

${replyUrl}
`.trim();
        } else {
            emailSubject = `New Query: ${subject || "User Query"}`;
            emailBody = `
NEW SUPPORT QUERY RECEIVED

Name: ${name || "User"}
Email: ${email || "No Email"}
Time: ${dateStr}
Subject: ${subject || "General Help"}

-----------------------------------
MESSAGE:
-----------------------------------
${cleanEmailQuery || "No query text found."}
-----------------------------------

REPLY TO THIS QUERY:
Click the link below to send your reply directly to the user's app.
This link expires in 7 days.

${replyUrl}
`.trim();
        }

        const mailOptions = {
            from: `"CashDash Support" <${GMAIL_USER}>`,
            to: ADMIN_EMAIL,
            subject: emailSubject,
            text: emailBody,
        };

        try {
            await createTransporter().sendMail(mailOptions);
            console.log(`Successfully dispatched email for ${targetUser}`);
        } catch (err) {
            console.error(`Error dispatching email:`, err);
        }
    }
});

// ─────────────────────────────────────────────
// FUNCTION 2: getSupportReplyLink (callable, admin-only)
// The in-app admin inbox used to build the reply URL itself. It cannot any more,
// because the URL now needs a server-held signing key — so it asks for one here.
//
// The link it mints grants read + reply on one support thread, which is the
// access firestore.rules restricts to canReplyToQueries(). It therefore requires
// that same grant: without the check, an admin holding only (say) viewLastSeen
// could pull any user's support conversation and answer as CashDash Support.
// ─────────────────────────────────────────────
exports.getSupportReplyLink = onCall({
    region: "us-central1",
    secrets: [REPLY_SIGNING_SECRET],
    memory: "256MiB",
    maxInstances: 5
}, async (request) => {
    await assertAdmin(request, "replyToQueries");

    const userEmail = String((request.data && request.data.userEmail) || "").trim();
    const docId = String((request.data && request.data.docId) || "").trim();
    if (!userEmail || !docId) {
        throw new HttpsError("invalid-argument", "userEmail and docId are required.");
    }
    if (userEmail.includes("/") || docId.includes("/")) {
        throw new HttpsError("invalid-argument", "Invalid identifier.");
    }

    return { url: buildReplyUrl(userEmail, docId, userEmail) };
});

// ─────────────────────────────────────────────
// FUNCTION 3: rephraseSupportText (callable, admin-only)
// Holds the Gemini keys server-side. Used to be hardcoded in the APK.
// ─────────────────────────────────────────────
exports.rephraseSupportText = onCall({
    region: "us-central1",
    secrets: [GEMINI_API_KEY, GEMINI_API_KEY_ADMIN],
    memory: "256MiB",
    maxInstances: 5
}, async (request) => {
    const callerEmail = await assertAdmin(request);

    // Which key to bill. The client names a scope; the keys themselves never
    // leave the server. Unknown scopes fall back to messaging rather than erroring.
    const scope = REPHRASE_SCOPES[String((request.data && request.data.scope) || "")]
        ? String(request.data.scope)
        : "messaging";

    // Limited per admin PER SCOPE, since the two scopes draw on separate quotas.
    await enforceRateLimit(`rephrase_${scope}_${callerEmail}`, 20, 60 * 1000);

    const text = String((request.data && request.data.text) || "").trim();
    const isTitle = Boolean(request.data && request.data.isTitle);

    if (!text) throw new HttpsError("invalid-argument", "text is required.");
    if (text.length > 4000) throw new HttpsError("invalid-argument", "text is too long.");

    const prompt = isTitle
        ? "Rewrite this title in a highly professional, corporate, and encouraging tone suitable for a fintech platform (CashDash). CashDash offers legitimate deals, announcements, and admin communications. CRITICAL: Do not invent or add features like 'cashback', 'rewards', or 'discounts' unless they are explicitly mentioned in the original text. Keep it short (max 5 words). Only return the rewritten text without quotes, do not include any other commentary.\n\nTitle:\n" + text
        : "Rewrite the following notification message in a highly professional, corporate, and encouraging tone suitable for a fintech platform (CashDash). CashDash offers legitimate deals, announcements, and admin communications. CRITICAL: Do not invent or add features like 'cashback', 'rewards', or 'discounts' unless they are explicitly mentioned in the original text. Only return the rewritten text, do not include any other commentary. Keep the {Validity} placeholder exactly as it is if it exists in the original text.\n\nMessage:\n" + text;

    try {
        // The original call hit `gemini-flash-latest` with no timeout and no retry.
        // Two problems: that alias is the busiest pool on the free tier, so it
        // returns 503 "experiencing high demand" regularly, and when it hung
        // instead, undici raised a Headers Timeout long after the caller's 70s
        // callable deadline — the app just showed DEADLINE_EXCEEDED.
        //
        // Rephrasing one short line of admin copy does not need a frontier model, and
        // this feature is used a handful of times a day, so rather than paying for
        // priority capacity we walk a chain of free models and take whichever
        // answers first. Lite models are listed first: they are the least contended
        // and comfortably good enough for "rewrite this professionally".
        //
        // Pinned IDs, not aliases — Google's own guidance, and it keeps a model
        // swap from silently changing behaviour. A 404 (retired ID) just falls
        // through to the next entry, so a stale name degrades instead of breaking.
        const MODEL_CHAIN = [
            "gemini-2.5-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-2.5-flash",
            "gemini-flash-latest",
        ];
        const ATTEMPT_TIMEOUT_MS = 12000;
        // Stay inside the 60s function timeout and the client's 70s deadline even
        // if every model in the chain stalls.
        const TOTAL_BUDGET_MS = 45000;
        const startedAt = Date.now();

        let resp = null;
        let lastStatus = null;
        let usedModel = null;

        for (const model of MODEL_CHAIN) {
            if (Date.now() - startedAt > TOTAL_BUDGET_MS) {
                console.error(`Gemini budget exhausted [scope=${scope}] before trying ${model}`);
                break;
            }

            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), ATTEMPT_TIMEOUT_MS);
            try {
                resp = await fetch(
                    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${encodeURIComponent(REPHRASE_SCOPES[scope]())}`,
                    {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }] }),
                        signal: controller.signal,
                    }
                );
            } catch (fetchErr) {
                // Abort or network failure — treat as this model being unusable.
                console.error(`Gemini fetch failed [scope=${scope}, model=${model}]:`, fetchErr.message);
                resp = null;
            } finally {
                clearTimeout(timer);
            }

            if (resp && resp.ok) {
                usedModel = model;
                break;
            }

            lastStatus = resp ? resp.status : "timeout";
            if (resp) {
                console.warn(`Gemini unavailable [scope=${scope}, model=${model}]:`, resp.status, await resp.text());
                // 400/403 mean the key or request is wrong, not the model — no
                // other model in the chain will do better, so stop here.
                if (resp.status === 400 || resp.status === 403) break;
            }
            resp = null;
        }

        if (!resp || !resp.ok) {
            // "unavailable" rather than "internal": every free model was busy, which
            // the admin can simply retry. The client maps this to a clear message.
            throw new HttpsError(
                "unavailable",
                `All rephrase models are busy right now (${scope}, last status ${lastStatus}). Please try again in a moment.`
            );
        }

        if (usedModel !== MODEL_CHAIN[0]) {
            console.log(`Rephrase served by fallback model ${usedModel} [scope=${scope}]`);
        }

        const data = await resp.json();
        const out = data &&
            data.candidates &&
            data.candidates[0] &&
            data.candidates[0].content &&
            data.candidates[0].content.parts &&
            data.candidates[0].content.parts[0]
            ? data.candidates[0].content.parts[0].text
            : null;

        return { text: out ? String(out).trim().replace(/^"|"$/g, "") : text };
    } catch (err) {
        if (err instanceof HttpsError) throw err;
        console.error("rephraseSupportText error:", err);
        throw new HttpsError("internal", "Rephrase failed.");
    }
});

// ─────────────────────────────────────────────
// FUNCTION 4: adminReply
// Access is granted by the HMAC-signed `exp`/`sig` pair on the URL, which binds
// the request to one notification and expires. Previously this endpoint had no
// access control at all.
// ─────────────────────────────────────────────
exports.adminReply = onRequest({ cors: false, region: "us-central1", secrets: [EMAIL_PASS_SECRET, REPLY_SIGNING_SECRET], memory: "256MiB", maxInstances: 5 }, async (req, res) => {
    const Busboy = require("busboy");
    const path = require("path");
    const os = require("os");
    const fs = require("fs");

    res.set("Content-Security-Policy",
        "default-src 'none'; img-src https: data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'; connect-src 'self'");
    res.set("X-Content-Type-Options", "nosniff");
    res.set("Referrer-Policy", "no-referrer");

    const denied = (why) => res.status(403).send(`
<html><body style='background:#0a0a1a;color:#f87171;text-align:center;padding-top:100px;font-family:sans-serif;'>
  <h2>Link no longer valid</h2>
  <p style='color:#88a'>${esc(why)}</p>
</body></html>`);

    if (req.method === "GET") {
        const { uid, id, email, exp, sig } = req.query;
        const targetUser = email || uid;

        if (!verifyReplyToken(targetUser, id, exp, sig)) {
            return denied("This reply link has expired or is not valid. Open the query from the CashDash admin panel to get a fresh link.");
        }

        let queryText = "Loading...";
        let subjectText = "Support Query";
        let hasPreviousReply = false;
        let legacyImages = [];

        try {
            const doc = await db.collection("users").doc(targetUser).collection("notifications").doc(String(id)).get();
            if (doc.exists) {
                const data = doc.data();
                legacyImages = [];
                if (data.imageUrl) {
                    legacyImages.push(data.imageUrl);
                }
                if (Array.isArray(data.imageUrls)) {
                    data.imageUrls.forEach(url => {
                        if (url !== data.imageUrl) {
                            legacyImages.push(url);
                        }
                    });
                }
                const userName = data.name || "User";
                queryText = data.query || "No query text found.";
                subjectText = data.subject || "Support Query";

                if (data.is_reply) {
                    if (!queryText.startsWith(userName + ":") && !queryText.startsWith("User (") && !queryText.startsWith("Team Cashdash:")) {
                        queryText = userName + ": " + queryText;
                    }
                } else {
                    if (!queryText.startsWith("Question:")) {
                        queryText = "Question: " + queryText;
                    }
                }

                const currentReply = data.reply || "";
                if (currentReply && currentReply !== "Waiting for reply...") {
                    queryText = queryText + "\n\nTeam Cashdash: " + currentReply;
                    if (currentReply !== "This query has been marked as resolved by the admin.") {
                        hasPreviousReply = true;
                    }
                }

                if (data.status === "resolved") {
                    if (!currentReply || currentReply === "This query has been marked as resolved by the admin.") {
                        queryText += "\n\n(Marked resolved without reply)";
                    } else {
                        queryText += "\n\n(Marked resolved with reply)";
                    }
                }
            }
        } catch (e) {
            queryText = "Could not load query.";
        }

        // Only render attachment images that live in our own Storage bucket, so a
        // crafted [Attachment: ...] marker cannot point the page at arbitrary hosts.
        const isTrustedAttachment = (url) =>
            /^https:\/\/(storage\.googleapis\.com|firebasestorage\.googleapis\.com)\//.test(url);

        // Collect which URLs are already referenced via [Attachment:] markers in the query text
        const referencedInText = new Set();
        const refScanRegex = /\[Attachment:\s*(https?:\/\/[^\s\]]+)\]/g;
        let refMatch;
        while ((refMatch = refScanRegex.exec(queryText)) !== null) {
            referencedInText.add(refMatch[1]);
        }

        // Legacy image URLs that are NOT already embedded as [Attachment:] markers
        const unreferencedLegacy = Array.isArray(legacyImages)
            ? legacyImages.filter(url => !referencedInText.has(url) && isTrustedAttachment(url))
            : [];

        const imgTag = (url) =>
            `<div style="margin: 8px 0;"><a href="${esc(url)}" target="_blank" rel="noopener noreferrer">` +
            `<img src="${esc(url)}" style="max-width: 100%; max-height: 250px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.15);">` +
            `</a></div>`;

        // Escape FIRST, then substitute the (validated) image markup. The previous
        // version escaped and then re-injected the raw URL into src="" and an
        // inline onclick, which let a crafted marker break out of the attribute.
        // `esc` has already turned any & into &amp;, so undo that before validating.
        let escapedQueryText = esc(queryText).replace(
            /\[Attachment:\s*(https?:[^\s\]]*)\]/g,
            (match, rawUrl) => {
                const url = rawUrl.replace(/&amp;/g, "&");
                return isTrustedAttachment(url) ? imgTag(url) : "(Attachment removed)";
            }
        );

        // Fix: if a speaker line ("Name: ") has no text before an image, insert "(Sent an Attachment)"
        escapedQueryText = escapedQueryText.replace(/([A-Za-z0-9 ]+:)[ \t]*\n(<div style)/g, '$1 (Sent an Attachment)\n$2');
        escapedQueryText = escapedQueryText.replace(/([A-Za-z0-9 ]+:)[ \t]*(<div style)/g, '$1 (Sent an Attachment)\n$2');

        // Inject unreferenced legacy images after the FIRST block (the original user message)
        if (unreferencedLegacy.length > 0) {
            const legacyImgHtml = unreferencedLegacy.map(imgTag).join('');
            const firstDoubleBreak = escapedQueryText.indexOf('\n\n');
            if (firstDoubleBreak !== -1) {
                escapedQueryText = escapedQueryText.slice(0, firstDoubleBreak) + '\n' + legacyImgHtml + escapedQueryText.slice(firstDoubleBreak);
            } else {
                escapedQueryText = escapedQueryText + '\n' + legacyImgHtml;
            }
        }

        return res.status(200).send(`
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>CashDash Support Reply</title>
  <style>
    body { font-family: -apple-system, sans-serif; background: #0a0a1a; color: #e0e0ff; margin: 0; padding: 20px; }
    .card { background: rgba(255,255,255,0.07); border-radius: 16px; padding: 24px; max-width: 600px; margin: 40px auto; }
    h2 { color: #7c83ff; margin-top: 0; display: flex; align-items: center; gap: 8px; }
    .query-box { background: rgba(0,0,0,0.3); border-radius: 8px; padding: 16px; margin-bottom: 20px; font-size: 14px; white-space: pre-wrap; border-left: 3px solid #7c83ff; }
    textarea { width: 100%; min-height: 120px; padding: 12px; border-radius: 8px; border: 1px solid #7c83ff; background: rgba(0,0,0,0.4); color: #e0e0ff; font-size: 15px; resize: vertical; box-sizing: border-box; }
    .btn-group { display: flex; flex-direction: column; gap: 10px; margin-top: 20px; }
    button { width: 100%; padding: 14px; border: none; border-radius: 8px; font-size: 16px; font-weight: bold; cursor: pointer; transition: opacity 0.2s; }
    .btn-reply { background: linear-gradient(135deg, #7c83ff, #4f46e5); color: white; }
    .btn-resolve { background: linear-gradient(135deg, #f59e0b, #d97706); color: white; }
    .btn-both { background: linear-gradient(135deg, #4ade80, #22c55e); color: white; }
    button:hover { opacity: 0.9; }
    button:disabled { opacity: 0.25; cursor: not-allowed; pointer-events: none; }
    #error-msg { color: #f87171; font-size: 13px; margin-top: 5px; display: none; text-align: center; font-weight: bold; }
    .attach-label { display: block; font-weight: bold; margin-bottom: 10px; font-size: 14px; color: #aab; }
    .img-slots { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
    .img-slot-wrapper { display: flex; flex-direction: column; align-items: center; }
    .img-slot { width: 80px; height: 80px; border-radius: 10px; border: 2px dashed rgba(124,131,255,0.5); display: flex; align-items: center; justify-content: center; cursor: pointer; position: relative; overflow: hidden; background: rgba(0,0,0,0.3); transition: border-color 0.2s; flex-shrink: 0; }
    .img-slot:hover { border-color: #7c83ff; }
    .img-slot img { width: 100%; height: 100%; object-fit: cover; border-radius: 8px; }
    .img-slot .plus { font-size: 28px; color: rgba(124,131,255,0.7); pointer-events: none; }
    .delete-txt { color: #f87171; font-size: 12px; cursor: pointer; margin-top: 6px; font-weight: bold; transition: opacity 0.2s; }
    .delete-txt:hover { opacity: 0.8; }
    #progress-section { display: none; margin-top: 16px; }
    #progress-label { font-size: 13px; color: #aab; margin-bottom: 6px; }
    #progress-bar-wrap { background: rgba(255,255,255,0.1); border-radius: 999px; height: 8px; overflow: hidden; }
    #progress-bar { height: 100%; width: 0%; background: linear-gradient(90deg, #7c83ff, #4ade80); border-radius: 999px; transition: width 0.15s ease; }
  </style>
</head>
<body>
  <div class="card">
    <h2>📨 CashDash Support Reply</h2>
    <div style="font-size:12px;color:#88a;margin-bottom:10px;">Subject: ${esc(subjectText)}</div>
    <div class="query-box">${escapedQueryText}</div>

    <div id="form-area">
      <input type="hidden" id="f-uid" value="${esc(uid)}">
      <input type="hidden" id="f-email" value="${esc(email || "")}">
      <input type="hidden" id="f-id" value="${esc(id)}">
      <input type="hidden" id="f-exp" value="${esc(exp)}">
      <input type="hidden" id="f-sig" value="${esc(sig)}">

      <button id="btn-resolve-only" class="btn-resolve" style="margin-bottom: 20px;" onclick="submitAction('resolve_only')" ${hasPreviousReply ? "disabled" : ""}>1. Mark as resolved without reply</button>

      ${hasPreviousReply ? `
      <div style="margin-bottom:20px;background:rgba(255,165,0,0.1);padding:12px;border-radius:8px;border:1px dashed orange;">
        <div style="font-weight:bold;margin-bottom:8px;color:orange;">⚠️ Consecutive Message Options:</div>
        <label style="display:block;margin-bottom:6px;cursor:pointer;">
          <input type="radio" name="consecutive_mode" value="additional" onchange="enableActionButtons()"> ➕ Additional message to above message
        </label>
        <label style="display:block;cursor:pointer;">
          <input type="radio" name="consecutive_mode" value="replace" onchange="enableActionButtons()"> 🔄 Replace above message
        </label>
      </div>` : ""}

      <textarea id="replyText" placeholder="Type your reply here..."></textarea>

      <div style="margin-top:16px;margin-bottom:4px;">
        <span class="attach-label">📎 Attach Images:</span>
        <div class="img-slots" id="imgSlots"></div>
        <input type="file" id="hiddenPicker" accept="image/*" style="display:none;">
      </div>

      <div id="error-msg">⚠️ Please type a reply or attach an image.</div>

      <div id="progress-section">
        <div id="progress-label">Uploading… 0%</div>
        <div id="progress-bar-wrap"><div id="progress-bar"></div></div>
      </div>

      <div class="btn-group" style="margin-top:20px;">
        <button id="btn-reply" class="btn-reply" onclick="submitAction('reply')" ${hasPreviousReply ? "disabled" : ""}>2. Send Reply ✈️</button>
        <button id="btn-reply-and-resolve" class="btn-both" onclick="submitAction('reply_and_resolve')" ${hasPreviousReply ? "disabled" : ""}>3. Reply &amp; Mark resolved ✅</button>
      </div>
    </div>
  </div>

  <script>
    const MAX_IMAGES = ${MAX_UPLOAD_FILES};
    const MAX_BYTES = ${MAX_UPLOAD_BYTES};
    const hasPrev = ${hasPreviousReply ? "true" : "false"};
    let selectedFiles = [];
    let currentAction = '';

    function renderSlots() {
      const container = document.getElementById('imgSlots');
      container.innerHTML = '';

      selectedFiles.forEach((file, i) => {
        const wrapper = document.createElement('div');
        wrapper.className = 'img-slot-wrapper';

        const slot = document.createElement('div');
        slot.className = 'img-slot';
        const img = document.createElement('img');
        img.src = URL.createObjectURL(file);
        slot.appendChild(img);

        const delTxt = document.createElement('div');
        delTxt.className = 'delete-txt';
        delTxt.textContent = 'Delete';
        delTxt.onclick = () => { selectedFiles.splice(i, 1); renderSlots(); };

        wrapper.appendChild(slot);
        wrapper.appendChild(delTxt);
        container.appendChild(wrapper);
      });

      if (selectedFiles.length < MAX_IMAGES) {
        const addWrapper = document.createElement('div');
        addWrapper.className = 'img-slot-wrapper';
        const addSlot = document.createElement('div');
        addSlot.className = 'img-slot';
        addSlot.innerHTML = '<span class="plus">+</span>';
        addSlot.onclick = () => document.getElementById('hiddenPicker').click();
        addWrapper.appendChild(addSlot);
        container.appendChild(addWrapper);
      }
    }

    document.getElementById('hiddenPicker').addEventListener('change', function() {
      const f = this.files[0];
      this.value = '';
      if (!f || selectedFiles.length >= MAX_IMAGES) return;
      if (!/^image\\//.test(f.type)) { alert('Only image files can be attached.'); return; }
      if (f.size > MAX_BYTES) { alert('That image is larger than 8 MB.'); return; }
      selectedFiles.push(f);
      renderSlots();
    });

    renderSlots();

    function enableActionButtons() {
      ['btn-resolve-only','btn-reply','btn-reply-and-resolve'].forEach(id => {
        document.getElementById(id).removeAttribute('disabled');
      });
    }

    function setButtons(disabled) {
      ['btn-resolve-only','btn-reply','btn-reply-and-resolve'].forEach(id => {
        const btn = document.getElementById(id);
        if (disabled) btn.setAttribute('disabled',''); else btn.removeAttribute('disabled');
      });
    }

    function submitAction(action) {
      currentAction = action;
      const text = document.getElementById('replyText').value.trim();
      const error = document.getElementById('error-msg');

      if (hasPrev) {
        const radios = document.getElementsByName('consecutive_mode');
        if (!Array.from(radios).some(r => r.checked)) {
          alert('Please select whether this is an additional or replacement message.');
          return;
        }
      }

      if ((action === 'reply' || action === 'reply_and_resolve') && text.length === 0 && selectedFiles.length === 0) {
        error.style.display = 'block'; return;
      }
      error.style.display = 'none';

      const consecutiveMode = hasPrev
        ? (Array.from(document.getElementsByName('consecutive_mode')).find(r => r.checked)?.value || 'additional')
        : '';

      const fd = new FormData();
      fd.append('uid', document.getElementById('f-uid').value);
      fd.append('email', document.getElementById('f-email').value);
      fd.append('id', document.getElementById('f-id').value);
      fd.append('exp', document.getElementById('f-exp').value);
      fd.append('sig', document.getElementById('f-sig').value);
      fd.append('reply', text);
      fd.append('action', action);
      fd.append('consecutive_mode', consecutiveMode);
      selectedFiles.forEach(file => fd.append('attachments', file, file.name));

      setButtons(true);
      const progressSection = document.getElementById('progress-section');
      const progressBar = document.getElementById('progress-bar');
      const progressLabel = document.getElementById('progress-label');
      const progressBarWrap = document.getElementById('progress-bar-wrap');

      const hasImages = selectedFiles.length > 0;
      progressSection.style.display = 'block';
      if (hasImages) {
        progressBarWrap.style.display = 'block';
        progressLabel.textContent = 'Uploading images… 0%';
        progressBar.style.width = '0%';
      } else {
        progressBarWrap.style.display = 'none';
        progressLabel.textContent = 'Sending reply…';
      }

      const xhr = new XMLHttpRequest();
      xhr.open('POST', window.location.href, true);
      xhr.upload.onprogress = function(e) {
        if (hasImages && e.lengthComputable) {
          const pct = Math.round((e.loaded / e.total) * 100);
          progressBar.style.width = pct + '%';
          progressLabel.textContent = 'Uploading images… ' + pct + '%';
        }
      };
      xhr.onload = function() {
        if (hasImages) {
          progressBar.style.width = '100%';
        }
        progressLabel.textContent = 'Done! ✅';
        setTimeout(() => { document.open(); document.write(xhr.responseText); document.close(); }, 600);
      };
      xhr.onerror = function() {
        progressSection.style.display = 'none';
        setButtons(false);
        alert('Upload failed. Please try again.');
      };
      xhr.send(fd);
    }
  </script>
</body>
</html>`);
    }

    if (req.method === "POST") {
        const parseMultipart = () => {
            return new Promise((resolve, reject) => {
                const busboy = Busboy({
                    headers: req.headers,
                    limits: { fileSize: MAX_UPLOAD_BYTES, files: MAX_UPLOAD_FILES, fields: 20 },
                });
                const fields = {};
                const files = [];
                let rejected = null;

                busboy.on("field", (fieldName, val) => {
                    fields[fieldName] = val;
                });

                busboy.on("file", (fieldName, file, info) => {
                    const { filename, mimeType } = info;
                    if (!filename || !ALLOWED_UPLOAD_TYPES.includes(String(mimeType).toLowerCase())) {
                        file.resume();
                        return;
                    }
                    // Never trust the client filename: strip any path component and
                    // any character that could escape the destination directory.
                    const safeName = path.basename(String(filename)).replace(/[^A-Za-z0-9._-]/g, "_").slice(-64) || "upload";
                    const filepath = path.join(os.tmpdir(), `${Date.now()}_${safeName}`);
                    const writeStream = fs.createWriteStream(filepath);

                    let overLimit = false;
                    file.on("limit", () => {
                        overLimit = true;
                        rejected = "One of the images is larger than 8 MB.";
                        writeStream.destroy();
                    });

                    file.pipe(writeStream);

                    // Settle on "close", which fires on both success and destroy,
                    // so an over-limit file can never leave the promise pending.
                    files.push(new Promise((res2) => {
                        let settled = false;
                        const done = (value) => {
                            if (settled) return;
                            settled = true;
                            res2(value);
                        };
                        writeStream.on("error", () => done(null));
                        writeStream.on("close", () => {
                            if (overLimit) {
                                try { fs.unlinkSync(filepath); } catch (e) { /* already gone */ }
                                return done(null);
                            }
                            done({ fieldName, filepath, filename: safeName, mimeType });
                        });
                    }));
                });

                busboy.on("finish", async () => {
                    try {
                        const resolvedFiles = (await Promise.all(files)).filter(Boolean);
                        if (rejected) return reject(new Error(rejected));
                        resolve({ fields, files: resolvedFiles });
                    } catch (e) {
                        reject(e);
                    }
                });

                busboy.on("error", reject);

                if (req.rawBody) {
                    busboy.end(req.rawBody);
                } else {
                    req.pipe(busboy);
                }
            });
        };

        try {
            const parsed = await parseMultipart();
            let { uid, id, email, reply, action, consecutive_mode, exp, sig } = parsed.fields;
            if (!uid || !id) return res.status(400).send("Missing data.");

            const targetUser = email || uid;

            if (!verifyReplyToken(targetUser, id, exp, sig)) {
                parsed.files.forEach(f => { try { fs.unlinkSync(f.filepath); } catch (e) { /* ignore */ } });
                return denied("This reply link has expired or is not valid.");
            }

            const actionStr = String(action || "reply");

            const bucket = admin.storage().bucket();
            const uploadedUrls = [];
            for (const file of parsed.files) {
                const dest = `support_attachments/${Date.now()}_${file.filename}`;
                // These are support-thread images — screenshots of balances,
                // statements, whatever the user was asked to send. They used to be
                // makePublic()'d, which grants allUsers read on the object and
                // bypasses Storage rules entirely: world-readable, forever.
                // A download token keeps the URL unguessable, leaves the object
                // private, and can be revoked by clearing the metadata.
                const downloadToken = crypto.randomUUID();
                await bucket.upload(file.filepath, {
                    destination: dest,
                    metadata: {
                        contentType: file.mimeType,
                        metadata: { firebaseStorageDownloadTokens: downloadToken },
                    }
                });
                const publicUrl =
                    `https://firebasestorage.googleapis.com/v0/b/${bucket.name}/o/` +
                    `${encodeURIComponent(dest)}?alt=media&token=${downloadToken}`;
                uploadedUrls.push(publicUrl);
                fs.unlinkSync(file.filepath);
            }

            let typedReply = reply ? reply.trim() : "";
            for (const url of uploadedUrls) {
                typedReply += `\n[Attachment: ${url}]`;
            }

            let finalReply = typedReply;
            let finalStatus = "responded";
            let isResolvedAction = (actionStr === "resolve_only" || actionStr === "reply_and_resolve");

            const doc = await db.collection("users").doc(targetUser).collection("notifications").doc(String(id)).get();
            const existingReply = doc.exists ? (doc.data().reply || "") : "";
            const hasPrev = existingReply && existingReply !== "Waiting for reply..." && existingReply !== "This query has been marked as resolved by the admin.";

            if (actionStr === "resolve_only") {
                finalStatus = "resolved";
                finalReply = "This query has been marked as resolved by the admin.";
            } else if (actionStr === "reply_and_resolve") {
                finalStatus = "resolved";
            }

            if (actionStr !== "resolve_only" && hasPrev && consecutive_mode === "additional") {
                finalReply = `${existingReply}\n\n${finalReply}`;
            }

            const timestamp = Date.now();
            // NOTE: imageUrls field is intentionally NOT updated with admin reply images.
            // Admin images are embedded as [Attachment:] markers in the reply text itself.
            // imageUrls only holds the user's original submission attachments.

            const updateData = {
                reply: finalReply,
                status: finalStatus,
                read: false,
                replyTimestamp: timestamp,
                timestamp: timestamp
            };

            await db.collection("users").doc(targetUser).collection("notifications").doc(String(id)).set(updateData, { merge: true });

            try {
                const userDoc = await db.collection("users").doc(targetUser).get();
                const fcmToken = userDoc.data()?.fcmToken;

                if (fcmToken) {
                    // Data-only message: MyFirebaseMessagingService builds the notification
                    // and its own PendingIntent, so NotificationActivity does not need to
                    // be an exported component. Matches the onUserPush pattern.
                    await admin.messaging().send({
                        token: fcmToken,
                        data: {
                            title: "Cashdash Support",
                            body: isResolvedAction ? "Your query has been resolved !" : "Your query has got a response ! Tap to view.",
                            uid: String(uid),
                            id: String(id),
                        },
                        android: {
                            priority: "high",
                        },
                    });
                }
            } catch (fcmError) {
                console.error("FCM Send Error:", fcmError);
            }

            return res.status(200).send(`
<html>
<body style='background:#0a0a1a;color:#4ade80;text-align:center;padding-top:100px;font-family:sans-serif;'>
  <h2>✅ Success!</h2>
  <p style='color:#88a'>Action: ${esc(actionStr.replace(/_/g, " "))}</p>
  <p style='color:#88a'>The user has been notified.</p>
</body>
</html>`);
        } catch (error) {
            console.error("adminReply POST error:", error);
            return res.status(500).send("Error: " + esc(error.message));
        }
    }
    return res.status(405).send("Method Not Allowed");
});

// ─────────────────────────────────────────────
// FUNCTION 5: onGlobalPush
// ─────────────────────────────────────────────
exports.onGlobalPush = onDocumentWritten({
    document: "global_pushes/{pushId}",
    region: "us-central1",
    memory: "256MiB",
    maxInstances: 5
}, async (event) => {
    const newData = event.data.after.data();
    if (!newData) return; // Document was deleted, ignore

    const title = newData.title || "Announcement";
    const message = newData.message || "";
    const imageUrl = newData.imageUrl || "";
    const triggerUrl = newData.triggerUrl || "";
    const triggerText = newData.triggerText || "";

    const promoId = newData.promo_id || "";

    if (!message) return;

    const adminOnly = newData.adminOnly === true;
    const targetTopic = adminOnly ? "admins" : "all_users";

    try {
        await admin.messaging().send({
            topic: targetTopic,
            data: {
                title: title,
                body: message,
                imageUrl: imageUrl,
                triggerUrl: triggerUrl,
                triggerText: triggerText,
                isPromotion: "true",
                promo_id: promoId
            },
            android: {
                priority: "high",
                ttl: 172800000 // 48 hours
            }
        });
        console.log(`Successfully sent push notification to ${targetTopic}: ${title}`);
    } catch (error) {
        console.error(`Error sending push notification to ${targetTopic}:`, error);
    }
});

// ─────────────────────────────────────────────
// FUNCTION 6: onUserPush
// ─────────────────────────────────────────────
exports.onUserPush = onDocumentWritten({
    document: "user_pushes/{pushId}",
    region: "us-central1",
    memory: "256MiB",
    maxInstances: 10
}, async (event) => {
    const newData = event.data.after.data();
    if (!newData) return; // Document was deleted, ignore

    const email = newData.email;
    const title = newData.title || "Support Alert";
    const message = newData.message || "";
    const imageUrl = newData.imageUrl || "";
    const triggerUrl = newData.triggerUrl || "";
    const triggerText = newData.triggerText || "";

    if (!email || !message) return;

    try {
        const userDoc = await db.collection("users").doc(email).get();
        const fcmToken = userDoc.data()?.fcmToken;

        if (fcmToken) {
            await admin.messaging().send({
                token: fcmToken,
                data: {
                    title: title,
                    body: message,
                    imageUrl: imageUrl,
                    triggerUrl: triggerUrl,
                    triggerText: triggerText,
                    userSpecificPush: "true",
                    isPromotion: "true",
                    promo_id: newData.promo_id || ""
                },
                android: {
                    priority: "high",
                    ttl: 172800000 // 48 hours
                }
            });
            console.log(`Successfully sent user-specific push to ${email}: ${title}`);
        } else {
            console.log(`No FCM token found for user ${email}`);
        }

        // Save to the user's in-app notification inbox so they can actually open it in NotificationActivity!
        const docData = {
            name: userDoc.data()?.name || "User",
            email: email,
            time: new Date(newData.timestamp || Date.now()).toLocaleString(),
            subject: title,
            originalSubject: title,
            query: message,
            timestamp: newData.timestamp || Date.now(),
            read: false,
            status: "resolved",
            reply: "Notification Received",
            isPush: true
        };
        await db.collection("users").doc(email).collection("notifications").doc(String(newData.timestamp || Date.now())).set(docData);

    } catch (error) {
        console.error(`Error sending user-specific push to ${email}:`, error);
    }
});

// ─────────────────────────────────────────────
// FUNCTION 7: onAuthUserDeleted
// ─────────────────────────────────────────────
const functionsv1 = require("firebase-functions/v1");

exports.onAuthUserDeleted = functionsv1.auth.user().onDelete(async (user) => {
    const email = user.email;
    const uid = user.uid;

    if (!email) {
        console.log(`User deleted with UID ${uid} but no email found. Skipping Firestore cleanup.`);
        return;
    }

    console.log(`onAuthUserDeleted triggered for user: ${email} (UID: ${uid})`);

    try {
        // 1. Write status: "admin_deleted" to deleted_accounts to trigger disruptive logout on the device
        await db.collection("deleted_accounts").doc(email).set({
            uid: uid,
            email: email,
            deleted_at: Date.now(),
            status: "admin_deleted"
        });
        console.log(`Successfully logged admin_deleted status in deleted_accounts for ${email}`);

        const userRef = db.collection("users").doc(email);

        // Retrieve and send FCM force_logout notification to active user device before wiping data
        try {
            const userDoc = await userRef.get();
            const fcmToken = userDoc.data()?.fcmToken;
            if (fcmToken) {
                await admin.messaging().send({
                    token: fcmToken,
                    data: {
                        action: "force_logout"
                    },
                    android: {
                        priority: "high"
                    }
                });
                console.log(`Successfully sent force_logout FCM message to ${email}`);
            } else {
                console.log(`No FCM token found for ${email}`);
            }
        } catch (fcmError) {
            console.error(`Error sending force_logout FCM message to ${email}:`, fcmError);
        }

        // 2. Wipe everything under config.
        // This used to delete a hardcoded list of document names, which silently
        // missed scanner_metadata, finminder and upi_allocations — financial data
        // left orphaned in Firestore after the user asked for deletion. Enumerate
        // instead, so documents added later are covered without a code change.
        const batch = db.batch();
        const configSnapshot = await userRef.collection("config").get();
        configSnapshot.forEach((doc) => {
            batch.delete(doc.ref);
        });

        // 3. Wipe sub-collections under notifications
        const notificationsSnapshot = await userRef.collection("notifications").get();
        notificationsSnapshot.forEach((doc) => {
            batch.delete(doc.ref);
        });

        // 4. Finally delete the root document itself
        batch.delete(userRef);

        // Commit the batch
        await batch.commit();
        console.log(`Successfully cleaned up Firestore database for deleted user: ${email}`);
    } catch (error) {
        console.error(`Error cleaning up Firestore data for deleted user ${email}:`, error);
    }
});
