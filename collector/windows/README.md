# AITSM Windows collector

The portal provides a one-click, self-contained Windows x64 installer. It enrolls once over HTTPS, installs `AITSMCollector` as an automatic Windows service, stores its collector credential with Windows DPAPI (`LocalMachine`), restricts the installation folder to SYSTEM and local Administrators, and begins reporting every 60 seconds. Version 1.3.0 also contains the optional Ollama AI Bridge. No inbound firewall rule, copied token, PowerShell command, .NET installation, or scheduled task is required.

## Download and install

### Administrator: company/LAN collector

1. Open **Settings → Windows Collector Installer**.
2. Enter a descriptive collector and site name, choose **Company / LAN**, and select **Download collector**.
3. Within 10 minutes, run the downloaded EXE and approve the Windows administrator prompt. Do not rename the file before running it.
4. Confirm the collector changes from **Never connected** to **Online** in Settings.

An administrator may instead choose **This computer only**. Only a Company/LAN collector receives discovery work and observes authorized LAN peers. Peer records contain only observable address, MAC, hostname, and reachability; they do not claim hardware telemetry.

### End user: this computer only

1. Open the end-user dashboard and find **Protect This Computer**.
2. Select **Download collector**, run the EXE within 10 minutes, and approve the Windows administrator prompt.
3. Confirm the device appears as **Online**.

End-user installers are always forced to `SELF` scope by the server, even if a request is modified. A self-device collector can report exactly one private-address host with its own authenticated hardware inventory and resource telemetry. It cannot poll LAN discovery commands or submit peer devices.

### Optional local AI bridge

Only an active `LAN` collector can be enabled as an AI Bridge. Install Ollama on that Windows computer, pull the exact model configured in Settings, keep Ollama on `127.0.0.1:11434`, then choose **Enable AI Bridge** from the collector action menu. The service sends an authenticated heartbeat every 30 seconds and polls the portal for short-lived jobs over outbound HTTPS. It calls Ollama only on localhost and returns the result over the same authenticated channel. `SELF` collectors cannot claim or complete AI jobs. Revoking the collector immediately invalidates both telemetry and bridge access.

Each download contains a high-entropy, single-use enrollment code in its generated filename. The server stores only its SHA-256 hash. Redemption returns the long-lived collector token once to the installer; the token is then DPAPI-encrypted on that Windows machine. Never share an unredeemed installer. Generate a new one if it expires or is renamed.

## Health, repair, revocation, and uninstall

- **Online**: a valid report was received in the last 2 minutes.
- **Stale**: the most recent report is 2–10 minutes old.
- **Offline**: no valid report was received for more than 10 minutes.
- **Never connected**: enrollment exists but no report has arrived.
- **Revoked**: the server will reject further reports for that credential.

The **Repair** action produces an installer tied to that exact collector record. When it runs on the matching computer, it repairs the service and updates the binary while reusing the protected credential. A fresh installer always redeems its single-use enrollment—even when another collector for the same portal is already installed—so the computer can be moved to the account that downloaded it. An installer for another environment also replaces the protected credential and restarts the service. Previous server records remain available for revocation or audit history.

The collector starts automatically after Windows boots (delayed automatic start), restarts after service crashes, and uses exponential retry up to 15 minutes during repeated network or server failures. It records a secret-free local health snapshot at `C:\ProgramData\AITSMCollector\collector-status.json`. Administrators and device owners can download a server-side diagnostic summary or create a repair installer from the portal.

Administrators can revoke all managed collectors in Settings. End users can view and revoke only collectors that they enrolled for themselves. Revocation is immediate and does not erase historical inventory under the normal data-retention policy.

To remove the local service, run an elevated command from the installed binary:

```powershell
"C:\ProgramData\AITSMCollector\AITSMCollector.exe" --uninstall
```

Then revoke the matching portal entry. The encrypted credential and local status snapshot are deleted locally. The service log is at `C:\ProgramData\AITSMCollector\collector.log`; it is capped by rotation and never intentionally logs the token.

## Network and troubleshooting

- Allow outbound TCP 443 to the AITSM hostname. No inbound port or router change is required.
- For AI, allow the Windows service to reach local `127.0.0.1:11434`; do not publish that port to the LAN or internet.
- Company/LAN discovery is restricted to the private CIDRs configured in **Settings → Asset Detection Configuration**. Normal reports use local neighbor data only. When an administrator queues LAN discovery, a Company/LAN collector also performs a bounded routed discovery job: it sends ICMP reachability checks and optional reverse-DNS lookups only to the server-provided approved ranges. The job is limited to 1,024 target addresses, so use specific floor or VLAN prefixes rather than broad ranges such as `10.`.
- Routed discovery requires the institution network to permit the collector host to reach the approved remote subnet. A discovered peer indicates network reachability only; full hardware and resource telemetry still requires that device to run its own collector.
- HTTP 401 in the local log means the credential was revoked or rejected. Revoke/uninstall it and download a replacement.
- A Windows clock that is badly out of sync no longer blocks enrollment data; the server safely substitutes its authoritative receipt time while preserving the reported device data.
- If the EXE says the enrollment is expired or already used, download a fresh installer.
- Windows may display an unknown-publisher warning until the organization applies an Authenticode code-signing certificate. Only use the installer downloaded while signed in to the trusted AITSM portal.
- A browser refresh does not make a collector online; state is based only on the server receipt time of authenticated reports.

## Build and release

The installer source is in `collector/windows/installer`. Build the production Windows x64 release with:

```powershell
dotnet publish collector/windows/installer/AITSMCollectorSetup.csproj -c Release -r win-x64 --self-contained true -o collector/windows/installer/publish
Get-FileHash collector/windows/installer/publish/AITSMCollectorSetup.exe -Algorithm SHA256
gh release create collector-vX.Y.Z "collector/windows/installer/publish/AITSMCollectorSetup.exe#AITSMCollectorSetup.exe" --target beta
```

Set `COLLECTOR_INSTALLER_URL` to the immutable release asset and pin its checksum in `COLLECTOR_INSTALLER_SHA256`. The portal downloads and verifies the release once per instance, then serves it only after an authenticated, CSRF-protected request whose enrollment code belongs to the requesting account. Deployment must apply every migration listed in `db/migrations/index.txt` before receiving traffic.
