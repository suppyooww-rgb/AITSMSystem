# AITSMSystem User Guide

This guide explains how to use the system as an end-user or admin.

## 1. Sign Up and Login
1. Open the application in your browser.
2. Open the invitation link sent by your company administrator.
3. Confirm the locked invited email, accept the EULA, and create your password.
4. Administrators scan the MFA QR code (or use the manual key), confirm one code, and store recovery codes offline.
5. Login with your credentials.

Open `/security` to enroll or manage MFA, regenerate one-time recovery codes after reauthentication, review recent security activity, revoke another session, or sign out everywhere. Network information is deliberately approximate.

---

## 2. End-User Features

### 2.1 Create a Ticket
1. Open **Create Ticket**.
2. Enter title, description, priority, category, and optional device.
3. Submit ticket.

### 2.2 Track Tickets
1. Open **Tickets** page.
2. Review status and SLA indicators.
3. Update allowed status transitions when needed.

### 2.3 AI Troubleshooting Assistant
1. Open **AI Assistant**.
2. Type your issue and click **Send**.
3. Behavior:
   - If AI provider is healthy: you see one assistant chat reply.
   - If AI provider is unavailable/malformed: fallback troubleshooting panel appears.
4. Click **Create Ticket from Last Reply** to prefill ticket form from visible final output.

### 2.4 AI-Assisted Ticket Creation
1. Enter a ticket description with at least 10 characters.
2. Click **Analyze with AI**.
3. Review and edit the suggested title, description, category, and priority in the preview.
4. Review any knowledge recommendations or possible duplicate tickets.
5. Click **Apply reviewed suggestions** only when the proposed fields are accurate.
6. Continue editing as needed, then create the ticket normally.

### 2.5 Notifications
- Open notification areas in dashboard/shell to review updates.

### 2.5 Profile and Password
- Update your profile and change password from settings/profile pages.

---

## 3. Admin Features

### 3.1 User Management
- List users, adjust supported role state, reset passwords, approve email status, invite new users, and delete users.
- Send, resend, list, and revoke single-use invitations. Resending invalidates the old link.
- Sensitive actions prompt for recent password or MFA verification. Grants are short-lived, scoped, server-side, and single-use for mutations.
- Only a superadmin can perform the audited MFA reset for another administrator; this revokes all target sessions.

### 3.2 Device Management
- Register LAN devices.
- Update/delete device records.
- Use IP lookup and sync-from-monitoring tools.

### 3.3 Monitoring
- Check collector health, LAN discovery, genuine device telemetry, and alerts.
- Queue discovery when needed; the enrolled LAN collector performs it on its next poll.

### 3.4 Knowledge Base
- Create/edit/delete knowledge articles.

### 3.5 Analytics
- View ticket trends and system health insights.

### 3.6 SLA
- View configured SLA policies.

### 3.7 AI Ticket Assistance
1. Open **Ticket Management** and select a ticket ID or title.
2. In **AI Ticket Assistance**, click **Generate assistance**.
3. Review the summary and checklist.
4. Edit the resolution draft before copying or using it. Generation never posts an update or changes ticket status automatically.

### 3.8 Local AI Bridge

The **Settings → Local AI Bridge** card lets administrators connect a company-managed Windows collector to Ollama:

1. Download Ollama only from the official Windows link shown in Settings.
2. Enter the exact model identifier reported by `ollama list`; the initial recommendation is `qwen3:8b`.
3. Copy and run the displayed `ollama pull` command on the Windows computer intended for local AI.
4. Do not expose Ollama port `11434` to the internet. Keep it bound to `127.0.0.1`.
5. Install or repair collector version 1.3.0 or later with `LAN` scope.
6. In the collector action menu, choose **Enable AI Bridge**, wait for **Online**, then select **Test AI Bridge**.

The bridge requires no inbound internet access. It polls authenticated, short-lived jobs over HTTPS and calls Ollama only on localhost. End-user `SELF` collectors are not eligible to process AI jobs.

SLA timers, breach handling, and deterministic risk scoring do not depend on Ollama and remain available while the AI Bridge is unavailable.

---

## 4. Practical Workflow (Recommended)
1. User experiences issue.
2. User tries AI Assistant first for quick troubleshooting.
3. If unresolved, user creates ticket (optionally from AI output).
4. Admin triages ticket and correlates with monitoring/device data.
5. Admin resolves and updates knowledge article if recurring issue.

---

## 5. Troubleshooting for Users

### I cannot log in
- Verify email was approved/verified.
- Reset password if needed.

### AI assistant shows fallback panel
- Confirm a company/LAN collector version 1.3.0+ is online and has AI Bridge enabled.
- Confirm Ollama is running and the configured model appears in `ollama list`.
- You can still create a ticket draft from deterministic fallback guidance.

### No monitoring data
- Confirm a collector is enrolled, running inside the target LAN, and allowed outbound HTTPS access.
- Confirm its configured private CIDRs match the approved ranges and review the collector troubleshooting guide.

---

## 6. Keyboard/UX Tips
- In AI assistant input:
  - `Enter` = send
  - `Shift+Enter` = newline

---

## 7. Data & Safety Notes
- Do not include sensitive secrets in ticket descriptions.
- Use strong passwords.
- Admin actions are role-gated and sensitive operations require verification.
