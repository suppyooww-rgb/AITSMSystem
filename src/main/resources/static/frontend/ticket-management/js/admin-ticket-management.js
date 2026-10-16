const adminTicketState = {
  tickets: [],
  usersById: new Map(),
  filters: {
    search: '',
    status: 'all',
    priority: 'all',
    sort: 'updated-desc'
  }
};

function ensureActionMenuBackdrop() {
  if (document.getElementById('actionMenuBackdrop')) return;
  const backdrop = document.createElement('div');
  backdrop.id = 'actionMenuBackdrop';
  backdrop.className = 'action-menu-backdrop hidden';
  backdrop.addEventListener('click', () => {
    closeAdminRowMenus();
    backdrop.classList.add('hidden');
  });
  document.body.appendChild(backdrop);
}

function normalizeTicketList(payload) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.data)) return payload.data;
  return [];
}

function parseDate(value) {
  const date = new Date(value || '');
  return Number.isNaN(date.getTime()) ? null : date;
}

function formatDate(value) {
  const parsed = parseDate(value);
  return parsed ? parsed.toLocaleString() : (value || '-');
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function normalizeStatus(value) {
  const status = String(value || '').trim().replaceAll('_', ' ').toLowerCase();
  if (status === 'pending' || status === 'in progress' || status === 'follow-up requested' || status === 'follow up requested') {
    return 'in progress';
  }
  if (status === 'resolved') return 'resolved';
  if (status === 'closed' || status === 'cancelled' || status === 'canceled') return 'closed';
  return 'open';
}

function statusLabel(value) {
  const normalized = normalizeStatus(value);
  if (normalized === 'in progress') return 'In Progress';
  if (normalized === 'resolved') return 'Resolved';
  if (normalized === 'closed') return 'Closed';
  return 'Open';
}

function badge(status) {
  const normalized = normalizeStatus(status);
  if (normalized === 'resolved' || normalized === 'closed') return 'resolved';
  if (normalized === 'in progress') return 'in-progress';
  return 'open';
}

function closeAdminRowMenus() {
  document.querySelectorAll('.row-action-menu').forEach((el) => el.classList.add('hidden'));
  document.getElementById('actionMenuBackdrop')?.classList.add('hidden');
}

function adminTicketHref(ticketId) {
  return `/ticket-management?ticketId=${encodeURIComponent(ticketId)}`;
}

function getRequestedAdminTicketId() {
  const params = new URLSearchParams(window.location.search);
  return params.get('ticketId');
}

function focusRequestedAdminTicket() {
  const requestedId = getRequestedAdminTicketId();
  if (!requestedId) return;

  const row = document.querySelector(`[data-ticket-id="${CSS.escape(requestedId)}"]`);
  if (!row) return;

  row.classList.add('ticket-row-focus');
  row.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function renderAdminTicketAiContext() {
  const panel = document.getElementById('adminTicketAiPanel');
  const requestedId = Number(getRequestedAdminTicketId());
  const ticket = adminTicketState.tickets.find((item) => Number(item.id) === requestedId);
  if (!panel || !ticket) {
    panel?.classList.add('hidden');
    return;
  }
  panel.classList.remove('hidden');
  document.getElementById('adminTicketAiTitle').textContent = `AI assistance for ticket #${ticket.id}`;
  document.getElementById('adminTicketAiMeta').textContent = `${ticket.title} • ${statusLabel(ticket.status)} • ${ticket.priority}`;
  document.getElementById('adminTicketAiResults').classList.add('hidden');
  const saved = document.getElementById('adminTicketSavedResolution');
  const savedText = document.getElementById('adminTicketSavedResolutionText');
  const savedMeta = document.getElementById('adminTicketSavedResolutionMeta');
  const saveButton = document.getElementById('saveTicketAiDraftBtn');
  const isClosed = normalizeStatus(ticket.status) === 'closed';
  saved?.classList.toggle('hidden', !ticket.reviewedResolution);
  if (savedText) savedText.textContent = ticket.reviewedResolution || '';
  if (savedMeta) savedMeta.textContent = ticket.reviewedResolution
    ? `Last saved ${formatDate(ticket.resolutionUpdatedAt)}${ticket.resolutionUpdatedBy ? ` by ${ticket.resolutionUpdatedBy}` : ''}.`
    : '';
  if (saveButton) {
    saveButton.disabled = isClosed;
    saveButton.title = isClosed ? 'Reopen the ticket before saving a new resolution.' : '';
  }
  document.getElementById('adminTicketAiStatus').textContent = isClosed
    ? 'This ticket is closed. You can generate and copy assistance, but reopen it before saving a new resolution.'
    : 'Generate reviewable assistance. Until the local AI Bridge is available, deterministic fallback guidance is used.';
}

async function generateAdminTicketAssistance() {
  const ticketId = Number(getRequestedAdminTicketId());
  const button = document.getElementById('generateTicketAiBtn');
  const status = document.getElementById('adminTicketAiStatus');
  const results = document.getElementById('adminTicketAiResults');
  if (!(ticketId > 0) || !button || !status || !results) return;
  button.disabled = true;
  status.classList.remove('text-danger', 'text-success');
  status.textContent = 'Preparing a reviewable summary and resolution draft…';
  results.classList.add('hidden');
  try {
    const response = await fetch(`/api/ai/tickets/${ticketId}/assist`, { method: 'POST', headers: authHeaders() });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || data.message || 'Unable to generate ticket assistance');
    document.getElementById('adminTicketAiSummary').textContent = data.summary || 'No summary was generated.';
    document.getElementById('adminTicketAiDraft').value = data.resolutionDraft || '';
    document.getElementById('adminTicketAiAdvisory').textContent = data.advisory || 'Review this draft before use.';
    const checklist = document.getElementById('adminTicketAiChecklist');
    checklist.replaceChildren();
    (data.troubleshootingChecklist || []).forEach((step) => {
      const item = document.createElement('li');
      item.textContent = step;
      checklist.appendChild(item);
    });
    results.classList.remove('hidden');
    status.classList.add(data.source === 'fallback' ? 'text-danger' : 'text-success');
    status.textContent = data.source === 'fallback'
      ? `Fallback guidance shown: ${data.fallbackReason || 'The local AI Bridge is not available.'}`
      : 'Local AI assistance generated. Review and edit it before use.';
  } catch (error) {
    status.classList.add('text-danger');
    status.textContent = error.message;
  } finally {
    button.disabled = false;
  }
}

async function copyAdminTicketDraft() {
  const draft = document.getElementById('adminTicketAiDraft')?.value.trim();
  const status = document.getElementById('adminTicketAiStatus');
  if (!draft || !status) return;
  try {
    await navigator.clipboard.writeText(draft);
    status.classList.remove('text-danger');
    status.classList.add('text-success');
    status.textContent = 'The reviewed draft was copied. No ticket data was changed.';
  } catch {
    status.classList.add('text-danger');
    status.textContent = 'Copy failed. Select the draft text and copy it manually.';
  }
}

async function saveAdminTicketDraft() {
  const ticketId = Number(getRequestedAdminTicketId());
  const draft = document.getElementById('adminTicketAiDraft')?.value.trim();
  const status = document.getElementById('adminTicketAiStatus');
  const button = document.getElementById('saveTicketAiDraftBtn');
  if (!(ticketId > 0) || !status || !button) return;
  if (!draft) {
    status.classList.add('text-danger');
    status.textContent = 'Review and enter a resolution before saving.';
    return;
  }
  if (!window.confirm(`Save this reviewed resolution to ticket #${ticketId}?`)) return;

  button.disabled = true;
  status.classList.remove('text-danger', 'text-success');
  status.textContent = 'Saving the reviewed resolution…';
  try {
    const response = await fetch(`/api/tickets/${ticketId}/resolution`, {
      method: 'PUT',
      headers: authHeaders(),
      body: JSON.stringify({ resolution: draft })
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || data.message || 'Unable to save the resolution');
    const index = adminTicketState.tickets.findIndex((ticket) => Number(ticket.id) === ticketId);
    if (index >= 0) adminTicketState.tickets[index] = data;
    renderAdminTicketAiContext();
    document.getElementById('adminTicketAiResults').classList.remove('hidden');
    status.classList.add('text-success');
    status.textContent = 'Reviewed resolution saved to the ticket.';
  } catch (error) {
    status.classList.add('text-danger');
    status.textContent = error.message;
    button.disabled = false;
  }
}

function toggleAdminRowMenu(event, id) {
  event.stopPropagation();
  const menu = document.getElementById(`adminTicketRowMenu-${id}`);
  if (!menu) return;
  const trigger = event.currentTarget;
  const open = menu.classList.contains('hidden');
  closeAdminRowMenus();
  if (!open) return;
  ensureActionMenuBackdrop();
  document.getElementById('actionMenuBackdrop')?.classList.remove('hidden');
  const rect = trigger.getBoundingClientRect();
  menu.style.position = 'fixed';
  menu.style.top = `${rect.bottom + 4}px`;
  menu.style.left = `${Math.max(8, rect.right - 180)}px`;
  menu.classList.remove('hidden');
}

async function updateTicketStatusAdmin(id, status) {
  closeAdminRowMenus();
  const res = await fetch(`/api/tickets/${id}/status`, {
    method: 'PUT',
    headers: authHeaders(),
    body: JSON.stringify({ status })
  });
  const data = await res.json();
  if (!res.ok) return alert(data.error || 'Unable to update status');
  await loadAdminTicketManagement();
}

function actionMenu(ticket) {
  return `<div class='row-action-wrap'>
    <button class='btn btn-ghost icon-btn' onclick='toggleAdminRowMenu(event, ${ticket.id})' title='Actions' aria-label='Actions'>...</button>
    <div id='adminTicketRowMenu-${ticket.id}' class='row-action-menu hidden'>
      <button type='button' onclick='updateTicketStatusAdmin(${ticket.id}, "Open")'>Set Open</button>
      <button type='button' onclick='updateTicketStatusAdmin(${ticket.id}, "In Progress")'>Set In Progress</button>
      <button type='button' onclick='updateTicketStatusAdmin(${ticket.id}, "Resolved")'>Set Resolved</button>
      <button type='button' onclick='updateTicketStatusAdmin(${ticket.id}, "Closed")'>Set Closed</button>
    </div>
  </div>`;
}

function requesterLabel(userId) {
  const user = adminTicketState.usersById.get(Number(userId));
  if (!user) return `User #${userId}`;
  const fullName = user.fullName || `User #${userId}`;
  return user.email ? `${fullName} (${user.email})` : fullName;
}

function priorityRank(priority) {
  const order = { Critical: 4, High: 3, Medium: 2, Low: 1 };
  return order[String(priority || '').trim()] || 0;
}

function applyTicketFilters() {
  const query = adminTicketState.filters.search.trim().toLowerCase();
  const statusFilter = adminTicketState.filters.status;
  const priorityFilter = adminTicketState.filters.priority;

  const filtered = adminTicketState.tickets.filter((ticket) => {
    const normalizedStatus = normalizeStatus(ticket.status);
    const matchesStatus = statusFilter === 'all' || normalizedStatus === statusFilter;
    const matchesPriority = priorityFilter === 'all' || String(ticket.priority || '').trim() === priorityFilter;

    if (!query) return matchesStatus && matchesPriority;

    const tokens = [
      ticket.id,
      ticket.title,
      ticket.category,
      ticket.priority,
      ticket.status,
      requesterLabel(ticket.userId)
    ].map((value) => String(value || '').toLowerCase());

    return matchesStatus && matchesPriority && tokens.some((token) => token.includes(query));
  });

  const sorted = [...filtered];
  const sortValue = adminTicketState.filters.sort;

  sorted.sort((a, b) => {
    if (sortValue === 'priority-desc') return priorityRank(b.priority) - priorityRank(a.priority);
    if (sortValue === 'priority-asc') return priorityRank(a.priority) - priorityRank(b.priority);
    if (sortValue === 'id-asc') return Number(a.id || 0) - Number(b.id || 0);
    if (sortValue === 'id-desc') return Number(b.id || 0) - Number(a.id || 0);

    const aTime = parseDate(a.updatedAt || a.createdAt)?.getTime() || 0;
    const bTime = parseDate(b.updatedAt || b.createdAt)?.getTime() || 0;
    if (sortValue === 'updated-asc') return aTime - bTime;
    return bTime - aTime;
  });

  return sorted;
}

function renderAdminTicketCount(count, total) {
  const el = document.getElementById('adminTicketCount');
  if (!el) return;
  el.textContent = `${count} shown / ${total} total`;
}

function renderAdminTicketRows() {
  const rows = document.getElementById('adminTicketRows');
  if (!rows) return;

  const visible = applyTicketFilters();
  renderAdminTicketCount(visible.length, adminTicketState.tickets.length);

  if (!visible.length) {
    renderTableEmptyState(rows, 9, 'No tickets matched the selected filters.');
    return;
  }

  rows.innerHTML = visible.map((ticket) => `
    <tr data-ticket-id='${ticket.id}'>
      <td><a class='ticket-link' href='${adminTicketHref(ticket.id)}'>#${ticket.id}</a></td>
      <td class='monitor-text-wrap' title='${escapeHtml(requesterLabel(ticket.userId))}'>${escapeHtml(requesterLabel(ticket.userId))}</td>
      <td><a class='ticket-link ticket-link-title' href='${adminTicketHref(ticket.id)}' title='${escapeHtml(ticket.title || '-')}'>${escapeHtml(ticket.title || '-')}</a></td>
      <td>${escapeHtml(ticket.category || '-')}</td>
      <td>${escapeHtml(ticket.priority || '-')}</td>
      <td><span class='badge ${badge(ticket.status)}'>${statusLabel(ticket.status)}</span></td>
      <td><span class='badge ${ticket.slaRiskLevel === 'CRITICAL' || ticket.slaRiskLevel === 'HIGH' ? 'warning' : 'resolved'}' title='${escapeHtml((ticket.slaRiskReasons || []).join('; '))}'>${escapeHtml(ticket.slaRiskLevel || 'LOW')} ${Number(ticket.slaRiskScore || 0)}%</span></td>
      <td>${formatDate(ticket.updatedAt || ticket.createdAt)}</td>
      <td>${actionMenu(ticket)}</td>
    </tr>
  `).join('');

  focusRequestedAdminTicket();
}

async function loadAdminTicketManagement() {
  const rows = document.getElementById('adminTicketRows');
  if (!rows) return;

  showTableSkeleton(rows, { rowCount: 8, columnCount: 9, hasActions: true });

  try {
    const [ticketsData, usersRes] = await Promise.all([
      window.aitsmFetchAllPaginated('/api/tickets'),
      fetch('/api/users', { headers: authHeaders() })
    ]);

    const usersData = await usersRes.json();

    if (!usersRes.ok) {
      renderTableErrorState(rows, 9, usersData.error || 'Unable to load requester data');
      return;
    }

    adminTicketState.tickets = ticketsData;
    adminTicketState.usersById = new Map((Array.isArray(usersData) ? usersData : []).map((user) => [Number(user.id), user]));

    clearTableSkeleton(rows);
    renderAdminTicketRows();
    renderAdminTicketAiContext();
  } catch {
    renderTableErrorState(rows, 9, 'Unable to load tickets');
  } finally {
    clearTableSkeleton(rows);
  }
}

function wireAdminTicketFilters() {
  const bind = (id, key, eventName = 'change') => {
    const el = document.getElementById(id);
    if (!el) return;
    el.addEventListener(eventName, () => {
      adminTicketState.filters[key] = el.value || 'all';
      renderAdminTicketRows();
    });
  };

  bind('adminTicketStatusFilter', 'status');
  bind('adminTicketPriorityFilter', 'priority');
  bind('adminTicketSort', 'sort');

  const search = document.getElementById('adminTicketSearch');
  if (search) {
    search.addEventListener('input', () => {
      adminTicketState.filters.search = search.value || '';
      renderAdminTicketRows();
    });
  }
}

document.addEventListener('click', () => closeAdminRowMenus());
document.addEventListener('DOMContentLoaded', () => {
  wireAdminTicketFilters();
  document.getElementById('generateTicketAiBtn')?.addEventListener('click', generateAdminTicketAssistance);
  document.getElementById('saveTicketAiDraftBtn')?.addEventListener('click', saveAdminTicketDraft);
  document.getElementById('copyTicketAiDraftBtn')?.addEventListener('click', copyAdminTicketDraft);
  loadAdminTicketManagement();
});
