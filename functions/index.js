const { onRequest } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const nodemailer = require("nodemailer");

admin.initializeApp();
const db = admin.firestore();

const { defineSecret } = require("firebase-functions/params");

// ─────────────────────────────────────────────
// CONFIGURATION & SECRETS
// ─────────────────────────────────────────────
const ADMIN_EMAIL = "cashdash.query@gmail.com";
const GMAIL_USER = "cashdash.query@gmail.com";
const gmailPasswordSecret = defineSecret("GMAIL_PASS"); // Value: kwebifxmvfhqkdwf (from chat)

// ─────────────────────────────────────────────
// NODEMAILER HELPER
// ─────────────────────────────────────────────
function createTransporter() {
    return nodemailer.createTransport({
        service: "gmail",
        auth: {
            user: GMAIL_USER,
            pass: gmailPasswordSecret.value(),
        },
    });
}

const { onDocumentWritten } = require("firebase-functions/v2/firestore");

exports.onSupportQuery = onDocumentWritten({
    document: "users/{userEmail}/notifications/{notificationId}",
    secrets: [gmailPasswordSecret]
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

        const { name, email, time, subject, query } = newData;
        const targetUser = email || event.params.userEmail;
        const id = event.params.notificationId;

        // For emails: swap the user's real name label to "User:" for clean admin display
        const userName = name || "User";
        let emailQuery = (query || "").replace(new RegExp(userName + ":", "g"), `User (${userName}):`);
        // Ensure the message has a clear label in emails
        if (!emailQuery.startsWith(`User (${userName}):`) && !emailQuery.startsWith("Team Cashdash:")) {
            emailQuery = `User (${userName}): ` + emailQuery;
        }

        const dateStr = newData.time || new Date(newData.timestamp || Date.now()).toLocaleString("en-US", {
            month: "short", day: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", hour12: false
        });

        const replyUrl = `https://adminreply-khhfw7mtba-uc.a.run.app?uid=${targetUser}&id=${id}&email=${email || ""}`;

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
${emailQuery || "No query text found."}
-----------------------------------

REPLY TO THIS QUERY:
Click the link below to send your reply directly to the user's app:

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
${emailQuery || "No query text found."}
-----------------------------------

REPLY TO THIS QUERY:
Click the link below to send your reply directly to the user's app:

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
// FUNCTION 1: cashdashWebhook (v2 / Cloud Run)
// ─────────────────────────────────────────────
exports.cashdashWebhook = onRequest({ cors: true, region: "us-central1", secrets: [gmailPasswordSecret] }, async (req, res) => {
    if (req.method !== "POST") return res.status(405).send("Method Not Allowed");

    const {
        uid, id, name, email, subject,
        query, rawQuery, originalQuery, teamReply, userFollowup,
        time, timestamp, is_reply
    } = req.body;

    const targetUser = email || uid;
    try {
        if (!is_reply) {
            await db.collection("users").doc(targetUser).collection("notifications").doc(String(id)).set({
                name: name || "",
                email: email || "",
                time: time || "",
                subject: subject || "General Help",
                originalSubject: subject || "General Help",
                query: rawQuery || query || "",
                timestamp: timestamp || Date.now(),
                read: false,
                status: "pending",
                reply: "Waiting for reply...",
            });
        }

        const replyUrl = `https://adminreply-khhfw7mtba-uc.a.run.app?uid=${uid}&id=${id}&email=${email || ""}`;

        let emailBody = "";
        if (is_reply && userFollowup) {
            emailBody = `
NEW FOLLOW-UP MESSAGE

Name: ${name || "User"}
Email: ${email || "No Email"}
Time: ${time || "Just now"}

-----------------------------------
CONVERSATION THREAD:
-----------------------------------
User (${name || "User"}): ${originalQuery || "No original message found."}

Team Cashdash: ${teamReply || "No team reply found."}

User (${name || "User"}): ${userFollowup}
-----------------------------------

REPLY TO THIS QUERY:
Click the link below to send your reply directly to the user's app:

${replyUrl}
`.trim();
        } else {
            emailBody = `
NEW SUPPORT QUERY RECEIVED

Name: ${name || "User"}
Email: ${email || "No Email"}
Time: ${time || "Just now"}
Subject: ${subject || "General Help"}

-----------------------------------
MESSAGE:
-----------------------------------
User (${name || "User"}): ${rawQuery || query || "No query text found."}
-----------------------------------

REPLY TO THIS QUERY:
Click the link below to send your reply directly to the user's app:

${replyUrl}
`.trim();
        }

        await createTransporter().sendMail({
            from: `"CashDash Support" <${GMAIL_USER}>`,
            to: ADMIN_EMAIL,
            subject: is_reply ? `Follow-up: ${subject}` : `New Query: ${subject}`,
            text: emailBody,
        });

        return res.status(200).json({ success: true });
    } catch (error) {
        console.error("cashdashWebhook error:", error);
        return res.status(500).json({ error: error.message });
    }
});

// ─────────────────────────────────────────────
exports.adminReply = onRequest({ cors: true, region: "us-central1", secrets: [gmailPasswordSecret] }, async (req, res) => {
    if (req.method === "GET") {
        const { uid, id, email } = req.query;
        let queryText = "Loading...";
        let subjectText = "Support Query";
        const targetUser = email || uid;
        try {
            const doc = await db.collection("users").doc(targetUser).collection("notifications").doc(String(id)).get();
            if (doc.exists) {
                const data = doc.data();
                const userName = data.name || "User";
                queryText = data.query || "No query text found.";
                subjectText = data.subject || "Support Query";

                // Prefix labeling: use "Question:" for initial query, and "Username:" for follow-up thread starts
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
                }
            }
        } catch (e) {
            queryText = "Could not load query.";
        }

        return res.status(200).send(`
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>CashDash Admin Reply</title>
  <style>
    body { font-family: -apple-system, sans-serif; background: #0a0a1a; color: #e0e0ff; margin: 0; padding: 20px; }
    .card { background: rgba(255,255,255,0.07); border-radius: 16px; padding: 24px; max-width: 600px; margin: 40px auto; }
    h2 { color: #7c83ff; margin-top: 0; }
    .query-box { background: rgba(0,0,0,0.3); border-radius: 8px; padding: 16px; margin-bottom: 20px; font-size: 14px; white-space: pre-wrap; border-left: 3px solid #7c83ff; }
    textarea { width: 100%; min-height: 120px; padding: 12px; border-radius: 8px; border: 1px solid #7c83ff; background: rgba(0,0,0,0.4); color: #e0e0ff; font-size: 15px; resize: vertical; box-sizing: border-box; }
    .btn-group { display: flex; flex-direction: column; gap: 10px; margin-top: 20px; }
    button { width: 100%; padding: 14px; border: none; border-radius: 8px; font-size: 16px; font-weight: bold; cursor: pointer; transition: opacity 0.2s; }
    .btn-reply { background: linear-gradient(135deg, #7c83ff, #4f46e5); color: white; }
    .btn-resolve { background: linear-gradient(135deg, #f59e0b, #d97706); color: white; } /* 🔥 This is the line that sets the color for Mark Resolved button! */
    .btn-both { background: linear-gradient(135deg, #4ade80, #22c55e); color: white; }
    button:hover { opacity: 0.9; }
    #error-msg { color: #f87171; font-size: 13px; margin-top: 5px; display: none; text-align: center; font-weight: bold; }
  </style>
</head>
<body>
  <div class="card">
    <h2>📨 CashDash Support Reply</h2>
    <div style="font-size:12px;color:#88a;margin-bottom:10px;">Subject: ${subjectText}</div>
    <div class="query-box">${queryText.replace(/</g, "&lt;").replace(/>/g, "&gt;")}</div>
    <form id="replyForm" method="POST" onsubmit="return validateForm(event)">
      <input type="hidden" name="uid" value="${uid}">
      <input type="hidden" name="email" value="${email || ""}">
      <input type="hidden" name="id" value="${id}">
      
      <button type="submit" class="btn-resolve" style="margin-bottom: 24px;" onclick="setAction('resolve_only')">1. Mark as resolved without reply</button>

      <textarea id="replyText" name="reply" placeholder="Type your reply here..."></textarea>
      <div id="error-msg">⚠️ Field is empty! Please type a response.</div>
      
      <div class="btn-group">
        <button type="submit" class="btn-reply" onclick="setAction('reply')">2. Send Reply ✈️</button>
        <button type="submit" class="btn-both" onclick="setAction('reply_and_resolve')">3. Reply & Mark resolved ✅</button>
      </div>
      <input type="hidden" name="action" id="actionInput">
    </form>
  </div>

  <script>
    let currentAction = '';
    function setAction(action) {
      currentAction = action;
      document.getElementById('actionInput').value = action;
    }

    function validateForm(e) {
      const text = document.getElementById('replyText').value.trim();
      const error = document.getElementById('error-msg');
      
      // If no action was set (e.g. enter key), default to reply
      if (!currentAction) {
        currentAction = 'reply';
        document.getElementById('actionInput').value = 'reply';
      }

      // Buttons 2 and 3 require text
      if ((currentAction === 'reply' || currentAction === 'reply_and_resolve') && text.length === 0) {
        error.style.display = 'block';
        return false;
      }
      
      error.style.display = 'none';
      return true;
    }
  </script>
</body>
</html>`);
    }

    if (req.method === "POST") {
        let { uid, id, email, reply, action } = req.body;
        if (!uid || !id) return res.status(400).send("Missing data.");

        const targetUser = email || uid;

        // Handle action if it's an array or missing
        if (Array.isArray(action)) action = action[0];
        const actionStr = String(action || "reply");

        let finalReply = reply ? reply.trim() : "";
        let finalStatus = "responded";
        let isResolvedAction = (actionStr === "resolve_only" || actionStr === "reply_and_resolve");

        if (actionStr === "resolve_only") {
            finalStatus = "resolved";
            finalReply = finalReply || "This query has been marked as resolved by the admin.";
        } else if (actionStr === "reply_and_resolve") {
            finalStatus = "resolved";
        }

        try {
            const timestamp = Date.now();
            await db.collection("users").doc(targetUser).collection("notifications").doc(String(id)).update({
                reply: finalReply,
                status: finalStatus,
                read: false,
                replyTimestamp: timestamp,
                timestamp: timestamp, // 🔥 Update main timestamp to reflect admin activity
            });

            // 🚀 Send Push Notification to User
            try {
                const userDoc = await db.collection("users").doc(targetUser).get();
                const fcmToken = userDoc.data()?.fcmToken;

                if (fcmToken) {
                    await admin.messaging().send({
                        token: fcmToken,
                        notification: {
                            title: "Cashdash Support",
                            body: isResolvedAction ? "Your query has been resolved !" : "Your query has got a response ! Tap to view.",
                        },
                        android: {
                            priority: "high",
                            notification: {
                                channelId: "cashdash_v6_urgent",
                                icon: "ic_bell",
                                color: "#4ADE80",
                                clickAction: "NotificationActivity",
                            },
                        },
                        data: {
                            uid: String(uid),
                            id: String(id),
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
  <p style='color:#88a'>Action: ${actionStr.replace(/_/g, " ")}</p>
  <p style='color:#88a'>The user has been notified.</p>
</body>
</html>`);
        } catch (error) {
            return res.status(500).send("Error: " + error.message);
        }
    }
    return res.status(405).send("Method Not Allowed");
});
