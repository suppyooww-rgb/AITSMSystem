const LAN_AUTO_REFRESH_VISIBLE_MS = 10 * 1000;
const LAN_AUTO_REFRESH_HIDDEN_MS = 30 * 1000;
let monitoringAutoRefreshTimer = null;
let monitoringRefreshInFlight = false;
let monitoringLastUpdatedAt = null;
let editingDeviceId = null;
let deviceRegistry = [];
let monitoringDevicesCache = [];
const monitoringFilters = {
  search: '',
  reachability: 'all',
  sort: 'cpu-desc'
};

function statusBadge(status) {
  const s = String(status || '').toLowerCase();
  if (s === 'critical') return 'open';
  if (s === 'high risk') return 'warning';
  if (s === 'offline' || s === 'unavailable' || s === 'unreachable') return 'warning';
  return 'resolved';
}

function setStatusField(status) {
  const normalized = (status || 'Unreachable').trim() || 'Unreachable';
  const hidden = document.getElementById('status');
  const display = document.getElementById('statusDisplay');
  if (hidden) hidden.value = normalized;
  if (display) display.value = normalized;
}

function formatLastSeen(value) {
  const date = new Date(value || '');
  if (Number.isNaN(date.getTime())) return value || 'Never';
  const zone = new Intl.DateTimeFormat(undefined, { timeZoneName: 'short' }).formatToParts(date).find((part) => part.type === 'timeZoneName')?.value || '';
  return `${new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(date)} ${zone}`.trim();
}

function formatRelativeTime(value) {
  if (!value) return 'Waiting for update…';
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return 'Never updated';
  const diffMs = Math.max(0, Date.now() - date.getTime());
  if (diffMs < 5000) return 'Updated just now';
  const seconds = Math.round(diffMs / 1000);
  if (seconds < 60) return `Updated ${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  return `Updated ${minutes}m ago`;
}

function normalizedMonitorText(value) {
  return String(value || '').trim().toLowerCase();
}

function monitorSortValue(device, sortKey) {
  if (sortKey === 'memory-desc') return Number(device.memoryUsagePercent ?? -1);
  if (sortKey === 'hostname-asc') return normalizedMonitorText(device.hostname || device.ipAddress);
  if (sortKey === 'last-seen-desc') return parseDateValue(device.lastSeen);
  return Number(device.cpuUsagePercent ?? -1);
}

function parseDateValue(value) {
  const parsed = new Date(value || '');
  return Number.isNaN(parsed.getTime()) ? 0 : parsed.getTime();
}

function applyMonitoringFilters(devices) {
  const safe = Array.isArray(devices) ? devices : [];
  const search = normalizedMonitorText(monitoringFilters.search);

  const filtered = safe.filter((device) => {
    const matchesSearch = !search || [
      device.hostname,
      device.ipAddress
    ].map(normalizedMonitorText).some((token) => token.includes(search));

    const matchesReachability = monitoringFilters.reachability === 'all'
      || (monitoringFilters.reachability === 'online' && device.reachable)
      || (monitoringFilters.reachability === 'offline' && !device.reachable);

    return matchesSearch && matchesReachability;
  });

  return filtered.sort((a, b) => {
    const sortKey = monitoringFilters.sort;
    if (sortKey === 'hostname-asc') {
      return monitorSortValue(a, sortKey).localeCompare(monitorSortValue(b, sortKey));
    }
    return monitorSortValue(b, sortKey) - monitorSortValue(a, sortKey);
  });
}

function renderMonitorTableCount(visibleCount, totalCount) {
  const el = document.getElementById('monitorTableCount');
  if (!el) return;
  el.textContent = `${visibleCount} shown / ${totalCount} collector devices`;
}

function updateMonitoringLiveStatus(state = 'idle', message) {
  const chip = document.getElementById('monitorLiveStatus');
  const label = document.getElementById('monitorLiveStatusText');
  if (!chip || !label) return;

  chip.dataset.state = state;
  if (message) {
    label.textContent = message;
    return;
  }

  if (state === 'loading') {
    label.textContent = 'Checking for collector reports…';
    return;
  }

  if (state === 'error') {
    label.textContent = 'Telemetry refresh failed';
    return;
  }

  label.textContent = formatRelativeTime(monitoringLastUpdatedAt);
}

function telemetryBadge(device) {
  const label = device.stale ? 'Stale telemetry' : (device.telemetryAvailable ? (device.telemetrySourceType || 'Telemetry') : 'Unavailable');
  const tone = device.telemetryAvailable && !device.stale ? 'resolved' : 'warning';
  return `<span class='badge ${tone} monitor-telemetry-badge' title='${aitsmEscapeHtml(label)}'>${aitsmEscapeHtml(label)}</span>`;
}

function renderMonitorSummary(summary) {
  const wrap = document.getElementById('monitorSummary');
  if (!wrap) return;

  const chips = [
    ['Collector Devices', summary?.totalDiscovered ?? 0],
    ['Monitored Devices', summary?.monitoredDevices ?? 0],
    ['Telemetry Available', summary?.telemetryAvailableDevices ?? 0],
    ['Collector', String(summary?.collectorState || 'NOT_ENROLLED').replaceAll('_', ' ')]
  ];

  wrap.innerHTML = chips.map(([label, value]) => `<div class='kpi-chip'><div class='kpi-label'>${label}</div><div class='kpi-value'>${value}</div></div>`).join('');
}

function renderMonitoringHealthPanel(summary, devices) {
  const panel = document.getElementById('monitorHealthPanel');
  if (!panel) return;

  const available = devices.filter(d => d.telemetryAvailable).length;
  const collectorState = String(summary?.collectorState || 'NOT_ENROLLED').replaceAll('_', ' ');
  const lastUpdate = summary?.timestamp;
  panel.innerHTML = `
    <div class='insight-grid monitor-health-grid'>
      <div class='insight-item'><div class='insight-label'>Collector health</div><div class='insight-value monitor-text-wrap'>${aitsmEscapeHtml(collectorState)}</div></div>
      <div class='insight-item'><div class='insight-label'>LAN scan</div><div class='insight-value'>${aitsmEscapeHtml(String(summary?.discoveryState || 'NEVER').replaceAll('_', ' '))}</div></div>
      <div class='insight-item'><div class='insight-label'>Telemetry coverage</div><div class='insight-value'>${available}/${devices.length}</div></div>
      <div class='insight-item'><div class='insight-label'>Last data received</div><div class='insight-value monitor-text-wrap monitor-updated-at'>${formatLastSeen(lastUpdate)}</div></div>
    </div>`;
}

function renderMonitorCards(devices) {
  const wrap = document.getElementById('monitorCards');
  if (!wrap) return;

  if (!devices.length) {
    wrap.innerHTML = `<div class='empty-state'><h3>No collector data received</h3><p>Enroll and start a collector on the target LAN. Scan LAN Now queues work for that collector; Cloud Run does not scan private networks directly.</p><button class='btn btn-primary' onclick='refreshDiscovery()'>Scan LAN Now</button></div>`;
    return;
  }

  const highlight = [...devices]
    .sort((a, b) => {
      const aScore = Number(a.cpuUsagePercent ?? 0) + Number(a.memoryUsagePercent ?? 0);
      const bScore = Number(b.cpuUsagePercent ?? 0) + Number(b.memoryUsagePercent ?? 0);
      return bScore - aScore;
    })
    .slice(0, 6);

  wrap.innerHTML = highlight.map(d => `
    <article class='card monitor-device-card'>
      <div class='card-head monitor-device-head'>
        <h3 class='section-title monitor-device-title' title='${aitsmEscapeHtml(d.hostname || d.ipAddress)}'>${aitsmEscapeHtml(d.hostname || d.ipAddress)}</h3>
        ${telemetryBadge(d)}
      </div>
      <div class='small monitor-device-meta'>
        <span class='monitor-text-wrap'>${aitsmEscapeHtml(d.ipAddress || '-')}</span>
        <span class='monitor-meta-separator' aria-hidden='true'>•</span>
        <span>Reachable: ${d.reachable ? 'Yes' : 'No'}</span>
      </div>
      <div class='insight-grid monitor-device-stats'>
        <div class='insight-item'><div class='insight-label'>CPU</div><div class='insight-value'>${d.cpuUsagePercent != null ? `${Number(d.cpuUsagePercent).toFixed(1)}%` : 'N/A'}</div></div>
        <div class='insight-item'><div class='insight-label'>Memory</div><div class='insight-value'>${d.memoryUsagePercent != null ? `${Number(d.memoryUsagePercent).toFixed(1)}%` : 'N/A'}</div></div>
      </div>
    </article>`).join('');

  if (devices.length > highlight.length) {
    wrap.innerHTML += `<div class='small'>Showing top ${highlight.length} devices by utilization out of ${devices.length} collector-managed devices.</div>`;
  }
}

function renderMonitorTable(devices) {
  const rows = document.getElementById('monitorTableRows');
  if (!rows) return;

  const visible = applyMonitoringFilters(devices);
  renderMonitorTableCount(visible.length, devices.length);

  if (!visible.length) {
    renderTableEmptyState(rows, 6, devices.length ? 'No devices matched the current filters.' : 'No collector-managed devices are reporting yet.');
    return;
  }

  rows.innerHTML = visible.map(d => `<tr>
    <td>${aitsmEscapeHtml(d.hostname || '-')}</td>
    <td title='${aitsmEscapeHtml(`Observed IPs: ${(d.observedIpAddresses || [d.ipAddress]).join(', ')}`)}'>${aitsmEscapeHtml(d.ipAddress || '-')}${(d.observedIpAddresses || []).length > 1 ? `<div class='small'>${d.observedIpAddresses.length} addresses observed</div>` : ''}</td>
    <td>${d.stale ? 'Stale' : (d.reachable ? 'Online' : 'Offline')}</td>
    <td>${d.cpuUsagePercent != null ? `${Number(d.cpuUsagePercent).toFixed(1)}%` : '-'}</td>
    <td>${d.memoryUsagePercent != null ? `${Number(d.memoryUsagePercent).toFixed(1)}%` : '-'}</td>
    <td>${formatLastSeen(d.lastSeen)}</td>
  </tr>`).join('');
}

function renderLanIpSuggestions(ips) {
  const suggestions = document.getElementById('lanIpSuggestions');
  if (!suggestions) return;

  const uniqueIps = [...new Set((ips || []).map((ip) => (ip || '').trim()).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' }));

  suggestions.innerHTML = uniqueIps.map((ip) => `<option value="${aitsmEscapeHtml(ip)}"></option>`).join('');
}

function setRefreshDiscoveryBusy(isBusy) {
  const button = document.getElementById('refreshDiscoveryBtn');
  if (!button) return;
  button.disabled = isBusy;
  button.textContent = isBusy ? 'Queuing LAN scan…' : 'Scan LAN Now';
}

async function refreshDiscovery() {
  setRefreshDiscoveryBusy(true);
  try {
    const data = await window.aitsmFetchJson('/api/monitoring/refresh-discovery', { method: 'POST' });
    updateMonitoringLiveStatus('live', data.message || 'LAN scan queued for collector');
    await loadMonitoring({ force: true, source: 'queued' });
  } catch (error) {
    alert(error.message || 'Failed to queue discovery');
  } finally {
    setRefreshDiscoveryBusy(false);
  }
}

function scheduleMonitoringAutoRefresh() {
  stopMonitoringAutoRefresh();
  const interval = document.hidden ? LAN_AUTO_REFRESH_HIDDEN_MS : LAN_AUTO_REFRESH_VISIBLE_MS;
  monitoringAutoRefreshTimer = window.setInterval(() => {
    loadMonitoring({ source: 'auto' });
    loadDevices();
  }, interval);
}

async function loadMonitoring({ force = false, source = 'manual' } = {}) {
  if (monitoringRefreshInFlight && !force) return;
  monitoringRefreshInFlight = true;
  updateMonitoringLiveStatus('loading');
  const monitorRows = document.getElementById('monitorTableRows');
  if (monitorRows) showTableSkeleton(monitorRows, { rowCount: 6, columnCount: 6 });

  try {
    const [summaryRes, devicesRes, peerIpsRes] = await Promise.all([
      fetch('/api/monitoring/summary', { headers: authHeaders() }),
      fetch('/api/monitoring/lan-devices', { headers: authHeaders() }),
      fetch('/api/monitoring/lan-peer-ips', { headers: authHeaders() })
    ]);

    const [summary, devices, peerIps] = await Promise.all([
      summaryRes.ok ? summaryRes.json() : Promise.reject(new Error(`Monitoring summary failed with HTTP ${summaryRes.status}.`)),
      devicesRes.ok ? devicesRes.json() : Promise.reject(new Error(`Monitoring devices failed with HTTP ${devicesRes.status}.`)),
      peerIpsRes.ok ? peerIpsRes.json() : Promise.reject(new Error(`Monitoring peer list failed with HTTP ${peerIpsRes.status}.`))
    ]);
    const safeDevices = Array.isArray(devices) ? devices : [];
    const safePeerIps = Array.isArray(peerIps) ? peerIps : [];
    monitoringDevicesCache = safeDevices;

    renderMonitorSummary(summary);
    renderMonitoringHealthPanel(summary, monitoringDevicesCache);
    renderMonitorCards(monitoringDevicesCache);
    if (monitorRows) clearTableSkeleton(monitorRows);
    renderMonitorTable(monitoringDevicesCache);
    renderLanIpSuggestions(safePeerIps);

    monitoringLastUpdatedAt = summary.timestamp || monitoringLastUpdatedAt;
    const discoveryState = String(summary.discoveryState || '').toUpperCase();
    const discoveryPending = ['PENDING', 'IN_PROGRESS'].includes(discoveryState);
    updateMonitoringLiveStatus('live', discoveryPending
      ? `LAN scan ${discoveryState.toLowerCase().replace('_', ' ')}; waiting for LAN collector`
      : (source === 'queued' ? 'LAN scan queued; waiting for LAN collector' : undefined));
  } catch {
    monitoringDevicesCache = [];
    renderMonitorTableCount(0, 0);
    if (monitorRows) renderTableErrorState(monitorRows, 6, 'Unable to load monitoring table data.');
    updateMonitoringLiveStatus('error');
  } finally {
    if (monitorRows) clearTableSkeleton(monitorRows);
    monitoringRefreshInFlight = false;
  }
}

async function autoFillDeviceContextByIp() {
  const ipInput = document.getElementById('ipAddress');
  const deviceInput = document.getElementById('deviceName');
  const assignedInput = document.getElementById('assignedUser');
  if (!ipInput || !deviceInput || !assignedInput) return;

  const ip = (ipInput.value || '').trim();
  if (!ip) return;

  try {
    const res = await fetch(`/api/devices/ip-lookup?ip=${encodeURIComponent(ip)}`, { headers: authHeaders() });
    const data = await res.json();
    if (!res.ok) {
      setStatusField('Unreachable');
      return;
    }

    if (data.deviceName) deviceInput.value = data.deviceName;
    if (!assignedInput.value.trim() && data.assignedUser) assignedInput.value = data.assignedUser;
    setStatusField(data.suggestedStatus);
  } catch {
    setStatusField('Unreachable');
  }
}

function seedAssignedUserFromSession() {
  const assignedInput = document.getElementById('assignedUser');
  if (!assignedInput) return;
  if (assignedInput.value.trim()) return;
  const fullName = (window.currentUser?.fullName || '').trim();
  const email = (window.currentUser?.email || '').trim();
  assignedInput.value = fullName || email;
}

async function refreshAssetConnections() {
  const res = await fetch('/api/devices/sync-from-monitoring', { method: 'POST', headers: authHeaders() });
  const data = await res.json();
  if (!res.ok) return alert(data.error || 'Unable to refresh device connections');
  await Promise.all([loadDevices(), loadMonitoring({ force: true, source: 'manual' })]);
  alert(data.message || 'Asset connections refreshed');
}

function bindAssetAutoFill() {
  const ipInput = document.getElementById('ipAddress');
  if (!ipInput || ipInput.dataset.boundAutofill === '1') return;
  ipInput.dataset.boundAutofill = '1';
  ipInput.addEventListener('blur', autoFillDeviceContextByIp);
  ipInput.addEventListener('change', autoFillDeviceContextByIp);
  ipInput.addEventListener('keydown', async (event) => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    await autoFillDeviceContextByIp();
  });
}

function currentDeviceFormBody() {
  return {
    deviceName: (document.getElementById('deviceName')?.value || '').trim(),
    ipAddress: (document.getElementById('ipAddress')?.value || '').trim(),
    department: (document.getElementById('department')?.value || '').trim(),
    assignedUser: (document.getElementById('assignedUser')?.value || '').trim(),
    status: document.getElementById('status')?.value || 'Online'
  };
}

function resetDeviceForm() {
  editingDeviceId = null;
  const registerButton = document.querySelector("button[onclick='registerDevice()']");
  const cancelButton = document.getElementById('cancelDeviceEditBtn');
  if (registerButton) registerButton.textContent = 'Register Device';
  cancelButton?.classList.add('hidden');
  ['deviceName', 'ipAddress', 'department', 'assignedUser'].forEach((id) => {
    const el = document.getElementById(id);
    if (el) el.value = '';
  });
  const statusEl = document.getElementById('status');
  if (statusEl) statusEl.value = 'Unreachable';
  setStatusField('Unreachable');
  seedAssignedUserFromSession();
}

function startEditDevice(device) {
  editingDeviceId = device.id;
  const registerButton = document.querySelector("button[onclick='registerDevice()']");
  const cancelButton = document.getElementById('cancelDeviceEditBtn');
  if (registerButton) registerButton.textContent = 'Save Changes';
  cancelButton?.classList.remove('hidden');

  document.getElementById('deviceName').value = device.deviceName || '';
  document.getElementById('ipAddress').value = device.ipAddress || '';
  document.getElementById('department').value = device.department || '';
  document.getElementById('assignedUser').value = device.assignedUser || '';
  setStatusField(device.status || 'Unreachable');
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function startEditDeviceById(id) {
  const device = deviceRegistry.find((entry) => entry.id === id);
  if (!device) {
    alert('Unable to load the selected device.');
    return;
  }
  startEditDevice(device);
}

async function deleteDevice(id) {
  const label = deviceRegistry.find((entry) => entry.id === id)?.deviceName;
  const confirmed = window.confirm(`Delete asset "${label || `#${id}`}"?`);
  if (!confirmed) return;

  const res = await fetch(`/api/devices/${id}`, { method: 'DELETE', headers: authHeaders() });
  const data = await res.json();
  if (!res.ok) return alert(data.error || 'Failed to delete device.');

  if (editingDeviceId === id) resetDeviceForm();
  await Promise.all([loadDevices(), loadMonitoring({ force: true, source: 'manual' })]);
  alert(data.message || 'Device deleted');
}

async function registerDevice() {
  const deviceNameEl = document.getElementById('deviceName');
  const ipAddressEl = document.getElementById('ipAddress');

  if (!(deviceNameEl?.value || '').trim() && (ipAddressEl?.value || '').trim()) {
    await autoFillDeviceContextByIp();
  }

  const body = currentDeviceFormBody();

  if (!body.deviceName || !body.ipAddress || !body.department || !body.assignedUser || !body.status) {
    return alert('All device fields are required.');
  }

  const endpoint = editingDeviceId ? `/api/devices/${editingDeviceId}` : '/api/devices';
  const method = editingDeviceId ? 'PUT' : 'POST';
  const res = await fetch(endpoint, { method, headers: authHeaders(), body: JSON.stringify(body) });
  const data = await res.json();
  if (!res.ok) return alert(data.error || (editingDeviceId ? 'Failed to update device.' : 'Failed to register device (LAN only policy).'));

  const wasEditing = Boolean(editingDeviceId);
  resetDeviceForm();
  await Promise.all([loadDevices(), loadMonitoring({ force: true, source: 'manual' })]);
  alert(wasEditing ? 'Device updated successfully.' : 'Device registered successfully.');
}

async function loadDevices() {
  const rows = document.getElementById('deviceList');
  if (!rows) return;
  showTableSkeleton(rows, { rowCount: 6, columnCount: 9, hasActions: true });

  const res = await fetch('/api/devices', { headers: authHeaders() });
  const data = await res.json();
  if (!res.ok) {
    renderTableErrorState(rows, 9, data.error || 'Failed to load devices.');
    return;
  }

  const safe = Array.isArray(data)
    ? data
    : Array.isArray(data?.data)
      ? data.data
      : [];
  deviceRegistry = safe;
  const requestedEditId = Number(new URLSearchParams(window.location.search).get('editDevice'));
  if (Number.isInteger(requestedEditId) && requestedEditId > 0) {
    window.history.replaceState({}, '', window.location.pathname);
    const requestedDevice = deviceRegistry.find((device) => Number(device.id) === requestedEditId);
    if (requestedDevice) startEditDevice(requestedDevice);
  }
  clearTableSkeleton(rows);
  if (!safe.length) {
    renderTableEmptyState(rows, 9, 'No devices registered yet.');
    return;
  }
  rows.innerHTML = safe.map(d => `<tr>
    <td>${aitsmEscapeHtml(d.deviceName || '-')}</td>
    <td>${aitsmEscapeHtml(d.ipAddress || '-')}</td>
    <td>${aitsmEscapeHtml(d.department || '-')}</td>
    <td>${aitsmEscapeHtml(d.assignedUser || '-')}</td>
    <td><span class='badge ${statusBadge(d.status)}'>${aitsmEscapeHtml(d.status || '-')}</span></td>
    <td>${d.cpuUsage != null ? `${Number(d.cpuUsage).toFixed(0)}%` : '-'}</td>
    <td>${d.memoryUsage != null ? `${Number(d.memoryUsage).toFixed(0)}%` : '-'}</td>
    <td>${formatLastSeen(d.lastSeen)}</td>
    <td>
      <div class='table-actions'>
        <button class='btn btn-ghost icon-only-btn' type='button' onclick='startEditDeviceById(${Number(d.id)})' aria-label='Edit ${aitsmEscapeHtml(d.deviceName || 'device')}' title='Edit'>
          ✎
        </button>
        <button class='btn btn-ghost icon-only-btn text-danger' type='button' onclick='deleteDevice(${Number(d.id)})' aria-label='Delete ${aitsmEscapeHtml(d.deviceName || 'device')}' title='Delete'>
          🗑
        </button>
      </div>
    </td>
  </tr>`).join('');
}

function wireMonitoringTableFilters() {
  const search = document.getElementById('monitorSearchInput');
  const reachability = document.getElementById('monitorReachabilityFilter');
  const sort = document.getElementById('monitorSortSelect');

  if (search) {
    search.addEventListener('input', () => {
      monitoringFilters.search = search.value || '';
      renderMonitorTable(monitoringDevicesCache);
    });
  }

  if (reachability) {
    reachability.value = monitoringFilters.reachability;
    reachability.addEventListener('change', () => {
      monitoringFilters.reachability = reachability.value || 'all';
      renderMonitorTable(monitoringDevicesCache);
    });
  }

  if (sort) {
    sort.value = monitoringFilters.sort;
    sort.addEventListener('change', () => {
      monitoringFilters.sort = sort.value || 'cpu-desc';
      renderMonitorTable(monitoringDevicesCache);
    });
  }
}

function stopMonitoringAutoRefresh() {
  if (!monitoringAutoRefreshTimer) return;
  window.clearInterval(monitoringAutoRefreshTimer);
  monitoringAutoRefreshTimer = null;
}

document.addEventListener('visibilitychange', () => {
  scheduleMonitoringAutoRefresh();
  if (!document.hidden) loadMonitoring({ source: 'visibility' });
});

document.addEventListener('DOMContentLoaded', () => {
  wireMonitoringTableFilters();
  loadMonitoring({ source: 'initial' });
  loadDevices();
  bindAssetAutoFill();
  seedAssignedUserFromSession();
  scheduleMonitoringAutoRefresh();
  window.setInterval(() => updateMonitoringLiveStatus('live'), 1000);
});

window.addEventListener('beforeunload', stopMonitoringAutoRefresh);
