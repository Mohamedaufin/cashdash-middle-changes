const { onRequest } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const nodemailer = require("nodemailer");

admin.initializeApp();
const db = admin.firestore();

const { defineSecret } = require("firebase-functions/params");

// ─────────────────────────────────────────────
// CONFIGURATION & SECRETS
// ─────────────────────────────────────────────
const ADMIN_EMAIL = "support@cashdash.co.in";
const GMAIL_USER = "support@cashdash.co.in";
const EMAIL_PASS_SECRET = defineSecret("GMAIL_PASS"); // We'll keep the secret name GMAIL_PASS for now to avoid re-setting it immediately, but it now holds your Zoho App Password.

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

exports.onSupportQuery = onDocumentWritten({
    document: "users/{userEmail}/notifications/{notificationId}",
    secrets: [EMAIL_PASS_SECRET]
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

        // Remove attachment links/tags from the email body
        let cleanEmailQuery = emailQuery.replace(/\[Attachment:\s*(https?:\/\/[^\s\]]+)\]/g, "").trim();
        
        // If the query is just the prefix, it means only an attachment was sent
        if (cleanEmailQuery.trim() === `User (${userName}):`) {
            cleanEmailQuery = `User (${userName}): (Sent an Attachment)`;
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
${cleanEmailQuery || "No query text found."}
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
${cleanEmailQuery || "No query text found."}
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
exports.cashdashWebhook = onRequest({ cors: true, region: "us-central1", secrets: [EMAIL_PASS_SECRET] }, async (req, res) => {
    if (req.method !== "POST") return res.status(405).send("Method Not Allowed");

    const {
        uid, id, name, email, subject,
        query, rawQuery, originalQuery, teamReply, userFollowup,
        time, timestamp, is_reply
    } = req.body;

    const targetUser = email || uid;
    try {
        if (!is_reply) {
            const docData = {
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
            };
            // Save attachment URLs if provided in the body
            const bodyImageUrl = req.body.imageUrl;
            const bodyImageUrls = req.body.imageUrls;
            if (bodyImageUrl) docData.imageUrl = bodyImageUrl;
            if (Array.isArray(bodyImageUrls) && bodyImageUrls.length > 0) docData.imageUrls = bodyImageUrls;
            await db.collection("users").doc(targetUser).collection("notifications").doc(String(id)).set(docData);
        }

        const replyUrl = `https://adminreply-khhfw7mtba-uc.a.run.app?uid=${uid}&id=${id}&email=${email || ""}`;

        // Helper: strip [Attachment: url] markers and replace empty lines with "(Sent an Attachment)"
        function cleanTextForEmail(text) {
            if (!text) return "";
            // Per-line fix: if a line is just "Name: " with nothing after, add (Sent an Attachment)
            const lines = text.split('\n');
            const fixedLines = lines.map(line => {
                const stripped = line.replace(/\[Attachment:\s*(https?:\/\/[^\s\]]+)\]/g, '').trim();
                const hasAttachment = /\[Attachment:/.test(line);
                if (hasAttachment && stripped === '') return '(Sent an Attachment)';
                if (hasAttachment && /^[A-Za-z0-9 ]+:\s*$/.test(stripped)) return stripped + ' (Sent an Attachment)';
                return stripped;
            });
            return fixedLines.filter((l, i, arr) => !(l === '' && arr[i-1] === '')).join('\n').trim();
        }

        let cleanOriginalQuery = cleanTextForEmail(originalQuery);
        if (!cleanOriginalQuery && (originalQuery || "").includes("[Attachment:")) cleanOriginalQuery = "(Sent an Attachment)";

        let cleanTeamReply = cleanTextForEmail(teamReply);
        if (!cleanTeamReply && (teamReply || "").includes("[Attachment:")) cleanTeamReply = "(Sent an Attachment)";

        let cleanUserFollowup = cleanTextForEmail(userFollowup);
        if (!cleanUserFollowup && (userFollowup || "").includes("[Attachment:")) cleanUserFollowup = "(Sent an Attachment)";

        let cleanQuery = cleanTextForEmail(rawQuery || query);
        if (!cleanQuery && (rawQuery || query || "").includes("[Attachment:")) cleanQuery = "(Sent an Attachment)";

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
User (${name || "User"}): ${cleanOriginalQuery || "No original message found."}

Team Cashdash: ${cleanTeamReply || "No team reply found."}

User (${name || "User"}): ${cleanUserFollowup}
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
User (${name || "User"}): ${cleanQuery || "No query text found."}
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
exports.adminReply = onRequest({ cors: true, region: "us-central1", secrets: [EMAIL_PASS_SECRET] }, async (req, res) => {
    const Busboy = require("busboy");
    const path = require("path");
    const os = require("os");
    const fs = require("fs");

    if (req.method === "GET") {
        const { uid, id, email } = req.query;
        let queryText = "Loading...";
        let subjectText = "Support Query";
        const targetUser = email || uid;
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

        // Collect which URLs are already referenced via [Attachment:] markers in the query text
        const referencedInText = new Set();
        const refScanRegex = /\[Attachment:\s*(https?:\/\/[^\s\]]+)\]/g;
        let refMatch;
        while ((refMatch = refScanRegex.exec(queryText)) !== null) {
            referencedInText.add(refMatch[1]);
        }

        // Legacy image URLs that are NOT already embedded as [Attachment:] markers
        // These come from the original imageUrl/imageUrls fields (pre-[Attachment:] era)
        const unreferencedLegacy = Array.isArray(legacyImages)
            ? legacyImages.filter(url => !referencedInText.has(url))
            : [];

        let escapedQueryText = queryText.replace(/</g, "&lt;").replace(/>/g, "&gt;");
        const attachmentRegex = /\[Attachment:\s*(https?:\/\/[^\s\]]+)\]/g;
        let hasNewMarkers = false;
        escapedQueryText = escapedQueryText.replace(attachmentRegex, (match, url) => {
            hasNewMarkers = true;
            return `<div style="margin: 8px 0;"><img src="${url}" style="max-width: 100%; max-height: 250px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.15); cursor: pointer;" onclick="window.open('${url}', '_blank')"></div>`;
        });

        // Fix: if a speaker line ("Name: ") has no text before an image, insert "(Sent an Attachment)"
        // Case 1: "Name: \n<div" — newline between colon and image
        escapedQueryText = escapedQueryText.replace(/([A-Za-z0-9 ]+:)[ \t]*\n(<div style)/g, '$1 (Sent an Attachment)\n$2');
        // Case 2: "Name: <div" — image immediately after colon with no text
        escapedQueryText = escapedQueryText.replace(/([A-Za-z0-9 ]+:)[ \t]*(<div style)/g, '$1 (Sent an Attachment)\n$2');

        // Inject unreferenced legacy images after the FIRST block (the original user message)
        // so they always appear attached to the first message, not dumped at the end
        if (unreferencedLegacy.length > 0) {
            const legacyImgHtml = unreferencedLegacy.map(url =>
                `<div style="margin: 8px 0;"><img src="${url}" style="max-width: 100%; max-height: 250px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.15); cursor: pointer;" onclick="window.open('${url}', '_blank')"></div>`
            ).join('');
            // Find the end of the first \n\n separated block
            const firstDoubleBreak = escapedQueryText.indexOf('\n\n');
            if (firstDoubleBreak !== -1) {
                // Insert images between first block and the rest
                escapedQueryText = escapedQueryText.slice(0, firstDoubleBreak) + '\n' + legacyImgHtml + escapedQueryText.slice(firstDoubleBreak);
            } else {
                // Only one block — append at end
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
    <div style="font-size:12px;color:#88a;margin-bottom:10px;">Subject: ${subjectText}</div>
    <div class="query-box">${escapedQueryText}</div>

    <div id="form-area">
      <input type="hidden" id="f-uid" value="${uid}">
      <input type="hidden" id="f-email" value="${email || ""}">
      <input type="hidden" id="f-id" value="${id}">

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
    const MAX_IMAGES = 4;
    const hasPrev = ${hasPreviousReply};
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
      if (this.files[0] && selectedFiles.length < MAX_IMAGES) {
        selectedFiles.push(this.files[0]);
        this.value = '';
        renderSlots();
      }
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
                const busboy = Busboy({ headers: req.headers });
                const fields = {};
                const files = [];

                busboy.on("field", (fieldName, val) => {
                    fields[fieldName] = val;
                });

                busboy.on("file", (fieldName, file, info) => {
                    const { filename, mimeType } = info;
                    if (!filename) {
                        file.resume();
                        return;
                    }
                    const filepath = path.join(os.tmpdir(), `${Date.now()}_${filename}`);
                    const writeStream = fs.createWriteStream(filepath);
                    file.pipe(writeStream);
                    
                    files.push(new Promise((res, rej) => {
                        writeStream.on("finish", () => {
                            res({
                                fieldName,
                                filepath,
                                filename,
                                mimeType
                            });
                        });
                        writeStream.on("error", rej);
                    }));
                });

                busboy.on("finish", async () => {
                    try {
                        const resolvedFiles = await Promise.all(files);
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
            let { uid, id, email, reply, action, consecutive_mode } = parsed.fields;
            if (!uid || !id) return res.status(400).send("Missing data.");

            const targetUser = email || uid;
            const actionStr = String(action || "reply");

            const bucket = admin.storage().bucket();
            const uploadedUrls = [];
            for (const file of parsed.files) {
                const dest = `support_attachments/${Date.now()}_${file.filename}`;
                const [uploadedFile] = await bucket.upload(file.filepath, {
                    destination: dest,
                    metadata: {
                        contentType: file.mimeType,
                    }
                });
                await uploadedFile.makePublic();
                const publicUrl = `https://storage.googleapis.com/${bucket.name}/${dest}`;
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
            // Appending them to imageUrls would cause double display on the client.
            // imageUrls only holds the user's original submission attachments.

            const updateData = {
                reply: finalReply,
                status: finalStatus,
                read: false,
                replyTimestamp: timestamp,
                timestamp: timestamp
            };

            await db.collection("users").doc(targetUser).collection("notifications").doc(String(id)).update(updateData);

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
                                channelId: "cashdash_urgent_heads_up_v10",
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

// ─────────────────────────────────────────────
// FUNCTION 3: syncPresenceToFirestore
// ─────────────────────────────────────────────
const { onValueWritten } = require("firebase-functions/v2/database");

exports.syncPresenceToFirestore = onValueWritten({
    ref: "/status/{safeEmail}",
    region: "us-central1"
}, async (event) => {
    const safeEmail = event.params.safeEmail;
    if (!safeEmail) return;
    
    // 🔥 ALWAYS fetch the absolute latest state from RTDB!
    // Cloud Functions can execute out-of-order if a user rapidly opens/closes the app.
    // Fetching the latest value directly prevents an older "Offline" event from 
    // overwriting a newer "Online" event in Firestore.
    const snapshot = await admin.database().ref(`/status/${safeEmail}`).once('value');
    const latestData = snapshot.val();
    if (!latestData) return;

    const state = latestData.state;
    
    // Convert back from Firebase safe key format (commas back to dots)
    const email = safeEmail.replace(/,/g, ".");
    const updates = { status: state };

    if (state === "Offline") {
        const date = new Date(latestData.last_changed || Date.now());
        const options = {
            timeZone: 'Asia/Kolkata',
            day: '2-digit', month: '2-digit', year: 'numeric',
            hour: '2-digit', minute: '2-digit', hour12: true
        };
        const dateStr = date.toLocaleString('en-GB', options).toUpperCase().replace(/\u202F/g, ' ');
        updates.lastActiveTime = dateStr;
    }

    try {
        await db.collection("users").doc(email).update(updates);
        await db.collection("users").doc(email).collection("config").doc("profile").update(updates);
    } catch (e) {
        console.log(`Failed to sync presence to Firestore for ${email}:`, e.message);
    }
});

// ─────────────────────────────────────────────
// FUNCTION 4: onGlobalPush
// ─────────────────────────────────────────────
exports.onGlobalPush = onDocumentWritten({
    document: "global_pushes/{pushId}",
    region: "us-central1"
}, async (event) => {
    const newData = event.data.after.data();
    if (!newData) return; // Document was deleted, ignore

    const title = newData.title || "Announcement";
    const message = newData.message || "";
    
    if (!message) return;

    const adminOnly = newData.adminOnly === true;
    const targetTopic = adminOnly ? "admins" : "all_users";

    try {
        await admin.messaging().send({
            topic: targetTopic,
            notification: {
                title: title,
                body: message,
            },
            android: {
                priority: "high",
                notification: {
                    channelId: "cashdash_urgent_heads_up_v10",
                    icon: "ic_bell",
                    color: "#4ADE80"
                },
            }
        });
        console.log(`Successfully sent push notification to ${targetTopic}: ${title}`);
    } catch (error) {
        console.error(`Error sending push notification to ${targetTopic}:`, error);
    }
});

// ─────────────────────────────────────────────
// FUNCTION 5: onUserPush
// ─────────────────────────────────────────────
exports.onUserPush = onDocumentWritten({
    document: "user_pushes/{pushId}",
    region: "us-central1"
}, async (event) => {
    const newData = event.data.after.data();
    if (!newData) return; // Document was deleted, ignore

    const email = newData.email;
    const title = newData.title || "Support Alert";
    const message = newData.message || "";

    if (!email || !message) return;

    try {
        const userDoc = await db.collection("users").doc(email).get();
        const fcmToken = userDoc.data()?.fcmToken;

        if (fcmToken) {
            await admin.messaging().send({
                token: fcmToken,
                notification: {
                    title: title,
                    body: message,
                },
                android: {
                    priority: "high",
                    notification: {
                        channelId: "cashdash_urgent_heads_up_v10",
                        icon: "ic_bell",
                        color: "#4ADE80",
                        clickAction: "NotificationActivity"
                    },
                },
                data: {
                    userSpecificPush: "true"
                }
            });
            console.log(`Successfully sent user-specific push to ${email}: ${title}`);
        } else {
            console.log(`No FCM token found for user ${email}`);
        }
    } catch (error) {
        console.error(`Error sending user-specific push to ${email}:`, error);
    }
});

