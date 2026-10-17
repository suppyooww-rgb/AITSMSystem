# AITSM Beta Master Checklist

This is the single authoritative checklist for beta development. Item IDs and completed entries remain stable. New work is appended to the appropriate section, and each implementation is tracked separately from its verification.

Status key: `DONE` implemented and verified; `TEST` implemented but awaiting user acceptance or multi-device testing; `NEXT` approved remaining work; `LATER` production-stage work.

## 1. Environment and access

- [x] ENV-01 `DONE` Create a separate Google Cloud beta deployment.
- [x] ENV-02 `DONE` Keep the production service isolated and unchanged during beta work.
- [x] AUTH-01 `DONE` Enforce invitation-only registration.
- [x] AUTH-02 `DONE` Remove public account signup.
- [x] AUTH-03 `DONE` Use single-use, expiring invitations.
- [x] AUTH-04 `DONE` Mask administrator reauthentication password entry.
- [x] AUTH-05 `DONE` Fix administrator-role grant serialization.
- [x] AUTH-06 `DONE` Remove the option to return a verified invitation account to pending.
- [x] AUTH-07 `DONE` Enforce the verified-account rule on the server.
- [x] AUTH-08 `DONE` Replace visible password-reset prompts with masked fields.
- [ ] AUTH-09 `TEST` Grant an administrator role and verify mandatory MFA enrollment and administrator portal access.

## 2. Collector, monitoring, and inventory

- [x] COL-01 `DONE` Provide a plug-and-play Windows collector installer.
- [x] COL-02 `DONE` Install and run the collector as a Windows background service.
- [x] COL-03 `DONE` Restrict end-user collectors to self-device telemetry.
- [x] COL-04 `DONE` Allow administrator collectors to use company/LAN scope.
- [x] COL-05 `DONE` Provide collector diagnostics, repair, revocation, and removal controls.
- [x] COL-06 `DONE` Store collector credentials securely and bind enrollment to one machine.
- [x] COL-07 `DONE` Support dynamic IP addresses through stable device identity.
- [x] COL-08 `DONE` Deduplicate device records.
- [x] COL-09 `DONE` Show only collector-agent devices on primary monitoring and inventory surfaces.
- [x] COL-10 `DONE` Move detailed hardware and storage data into the device detail view.
- [x] COL-11 `DONE` Make collector actions responsive through an overflow menu.
- [ ] COL-12 `TEST` Verify continuous telemetry from the administrator computer.
- [ ] COL-13 `TEST` Verify continuous telemetry from the end-user laptop.
- [ ] COL-14 `TEST` Verify collector recovery after Windows and service restarts.

## 3. SLA and analytics

- [x] SLA-01 `DONE` Run SLA calculations continuously on a 24/7 elapsed-time basis.
- [x] SLA-02 `DONE` Implement response and resolution targets.
- [x] SLA-03 `DONE` Implement deterministic SLA risk scoring.
- [x] SLA-04 `DONE` Generate SLA warning and breach notifications.
- [x] SLA-05 `DONE` Correct telemetry coverage health scoring so higher coverage is healthy.
- [ ] SLA-06 `TEST` Verify response, resolution, breach, resolve, and reopen behavior end to end.
- [ ] SLA-07 `NEXT` Add administrator workload and assignment analytics.

## 4. Ticket workflow and administration

- [x] TKT-01 `DONE` Require internal notes for status transitions.
- [x] TKT-02 `DONE` Require requester-visible resolution details when resolving a ticket.
- [x] TKT-03 `DONE` Allow the first administrator to claim an unassigned ticket atomically.
- [x] TKT-04 `DONE` Lock claimed tickets against changes by other regular administrators.
- [x] TKT-05 `DONE` Allow audited Super Admin ownership overrides and reassignment.
- [x] TKT-06 `DONE` Show ownership and status activity in ticket history.
- [x] TKT-07 `DONE` Refresh the administrator operational feed automatically.
- [x] TKT-08 `DONE` Keep older operational events available through notifications.
- [x] TKT-09 `DONE` Limit AI assistance to the assigned administrator on active tickets.
- [x] TKT-10 `DONE` Allow Super Admin AI override only on active tickets.
- [x] TKT-11 `DONE` Hide and reject AI assistance for Resolved and Closed tickets.
- [x] TKT-12 `DONE` Make completed-ticket reviewed resolutions read-only.
- [ ] TKT-13 `TEST` Verify ticket creation and notification using the end-user laptop and administrator computer.
- [ ] TKT-14 `TEST` Verify claim locking with two administrator accounts.
- [ ] TKT-15 `TEST` Verify Super Admin reassignment and override auditing.
- [ ] TKT-16 `TEST` Verify requester notifications and the complete resolve/reopen lifecycle.

## 5. Local AI and Ollama bridge

- [x] AI-01 `DONE` Remove Gemini configuration and runtime dependencies from the beta UI.
- [x] AI-02 `DONE` Add the Ollama local-AI bridge foundation.
- [x] AI-03 `DONE` Add the Ollama for Windows download link and model preference controls.
- [x] AI-04 `DONE` Keep Ollama bound locally and use authenticated outbound bridge requests.
- [x] AI-05 `DONE` Fix end-user ticket AI authenticated-request handling.
- [x] AI-06 `DONE` Prevent unauthorized or completed-ticket requests from consuming AI capacity.
- [ ] AI-07 `TEST` Verify the administrator collector can reach local Ollama.
- [ ] AI-08 `TEST` Verify an end-user AI request can use the administrator computer's bridge.
- [ ] AI-09 `TEST` Verify bridge recovery after restarting Ollama and the collector.
- [ ] AI-10 `NEXT` Improve bridge diagnostics and guided recovery messages.

## 6. Quality, security, and release

- [x] QA-01 `DONE` Maintain automated regression tests; current verified total: 100.
- [ ] QA-02 `NEXT` Complete responsive, browser zoom, and mobile-layout review.
- [ ] QA-03 `NEXT` Perform a complete role and permission security audit.
- [ ] QA-04 `NEXT` Review notification acknowledgement, retention, and cleanup.
- [ ] QA-05 `NEXT` Test database backup and restoration.
- [ ] QA-06 `NEXT` Complete beta user-acceptance testing.
- [ ] REL-01 `LATER` Approve the final set of records to retain.
- [ ] REL-02 `LATER` Prepare a clean production database and application deployment.
- [ ] REL-03 `LATER` Migrate only approved records.
- [ ] REL-04 `LATER` Perform production smoke, security, and rollback verification.

## Current deployment baseline

- Beta: `aitsm-beta-00031-tqj`
- Production: `aitsm-00035-fz5`
- Latest implementation commit: `4240b8e`
