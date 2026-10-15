const inventoryState = {
  page: 1,
  pageSize: 10,
  total: 0,
  selectedId: null,
  loading: false,
  searchDebounceTimer: null
};

function inventoryStatusBadge(status) {
  const normalized = String(status || '').toLowerCase();
  if (normalized === 'online') return 'resolved';
  if (normalized === 'offline') return 'warning';
  if (normalized === 'stale') return 'open';
  return 'in-progress';
}

function inventoryField(label, value, allowHtml = false) {
  const rendered = allowHtml ? (value || 'Unavailable') : aitsmEscapeHtml(value || 'Unavailable');
  return `<div class='inventory-field'><div class='inventory-field-label'>${aitsmEscapeHtml(label)}</div><div>${rendered}</div></div>`;
}

function storageValueInBytes(value) {
  const match = String(value || '').trim().match(/^([\d.,]+)\s*(B|KB|MB|GB|TB)?$/i);
  if (!match) return null;

  const amount = Number(match[1].replace(/,/g, ''));
  if (!Number.isFinite(amount)) return null;

  const unit = (match[2] || 'B').toUpperCase();
  const multipliers = { B: 1, KB: 1024, MB: 1024 ** 2, GB: 1024 ** 3, TB: 1024 ** 4 };
  return amount * multipliers[unit];
}

function formatDriveBreakdown(rawValue) {
  if (!rawValue) return `<p class='drive-breakdown-empty'>No drive details reported.</p>`;

  let parsed = rawValue;
  if (typeof rawValue === 'string') {
    try {
      parsed = JSON.parse(rawValue);
    } catch (_) {
      return `<p class='drive-breakdown-empty'>Drive details could not be read.</p>`;
    }
  }

  const drives = Array.isArray(parsed) ? parsed : (Array.isArray(parsed?.drives) ? parsed.drives : [parsed]);
  const validDrives = drives.filter((drive) => drive && typeof drive === 'object');
  if (!validDrives.length) return `<p class='drive-breakdown-empty'>No drive details reported.</p>`;

  return `<div class='drive-breakdown-list'>${validDrives.map((drive, index) => {
    const name = drive.drive || drive.name || drive.letter || drive.mount || `Drive ${index + 1}`;
    const total = drive.total || 'Unavailable';
    const used = drive.used || 'Unavailable';
    const free = drive.free || 'Unavailable';
    const totalBytes = storageValueInBytes(drive.total);
    const usedBytes = storageValueInBytes(drive.used);
    const freeBytes = storageValueInBytes(drive.free);
    const calculatedUsed = usedBytes ?? (totalBytes !== null && freeBytes !== null ? totalBytes - freeBytes : null);
    const percentUsed = totalBytes > 0 && calculatedUsed !== null
      ? Math.max(0, Math.min(100, Math.round((calculatedUsed / totalBytes) * 100)))
      : null;
    const usage = percentUsed === null
      ? ''
      : `<div class='drive-usage'><div class='drive-usage-heading'><span>Space used</span><strong>${percentUsed}%</strong></div><progress value='${percentUsed}' max='100' aria-label='${aitsmEscapeHtml(name)} storage used: ${percentUsed}%'>${percentUsed}%</progress></div>`;

    return `<article class='drive-breakdown-item'>
      <div class='drive-breakdown-heading'><strong>${aitsmEscapeHtml(name)}</strong><span>${aitsmEscapeHtml(total)} total</span></div>
      ${usage}
      <div class='drive-breakdown-metrics'>
        <span><small>Used</small><strong>${aitsmEscapeHtml(used)}</strong></span>
        <span><small>Free</small><strong>${aitsmEscapeHtml(free)}</strong></span>
      </div>
    </article>`;
  }).join('')}</div>`;
}

function parseListResponse(payload) {
  if (Array.isArray(payload)) return { data: payload, meta: { totalCount: payload.length, pageSize: payload.length, currentPage: 1 } };
  if (Array.isArray(payload?.data)) return payload;
  return { data: [], meta: { totalCount: 0, pageSize: inventoryState.pageSize, currentPage: inventoryState.page } };
}

function buildInventoryQuery() {
  const params = new URLSearchParams();
  const search = (document.getElementById('inventorySearch')?.value || '').trim();
  const status = (document.getElementById('inventoryStatusFilter')?.value || '').trim();
  const department = (document.getElementById('inventoryDepartmentFilter')?.value || '').trim();
  const sortValue = (document.getElementById('inventorySortFilter')?.value || 'lastSeen-desc').trim();
  const [sortByRaw, sortOrderRaw] = sortValue.split('-');
  const sortBy = sortByRaw || 'lastSeen';
  const sortOrder = sortOrderRaw || 'desc';

  if (search) params.set('search', search);
  if (status) params.set('status', status);
  if (department) params.set('department', department);

  params.set('page', String(inventoryState.page));
  params.set('pageSize', String(inventoryState.pageSize));
  params.set('sortBy', sortBy);
  params.set('sortOrder', sortOrder);

  return params.toString();
}

function populateDepartmentFilterOptions(items) {
  const select = document.getElementById('inventoryDepartmentFilter');
  if (!select) return;

  const current = select.value || '';
  const departments = [...new Set((Array.isArray(items) ? items : [])
    .map((item) => String(item.assignedDepartment || '').trim())
    .filter(Boolean))]
    .sort((a, b) => a.localeCompare(b));

  select.innerHTML = [`<option value=''>All</option>`]
    .concat(departments.map((department) => `<option value='${department}'>${department}</option>`))
    .join('');

  if (current && departments.includes(current)) {
    select.value = current;
  }
}

function renderInventoryStats(stats) {
  const host = document.getElementById('inventoryStats');
  if (!host) return;

  const cards = [
    ['Total Assets', stats?.total ?? 0],
    ['Online', stats?.online ?? 0],
    ['Offline', stats?.offline ?? 0],
    ['Stale', stats?.stale ?? 0]
  ];

  host.innerHTML = cards.map(([label, value]) => `<article class='card metric-card'><div class='metric-label'>${label}</div><div class='metric-value'>${value}</div></article>`).join('');
}

function updateInventoryPagination(meta) {
  const label = document.getElementById('inventoryPageLabel');
  const prev = document.getElementById('inventoryPrevBtn');
  const next = document.getElementById('inventoryNextBtn');

  inventoryState.total = Number(meta?.totalCount || 0);
  const currentPage = Number(meta?.currentPage || inventoryState.page || 1);
  const pageSize = Number(meta?.pageSize || inventoryState.pageSize || 10);
  const totalPages = Math.max(1, Math.ceil(inventoryState.total / pageSize));

  if (label) label.textContent = `Page ${currentPage} of ${totalPages}`;
  if (prev) prev.disabled = currentPage <= 1 || inventoryState.loading;
  if (next) next.disabled = currentPage >= totalPages || inventoryState.loading;
}

function renderInventoryRows(items) {
  const rows = document.getElementById('inventoryRows');
  if (!rows) return;

  if (!items.length) {
    renderTableEmptyState(rows, 5, 'No collector-managed devices found for the selected filters.');
    return;
  }

  rows.innerHTML = items.map((asset) => `
    <tr class='inventory-device-row' tabindex='0' role='button' aria-label='View ${aitsmEscapeHtml(asset.deviceName || asset.hostname || 'device')} details' onclick='openInventoryDetail(${Number(asset.id)})' onkeydown='handleInventoryRowKey(event, ${Number(asset.id)})'>
      <td><strong>${aitsmEscapeHtml(asset.deviceName || asset.hostname || 'Unavailable')}</strong><div class='small'>${aitsmEscapeHtml(asset.manufacturer || 'Collector agent')}</div></td>
      <td>${aitsmEscapeHtml(asset.ipAddress || 'Unavailable')}</td>
      <td><span class='badge ${inventoryStatusBadge(asset.status)}'>${aitsmEscapeHtml(asset.status || 'unknown')}</span></td>
      <td>${aitsmEscapeHtml(asset.lastSeenAt || 'Unavailable')}</td>
      <td>${aitsmEscapeHtml(asset.assignedDepartment || 'Unavailable')}</td>
    </tr>
  `).join('');
}

async function loadInventory() {
  const rows = document.getElementById('inventoryRows');
  if (!rows || inventoryState.loading) return;

  inventoryState.loading = true;
  showTableSkeleton(rows, { rowCount: 8, columnCount: 5 });

  try {
    const query = buildInventoryQuery();
    const [listRes, statsRes] = await Promise.all([
      fetch(`/api/inventory?${query}`, { headers: authHeaders() }),
      fetch('/api/inventory/stats', { headers: authHeaders() })
    ]);

    const listPayload = await listRes.json();
    const statsPayload = await statsRes.json();

    if (!listRes.ok) {
      renderTableErrorState(rows, 5, listPayload.error || 'Unable to load inventory assets.');
      return;
    }

    const normalized = parseListResponse(listPayload);
    clearTableSkeleton(rows);
    populateDepartmentFilterOptions(normalized.data || []);
    renderInventoryRows(normalized.data || []);
    updateInventoryPagination(normalized.meta);
    renderInventoryStats(statsRes.ok ? statsPayload : null);
  } catch (error) {
    renderTableErrorState(rows, 5, error.message || 'Unable to load inventory assets.');
  } finally {
    clearTableSkeleton(rows);
    inventoryState.loading = false;
    updateInventoryPagination({ totalCount: inventoryState.total, pageSize: inventoryState.pageSize, currentPage: inventoryState.page });
  }
}

function renderInventorySummaryCards(asset) {
  const host = document.getElementById('inventoryDetailSummary');
  if (!host) return;
  host.innerHTML = [
    ['Processor', asset.processorName || 'Unavailable'],
    ['RAM', asset.installedRam || 'Unavailable'],
    ['Storage Free', asset.storageFree || 'Unavailable'],
    ['GPU', asset.gpuName || 'Unavailable']
  ].map(([label, value]) => `<article class='card metric-card'><div class='metric-label'>${aitsmEscapeHtml(label)}</div><div class='metric-hint'>${aitsmEscapeHtml(value)}</div></article>`).join('');
}

function renderInventoryDetailGroups(asset) {
  const setFields = (id, fields) => {
    const el = document.getElementById(id);
    if (!el) return;
    el.innerHTML = fields.join('');
  };

  setFields('inventoryOverviewFields', [
    inventoryField('Device Name', asset.deviceName),
    inventoryField('Hostname', asset.hostname),
    inventoryField('Full Device Name', asset.fullDeviceName),
    inventoryField('Status', `<span class='badge ${inventoryStatusBadge(asset.status)}'>${aitsmEscapeHtml(asset.status || 'unknown')}</span>`, true),
    inventoryField('Last Seen', asset.lastSeenAt),
    inventoryField('Connection Source', asset.connectionSource)
  ]);

  setFields('inventoryHardwareFields', [
    inventoryField('Manufacturer', asset.manufacturer),
    inventoryField('Model', asset.model),
    inventoryField('System Type', asset.systemType),
    inventoryField('Processor Name', asset.processorName),
    inventoryField('Processor Speed', asset.processorSpeed),
    inventoryField('Installed RAM', asset.installedRam),
    inventoryField('Usable RAM', asset.usableRam),
    inventoryField('RAM Speed', asset.ramSpeed)
  ]);

  setFields('inventoryStorageFields', [
    inventoryField('Storage Total', asset.storageTotal),
    inventoryField('Storage Used', asset.storageUsed),
    inventoryField('Storage Free', asset.storageFree),
    inventoryField('Drive Breakdown', formatDriveBreakdown(asset.storageBreakdownJson), true)
  ]);

  setFields('inventoryGraphicsFields', [
    inventoryField('GPU Name', asset.gpuName),
    inventoryField('GPU Memory', asset.gpuMemory),
    inventoryField('Pen & Touch', asset.penTouchSupport)
  ]);

  setFields('inventoryOsFields', [
    inventoryField('OS Name', asset.osName),
    inventoryField('OS Edition', asset.osEdition),
    inventoryField('OS Version', asset.osVersion),
    inventoryField('OS Build', asset.osBuild),
    inventoryField('Installed On', asset.installedOn),
    inventoryField('Domain / Workgroup', asset.domainOrWorkgroup)
  ]);

  setFields('inventoryNetworkFields', [
    inventoryField('IP Address', asset.ipAddress),
    inventoryField('MAC Address', asset.macAddress),
    inventoryField('Device UUID', asset.deviceUuid),
    inventoryField('Product ID', asset.productId),
    inventoryField('Department', asset.assignedDepartment),
    inventoryField('Assigned User', asset.assignedUser),
    inventoryField('Created At', asset.createdAt),
    inventoryField('Updated At', asset.updatedAt)
  ]);
}

async function openInventoryDetail(id) {
  const modal = document.getElementById('inventoryDetailModal');
  if (!modal) return;

  const title = document.getElementById('inventoryDetailTitle');
  const subtitle = document.getElementById('inventoryDetailSubtitle');
  const notesInput = document.getElementById('inventoryNotesInput');

  inventoryState.selectedId = id;
  if (title) title.textContent = 'Loading asset details...';
  if (subtitle) subtitle.textContent = '';
  if (notesInput) notesInput.value = '';

  modal.classList.remove('hidden');
  modal.classList.add('show');

  try {
    const res = await fetch(`/api/inventory/${id}`, { headers: authHeaders() });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'Unable to load asset details');

    if (title) title.textContent = data.deviceName || data.hostname || `Asset #${id}`;
    if (subtitle) subtitle.textContent = `${data.ipAddress || 'Unavailable'} • ${data.status || 'unknown'}`;
    if (notesInput) notesInput.value = data.notes || '';

    renderInventorySummaryCards(data);
    renderInventoryDetailGroups(data);
  } catch (error) {
    if (title) title.textContent = 'Asset details unavailable';
    if (subtitle) subtitle.textContent = error.message;
  }
}

function handleInventoryRowKey(event, id) {
  if (event.key !== 'Enter' && event.key !== ' ') return;
  event.preventDefault();
  openInventoryDetail(id);
}

function closeInventoryDetail() {
  const modal = document.getElementById('inventoryDetailModal');
  modal?.classList.remove('show');
  modal?.classList.add('hidden');
  inventoryState.selectedId = null;
}

async function saveInventoryNotes() {
  if (!inventoryState.selectedId) return;
  const notes = document.getElementById('inventoryNotesInput')?.value || '';

  const res = await fetch(`/api/inventory/${inventoryState.selectedId}/notes`, {
    method: 'PATCH',
    headers: authHeaders(),
    body: JSON.stringify({ notes })
  });

  const data = await res.json();
  if (!res.ok) {
    alert(data.error || 'Unable to save notes');
    return;
  }

  alert('Notes saved');
  await loadInventory();
}

function bindInventoryEvents() {
  document.getElementById('inventoryRefreshBtn')?.addEventListener('click', () => loadInventory());

  document.getElementById('inventorySearch')?.addEventListener('input', () => {
    inventoryState.page = 1;
    if (inventoryState.searchDebounceTimer) window.clearTimeout(inventoryState.searchDebounceTimer);
    inventoryState.searchDebounceTimer = window.setTimeout(() => loadInventory(), 220);
  });

  ['inventoryStatusFilter', 'inventoryDepartmentFilter', 'inventorySortFilter'].forEach((id) => {
    document.getElementById(id)?.addEventListener('change', () => {
      inventoryState.page = 1;
      loadInventory();
    });
  });

  document.getElementById('inventoryPrevBtn')?.addEventListener('click', () => {
    if (inventoryState.page <= 1) return;
    inventoryState.page -= 1;
    loadInventory();
  });

  document.getElementById('inventoryNextBtn')?.addEventListener('click', () => {
    const totalPages = Math.max(1, Math.ceil(inventoryState.total / inventoryState.pageSize));
    if (inventoryState.page >= totalPages) return;
    inventoryState.page += 1;
    loadInventory();
  });

  document.getElementById('inventorySaveNotesBtn')?.addEventListener('click', saveInventoryNotes);
  document.getElementById('inventoryDetailModal')?.addEventListener('click', (event) => {
    if (event.target?.id === 'inventoryDetailModal') closeInventoryDetail();
  });
}

document.addEventListener('DOMContentLoaded', () => {
  bindInventoryEvents();
  loadInventory();
});
