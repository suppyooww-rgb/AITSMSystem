async function fetchJsonOrThrow(url, options = {}, requiresReauth = false) {
  const reauthToken = requiresReauth ? await obtainReauthToken('security-config') : '';
  const response = await fetch(url, {
    ...options,
    headers: { ...authHeaders(), ...(reauthToken ? { 'X-Reauth-Token': reauthToken } : {}), ...(options.headers || {}) }
  });
  const data = await response.json().catch(() => ({}));
  if (requiresReauth) consumeCachedReauth('security-config');
  if (!response.ok) throw new Error(data.error || data.message || 'Request failed');
  return data;
}

function setAiMessage(message, error = false) {
  const node = document.getElementById('aiConfigMessage');
  if (node) { node.textContent = message; node.classList.toggle('text-danger', error); }
}
function setAiButtonsDisabled(disabled) { ['aiSaveConfigBtn', 'aiTestBridgeBtn'].forEach(id => { const button = document.getElementById(id); if (button) button.disabled = disabled; }); }

function refreshOllamaCommand() {
  const model = document.getElementById('ollamaModel')?.value.trim() || 'qwen3:8b';
  const command = document.getElementById('ollamaPullCommand');
  if (command) command.textContent = `ollama pull ${model}`;
}

async function copyOllamaCommand() {
  const command = document.getElementById('ollamaPullCommand')?.textContent || 'ollama pull qwen3:8b';
  const button = document.getElementById('copyOllamaCommandBtn');
  try {
    await navigator.clipboard.writeText(command);
    if (button) button.textContent = 'Copied';
  } catch (_) {
    setAiMessage('Unable to copy automatically. Select and copy the command manually.', true);
  } finally {
    if (button) window.setTimeout(() => { button.textContent = 'Copy command'; }, 1800);
  }
}

async function loadSlaPolicies() {
  const policies = await fetchJsonOrThrow('/api/sla');
  document.getElementById('slaRows').innerHTML = policies.map(policy => `<tr>
    <td>${aitsmEscapeHtml(policy.priority)}</td>
    <td><input id='slaResponse-${policy.id}' type='number' min='1' max='43200' value='${Number(policy.responseTime)}' aria-label='${aitsmEscapeHtml(policy.priority)} response minutes'></td>
    <td><input id='slaResolution-${policy.id}' type='number' min='1' max='43200' value='${Number(policy.resolutionTime)}' aria-label='${aitsmEscapeHtml(policy.priority)} resolution minutes'></td>
    <td><button class='btn btn-ghost' type='button' onclick='saveSlaPolicy(${policy.id}, ${JSON.stringify(policy.priority)})'>Save</button></td>
  </tr>`).join('');
}
async function saveSlaPolicy(id, priority) {
  const message = document.getElementById('slaPolicyMessage');
  const responseTime = Number(document.getElementById(`slaResponse-${id}`)?.value);
  const resolutionTime = Number(document.getElementById(`slaResolution-${id}`)?.value);
  if (!Number.isInteger(responseTime) || !Number.isInteger(resolutionTime) || responseTime < 1 || resolutionTime < 1) {
    message.textContent = 'Enter whole-minute targets greater than zero.';
    message.className = 'small text-danger';
    return;
  }
  if (responseTime > resolutionTime) {
    message.textContent = 'Response time cannot exceed resolution time.';
    message.className = 'small text-danger';
    return;
  }
  message.textContent = `Saving ${priority} SLA…`;
  message.className = 'small';
  try {
    await fetchJsonOrThrow(`/api/sla/${encodeURIComponent(priority)}`, {
      method: 'PUT', body: JSON.stringify({ responseTime, resolutionTime })
    }, true);
    message.textContent = `${priority} SLA saved.`;
    message.className = 'small text-success';
    await loadSlaPolicies();
  } catch (error) {
    message.textContent = error.message || 'Unable to save SLA policy.';
    message.className = 'small text-danger';
  }
}
async function loadAssetDetectionPrefixes() {
  const data = await fetchJsonOrThrow('/settings/asset-ip-prefixes');
  document.getElementById('assetIpPrefixes').value = (data.prefixes || []).join('\n');
}
async function saveAssetDetectionPrefixes() {
  const prefixes = document.getElementById('assetIpPrefixes').value.split(/\r?\n/).map(value => value.trim()).filter(Boolean);
  await fetchJsonOrThrow('/settings/asset-ip-prefixes', { method: 'POST', body: JSON.stringify({ prefixes }) }, true);
  showAppAlert('Asset detection prefixes saved.');
}

async function loadAiConfig() {
  const data = await fetchJsonOrThrow('/api/ai/config');
  document.getElementById('ollamaModel').value = data.ollamaModel || 'qwen3:8b';
  refreshOllamaCommand();
  const status = document.getElementById('ollamaBridgeStatus');
  if (status) { status.textContent = data.localBridgeAvailable ? 'Online' : 'Not connected'; status.className = `status-pill ${data.localBridgeAvailable ? 'status-pill-success' : 'status-pill-warning'}`; }
  const bridge = await fetchJsonOrThrow('/api/ai/bridge/status');
  setAiMessage(bridge.message, !bridge.available && bridge.enabledCollectors > 0);
}
async function saveAiConfig() {
  const ollamaModel = document.getElementById('ollamaModel').value.trim();
  if (!ollamaModel) return setAiMessage('An Ollama model identifier is required.', true);
  setAiButtonsDisabled(true); setAiMessage('Saving Ollama model preference...');
  try { await fetchJsonOrThrow('/api/ai/config', { method: 'POST', body: JSON.stringify({ ollamaModel }) }, true); setAiMessage('Ollama model preference saved. The bridge will verify that exact model on its next heartbeat.'); await loadAiConfig(); }
  catch (error) { setAiMessage(error.message || 'Unable to save the Ollama model preference.', true); }
  finally { setAiButtonsDisabled(false); }
}

async function testAiBridge() {
  setAiButtonsDisabled(true); setAiMessage('Sending a test job through the secure AI Bridge…');
  try { const result = await fetchJsonOrThrow('/api/ai/bridge/test', { method: 'POST' }); setAiMessage(result.message || 'AI Bridge test passed.'); }
  catch (error) { setAiMessage(error.message || 'AI Bridge test failed.', true); }
  finally { setAiButtonsDisabled(false); await loadAiConfig().catch(() => {}); }
}

function collectorFilename(response) {
  const disposition = response.headers.get('content-disposition') || '';
  return disposition.match(/filename="([^"]+)"/i)?.[1] || 'AITSMCollectorSetup.exe';
}

let collectorRecords = new Map();

function ensureCollectorActionMenuBackdrop() {
  if (document.getElementById('collectorActionMenuBackdrop')) return;
  const backdrop = document.createElement('div');
  backdrop.id = 'collectorActionMenuBackdrop';
  backdrop.className = 'action-menu-backdrop hidden';
  backdrop.addEventListener('click', closeCollectorActionMenus);
  document.body.appendChild(backdrop);
}

function closeCollectorActionMenus() {
  document.querySelectorAll('.collector-action-menu').forEach((menu) => menu.classList.add('hidden'));
  document.querySelectorAll('.collector-menu-trigger').forEach((button) => button.setAttribute('aria-expanded', 'false'));
  document.getElementById('collectorActionMenuBackdrop')?.classList.add('hidden');
}

function toggleCollectorActionMenu(event, menuId) {
  event.stopPropagation();
  const trigger = event.currentTarget;
  const menu = document.getElementById(menuId);
  if (!menu) return;

  const shouldOpen = menu.classList.contains('hidden');
  closeCollectorActionMenus();
  if (!shouldOpen) return;

  ensureCollectorActionMenuBackdrop();
  document.getElementById('collectorActionMenuBackdrop')?.classList.remove('hidden');
  const rect = trigger.getBoundingClientRect();
  const menuWidth = 180;
  menu.style.position = 'fixed';
  menu.style.top = `${Math.min(window.innerHeight - 12, rect.bottom + 4)}px`;
  menu.style.left = `${Math.max(8, Math.min(window.innerWidth - menuWidth - 8, rect.right - menuWidth))}px`;
  menu.classList.remove('hidden');
  trigger.setAttribute('aria-expanded', 'true');
}

function collectorActionMenu(item, index) {
  const id = JSON.stringify(item.collectorId);
  const menuId = `collectorActionMenu-${index}`;
  const actions = [
    `<button type='button' onclick='downloadCollectorDiagnostics(${id})'>Download diagnostics</button>`
  ];
  if (item.scope === 'LAN' && item.state !== 'REVOKED') {
    actions.push(`<button type='button' onclick='setCollectorAiBridge(${id}, ${!item.aiBridgeEnabled})'>${item.aiBridgeEnabled ? 'Disable' : 'Enable'} AI Bridge</button>`);
  }
  if (item.state !== 'REVOKED') {
    actions.push(`<button type='button' onclick='repairCollector(${id})'>Download repair installer</button>`);
    actions.push(`<button class='collector-menu-danger' type='button' onclick='revokeCollector(${id})'>Revoke collector</button>`);
  } else {
    actions.push(`<button class='collector-menu-danger' type='button' onclick='archiveCollector(${id})'>Remove from list</button>`);
  }
  return `<div class='row-action-wrap'>
    <button class='btn btn-ghost icon-btn collector-menu-trigger' type='button' onclick='toggleCollectorActionMenu(event, "${menuId}")' aria-haspopup='menu' aria-expanded='false' aria-controls='${menuId}' aria-label='Actions for ${aitsmEscapeHtml(item.name || 'collector')}' title='Collector actions'>•••</button>
    <div id='${menuId}' class='row-action-menu collector-action-menu hidden' role='menu'>${actions.join('')}</div>
  </div>`;
}

async function createAndDownloadCollector(packageRequest, status) {
  const enrollment = await fetchJsonOrThrow('/api/monitoring/collector-packages', { method: 'POST', body: JSON.stringify(packageRequest) });
  const response = await fetch('/api/monitoring/collector-installer/windows', {
    method: 'POST', headers: authHeaders(), body: JSON.stringify({ enrollmentCode: enrollment.enrollmentCode, repairCollectorId: enrollment.repairCollectorId || null })
  });
  if (!response.ok) { const data = await response.json().catch(() => ({})); throw new Error(data.error || 'Installer download failed'); }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a'); link.href = url; link.download = collectorFilename(response); document.body.appendChild(link); link.click(); link.remove(); URL.revokeObjectURL(url);
  const target = enrollment.baseUrl || window.location.origin;
  status.textContent = `Installer downloaded for ${String(enrollment.environment || 'production').toUpperCase()} (${target}, ${enrollment.scope} scope). Run it within 10 minutes.`;
  status.className = 'small text-success';
}

async function downloadCollectorInstaller() {
  const button = document.getElementById('downloadCollectorBtn');
  const status = document.getElementById('collectorDownloadStatus');
  button.disabled = true; status.textContent = 'Creating a secure one-time installer…'; status.className = 'small';
  try {
    await createAndDownloadCollector({
      name: document.getElementById('collectorName').value.trim(),
      siteId: document.getElementById('collectorSite').value.trim(),
      scope: document.getElementById('collectorScope').value
    }, status);
  } catch (error) { status.textContent = error.message || 'Unable to create installer.'; status.className = 'small text-danger'; }
  finally { button.disabled = false; }
}

async function repairCollector(id) {
  closeCollectorActionMenus();
  const item = collectorRecords.get(id); if (!item) return;
  const status = document.getElementById('collectorDownloadStatus');
  status.textContent = `Creating a repair installer for ${item.name}…`; status.className = 'small';
  try {
    await createAndDownloadCollector({ name: item.name, siteId: item.siteId, scope: item.scope, repairCollectorId: item.collectorId }, status);
  } catch (error) { status.textContent = error.message || 'Unable to create repair installer.'; status.className = 'small text-danger'; }
}

async function downloadCollectorDiagnostics(id) {
  closeCollectorActionMenus();
  const response = await fetch(`/api/monitoring/collectors/${encodeURIComponent(id)}/diagnostics`, { headers: authHeaders() });
  if (!response.ok) { const data = await response.json().catch(() => ({})); return showAppAlert(data.error || 'Unable to download diagnostics'); }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a'); link.href = url; link.download = `AITSM-collector-${id}-diagnostics.txt`; document.body.appendChild(link); link.click(); link.remove(); URL.revokeObjectURL(url);
}

async function loadCollectors() {
  const rows = document.getElementById('collectorRows');
  const collectors = await fetchJsonOrThrow('/api/monitoring/collectors');
  collectorRecords = new Map(collectors.map(item => [item.collectorId, item]));
  rows.innerHTML = collectors.length ? collectors.map((item, index) => `<tr><td>${aitsmEscapeHtml(item.name)}<div class='small'>${aitsmEscapeHtml(item.statusReason || '')}</div><div class='small'>Agent ${aitsmEscapeHtml(item.agentVersion || 'legacy')} · Failures ${Number(item.consecutiveFailures || 0)}</div>${item.scope === 'LAN' ? `<div class='small'>AI Bridge: ${item.aiBridgeEnabled ? (item.aiBridgeAvailable ? 'ready' : 'enabled, waiting for Ollama') : 'disabled'}${item.aiBridgeModel ? ` · ${aitsmEscapeHtml(item.aiBridgeModel)}` : ''}</div>` : ''}${item.aiBridgeLastError ? `<div class='small text-danger'>AI: ${aitsmEscapeHtml(item.aiBridgeLastError)}</div>` : ''}${item.lastReportedError ? `<div class='small text-danger'>Last error: ${aitsmEscapeHtml(item.lastReportedError)}</div>` : ''}</td><td>${aitsmEscapeHtml(item.scope)}</td><td><span class='badge ${item.state === 'ONLINE' ? 'resolved' : 'warning'}'>${aitsmEscapeHtml(item.state)}</span></td><td>${aitsmEscapeHtml(item.lastSeenAt ? new Date(item.lastSeenAt).toLocaleString() : 'Never')}</td><td class='collector-actions-cell'>${collectorActionMenu(item, index)}</td></tr>`).join('') : `<tr><td colspan='5'>No collectors enrolled.</td></tr>`;
}

async function setCollectorAiBridge(id, enabled) {
  closeCollectorActionMenus();
  const item = collectorRecords.get(id); if (!item) return;
  if (!confirm(`${enabled ? 'Enable' : 'Disable'} AI Bridge on ${item.name}?`)) return;
  try {
    await fetchJsonOrThrow(`/api/ai/bridge/collectors/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify({ enabled }) }, true);
    await Promise.all([loadCollectors(), loadAiConfig()]);
  } catch (error) { showAppAlert(error.message || 'Unable to update AI Bridge.'); }
}

async function revokeCollector(id) {
  closeCollectorActionMenus();
  if (!confirm('Revoke this collector? It will stop accepting reports immediately.')) return;
  await fetchJsonOrThrow(`/api/monitoring/collectors/${encodeURIComponent(id)}`, { method: 'DELETE' });
  await loadCollectors();
}

async function archiveCollector(id) {
  closeCollectorActionMenus();
  const item = collectorRecords.get(id);
  if (!item || item.state !== 'REVOKED') return;
  if (!confirm(`Remove ${item.name} from the active collector list? Historical records will be retained. This does not uninstall the Windows service.`)) return;
  try {
    await fetchJsonOrThrow(`/api/monitoring/collectors/${encodeURIComponent(id)}/archive`, { method: 'DELETE' });
    await loadCollectors();
  } catch (error) {
    showAppAlert(error.message || 'Unable to remove the collector from the active list.');
  }
}

document.addEventListener('DOMContentLoaded', async () => {
  document.getElementById('aiSaveConfigBtn')?.addEventListener('click', saveAiConfig);
  document.getElementById('aiTestBridgeBtn')?.addEventListener('click', testAiBridge);
  document.getElementById('downloadCollectorBtn')?.addEventListener('click', downloadCollectorInstaller);
  document.getElementById('ollamaModel')?.addEventListener('input', refreshOllamaCommand);
  document.getElementById('copyOllamaCommandBtn')?.addEventListener('click', copyOllamaCommand);
  try { await Promise.all([loadSlaPolicies(), loadAssetDetectionPrefixes(), loadAiConfig(), loadCollectors()]); }
  catch (error) { showAppAlert(error.message || 'Unable to load settings data'); }
});
