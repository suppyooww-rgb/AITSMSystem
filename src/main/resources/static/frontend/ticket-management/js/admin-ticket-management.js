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
  const generateButton = document.getElementById('generateTicketAiBtn');
  const isTerminal = ['resolved', 'closed'].includes(normalizeStatus(ticket.status));
  const ownedByMe = Number(ticket.assignedToUserId || 0) === Number(window.currentUser?.id || 0);
  const superAdmin = String(window.currentUser?.role || '').toUpperCase() === 'SUPERADMIN';
  const canEdit = ownedByMe || superAdmin;
  const canGenerate = !isTerminal && canEdit;
  saved?.classList.toggle('hidden', !ticket.reviewedResolution);
  if (savedText) savedText.textContent = ticket.reviewedResolution || '';
  if (savedMeta) savedMeta.textContent = ticket.reviewedResolution
    ? `Last saved ${formatDate(ticket.resolutionUpdatedAt)}${ticket.resolutionUpdatedBy ? ` by ${ticket.resolutionUpdatedBy}` : ''}.`
    : '';
  if (saveButton) {
    saveButton.disabled = isTerminal || !canEdit;
    saveButton.title = isTerminal
      ? 'Reopen the ticket before saving a new resolution.'
      : (!canEdit ? `Only ${ticket.assignedTo || 'the assigned administrator'} can save a resolution.` : '');
  }
  if (generateButton) {
    generateButton.classList.toggle('hidden', !canGenerate);
    generateButton.disabled = !canGenerate;
    generateButton.title = isTerminal
      ? 'Resolved and closed tickets cannot generate new assistance.'
      : (!canEdit ? `Only ${ticket.assignedTo || 'the assigned administrator'} can generate assistance.` : '');
  }
  document.getElementById('adminTicketAiStatus').textContent = isTerminal
    ? 'This ticket is complete. Saved assistance and activity remain available as read-only history.'
    : (!canEdit
      ? `${ticket.assignedTo ? `Handled by ${ticket.assignedTo}. ` : 'This ticket is unassigned. '}Only the assigned administrator can generate or save assistance.`
      : `${superAdmin && !ownedByMe ? 'Super Admin override is available. ' : ''}Generate reviewable assistance. Until the local AI Bridge is available, deterministic fallback guidance is used.`);
  loadAdminTicketHistory(ticket.id);
}

function ticketStatusTransitionLabel(event) {
  const next = statusLabel(event.status);
  if (!event.previousStatus) return next;
  return `${statusLabel(event.previousStatus)} → ${next}`;
}

function buildTicketActivityItem(event, includeInternalNote = true) {
  const item = document.createElement('article');
  item.className = 'ticket-activity-item';
  const title = document.createElement('strong');
  title.textContent = ticketStatusTransitionLabel(event);
  const meta = document.createElement('div');
  meta.className = 'ticket-activity-meta';
  meta.textContent = `${event.updatedBy || 'System'} • ${formatDate(event.timestamp)}`;
  item.append(title, meta);
  if (event.resolutionCategory) {
    const category = document.createElement('p');
    category.className = 'small';
    category.textContent = `Resolution category: ${event.resolutionCategory}`;
    item.appendChild(category);
  }
  if (includeInternalNote && event.note) {
    const note = document.createElement('p');
    note.textContent = `Internal note: ${event.note}`;
    item.appendChild(note);
  }
  if (event.userMessage) {
    const message = document.createElement('p');
    message.className = 'ticket-activity-message';
    message.textContent = `Requester update: ${event.userMessage}`;
    item.appendChild(message);
  }
  return item;
}

async function loadAdminTicketHistory(ticketId) {
  const host = document.getElementById('adminTicketActivity');
  if (!host) return;
  host.replaceChildren(Object.assign(document.createElement('p'), { className: 'small', textContent: 'Loading activity…' }));
  try {
    const history = await window.aitsmFetchJson(`/api/tickets/${ticketId}/history`);
    host.replaceChildren(...(history || []).slice().reverse().map((event) => buildTicketActivityItem(event)));
    if (!host.children.length) host.textContent = 'No activity recorded.';
  } catch (error) {
    host.textContent = error.message || 'Unable to load ticket activity.';
  }
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

function updateTransitionRequirements() {
  const status = document.getElementById('ticketTransitionStatus')?.value;
  const resolving = status === 'Resolved';
  document.getElementById('ticketResolutionCategoryGroup')?.classList.toggle('hidden', !resolving);
  const category = document.getElementById('ticketResolutionCategory');
  const message = document.getElementById('ticketRequesterMessage');
  const required = document.getElementById('ticketRequesterMessageRequired');
  if (category) category.required = resolving;
  if (message) message.required = resolving;
  if (required) required.textContent = resolving ? '*' : '(optional)';
}

function openTicketTransition(id, status) {
  closeAdminRowMenus();
  const ticket = adminTicketState.tickets.find((item) => Number(item.id) === Number(id));
  const dialog = document.getElementById('ticketTransitionDialog');
  if (!ticket || !dialog) return;
  const superAdmin = String(window.currentUser?.role || '').toUpperCase() === 'SUPERADMIN';
  if (!superAdmin && Number(ticket.assignedToUserId) !== Number(window.currentUser?.id)) {
    window.alert(ticket.assignedTo ? `This ticket is being handled by ${ticket.assignedTo}.` : 'Take this ticket before updating its status.');
    return;
  }
  document.getElementById('ticketTransitionId').value = String(id);
  document.getElementById('ticketTransitionStatus').value = status;
  document.getElementById('ticketTransitionNote').value = '';
  document.getElementById('ticketRequesterMessage').value = '';
  document.getElementById('ticketResolutionCategory').value = '';
  document.getElementById('ticketNotifyRequester').checked = true;
  document.getElementById('ticketTransitionError').textContent = '';
  document.getElementById('ticketTransitionMeta').textContent = `Ticket #${id}: ${ticket.title} • Currently ${statusLabel(ticket.status)}`;
  updateTransitionRequirements();
  dialog.showModal();
  document.getElementById('ticketTransitionNote').focus();
}

async function claimTicket(id) {
  closeAdminRowMenus();
  if (!window.confirm(`Take ownership of ticket #${id} and set it In Progress?`)) return;
  try {
    await window.aitsmFetchJson(`/api/tickets/${id}/claim`, { method: 'POST' });
    await loadAdminTicketManagement();
  } catch (error) {
    window.alert(error.message || 'Unable to take this ticket.');
    await loadAdminTicketManagement();
  }
}

function openTicketAssignment(id, mode) {
  closeAdminRowMenus();
  const ticket = adminTicketState.tickets.find((item) => Number(item.id) === Number(id));
  const dialog = document.getElementById('ticketAssignmentDialog');
  if (!ticket || !dialog) return;
  const reassigning = mode === 'reassign';
  document.getElementById('ticketAssignmentId').value = String(id);
  document.getElementById('ticketAssignmentMode').value = mode;
  document.getElementById('ticketAssignmentTitle').textContent = reassigning ? 'Reassign ticket' : 'Release ticket';
  document.getElementById('ticketAssignmentMeta').textContent = `Ticket #${id}: ${ticket.title} • ${ticket.assignedTo || 'Unassigned'}`;
  document.getElementById('ticketAssignmentNote').value = '';
  document.getElementById('ticketAssignmentError').textContent = '';
  document.getElementById('ticketAssigneeGroup').classList.toggle('hidden', !reassigning);
  const select = document.getElementById('ticketAssigneeUserId');
  if (reassigning) {
    const eligible = [...adminTicketState.usersById.values()].filter((user) =>
      ['ADMIN', 'IT_ADMIN', 'SUPERADMIN', 'SUPPORT_AGENT'].includes(String(user.role || '').toUpperCase())
    );
    select.innerHTML = eligible.map((user) => `<option value='${Number(user.id)}'>${escapeHtml(user.fullName)} (${escapeHtml(String(user.role).replaceAll('_', ' '))})</option>`).join('');
    select.value = String(window.currentUser?.id || eligible[0]?.id || '');
  }
  dialog.showModal();
  document.getElementById('ticketAssignmentNote').focus();
}

function closeTicketAssignment() {
  document.getElementById('ticketAssignmentDialog')?.close();
}

async function submitTicketAssignment(event) {
  event.preventDefault();
  const id = Number(document.getElementById('ticketAssignmentId').value);
  const mode = document.getElementById('ticketAssignmentMode').value;
  const note = document.getElementById('ticketAssignmentNote').value.trim();
  const errorHost = document.getElementById('ticketAssignmentError');
  const submit = document.getElementById('submitTicketAssignmentBtn');
  if (note.length < 5) {
    errorHost.textContent = 'Enter a reason of at least 5 characters.';
    return;
  }
  submit.disabled = true;
  errorHost.textContent = '';
  try {
    if (mode === 'reassign') {
      const assigneeUserId = Number(document.getElementById('ticketAssigneeUserId').value);
      await window.aitsmFetchJson(`/api/tickets/${id}/assignment`, {
        method: 'PUT', body: JSON.stringify({ assigneeUserId, note })
      });
    } else {
      await window.aitsmFetchJson(`/api/tickets/${id}/release`, {
        method: 'POST', body: JSON.stringify({ note })
      });
    }
    closeTicketAssignment();
    await loadAdminTicketManagement();
  } catch (error) {
    errorHost.textContent = error.message || 'Unable to change ticket ownership.';
  } finally {
    submit.disabled = false;
  }
}

function closeTicketTransition() {
  document.getElementById('ticketTransitionDialog')?.close();
}

async function submitTicketTransition(event) {
  event.preventDefault();
  const id = Number(document.getElementById('ticketTransitionId').value);
  const status = document.getElementById('ticketTransitionStatus').value;
  const note = document.getElementById('ticketTransitionNote').value.trim();
  const userMessage = document.getElementById('ticketRequesterMessage').value.trim();
  const resolutionCategory = document.getElementById('ticketResolutionCategory').value;
  const notifyRequester = document.getElementById('ticketNotifyRequester').checked;
  const errorHost = document.getElementById('ticketTransitionError');
  const submit = document.getElementById('submitTicketTransitionBtn');
  if (note.length < 5) {
    errorHost.textContent = 'Enter an internal work note of at least 5 characters.';
    return;
  }
  if (status === 'Resolved' && (userMessage.length < 10 || !resolutionCategory)) {
    errorHost.textContent = 'Resolving requires a resolution category and requester-visible resolution of at least 10 characters.';
    return;
  }
  submit.disabled = true;
  errorHost.textContent = '';
  try {
    await window.aitsmFetchJson(`/api/tickets/${id}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status, note, userMessage: userMessage || null, resolutionCategory: resolutionCategory || null, notifyRequester })
    });
    closeTicketTransition();
    await loadAdminTicketManagement();
  } catch (error) {
    errorHost.textContent = error.message || 'Unable to update ticket status.';
  } finally {
    submit.disabled = false;
  }
}

function actionMenu(ticket) {
  const current = normalizeStatus(ticket.status);
  const actions = [`<button type='button' onclick='location.href="${adminTicketHref(ticket.id)}"'>View details</button>`];
  const currentUserId = Number(window.currentUser?.id || 0);
  const ownerId = Number(ticket.assignedToUserId || 0);
  const ownedByMe = ownerId > 0 && ownerId === currentUserId;
  const unassigned = ownerId === 0;
  const superAdmin = String(window.currentUser?.role || '').toUpperCase() === 'SUPERADMIN';
  const canEdit = ownedByMe || superAdmin;
  if (unassigned && current === 'open') actions.push(`<button type='button' onclick='claimTicket(${ticket.id})'>Take ticket</button>`);
  if (canEdit) {
    if (current !== 'open') actions.push(`<button type='button' onclick='openTicketTransition(${ticket.id}, "Open")'>Reopen ticket</button>`);
    if (!['resolved', 'closed'].includes(current)) actions.push(`<button type='button' onclick='openTicketTransition(${ticket.id}, "Resolved")'>Resolve ticket</button>`);
    if (current === 'resolved') actions.push(`<button type='button' onclick='openTicketTransition(${ticket.id}, "Closed")'>Close ticket</button>`);
    if (ownedByMe && !['resolved', 'closed'].includes(current)) actions.push(`<button type='button' onclick='openTicketAssignment(${ticket.id}, "release")'>Release ticket</button>`);
  }
  if (superAdmin && ownerId > 0 && !ownedByMe && !['resolved', 'closed'].includes(current)) {
    actions.push(`<button type='button' onclick='openTicketAssignment(${ticket.id}, "reassign")'>Reassign ticket</button>`);
  }
  const completedMessage = ['resolved', 'closed'].includes(current) && unassigned
    ? `<span class='small'>Legacy ${statusLabel(ticket.status)} ticket${superAdmin ? ' · Super Admin override available' : ' · read-only'}</span>`
    : '';
  const lockMessage = !unassigned && !ownedByMe
    ? `<span class='small'>Handled by ${escapeHtml(ticket.assignedTo || 'another administrator')}</span>`
    : completedMessage;
  return `<div class='row-action-wrap'>
    <button class='btn btn-ghost icon-btn' onclick='toggleAdminRowMenu(event, ${ticket.id})' title='Actions' aria-label='Actions'>...</button>
    <div id='adminTicketRowMenu-${ticket.id}' class='row-action-menu hidden'>
      ${lockMessage}${actions.join('') || (!lockMessage ? '<span class="small">No status actions available.</span>' : '')}
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
      <td>${formatDate(ticket.updatedAt || ticket.createdAt)}${ticket.assignedTo ? `<div class='small'>Owner: ${escapeHtml(ticket.assignedTo)}</div>` : `<div class='small'>Unassigned</div>`}</td>
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
    await window.aitsmReady;
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
  document.getElementById('ticketTransitionForm')?.addEventListener('submit', submitTicketTransition);
  document.getElementById('ticketTransitionStatus')?.addEventListener('change', updateTransitionRequirements);
  document.getElementById('closeTicketTransitionBtn')?.addEventListener('click', closeTicketTransition);
  document.getElementById('cancelTicketTransitionBtn')?.addEventListener('click', closeTicketTransition);
  document.getElementById('ticketAssignmentForm')?.addEventListener('submit', submitTicketAssignment);
  document.getElementById('closeTicketAssignmentBtn')?.addEventListener('click', closeTicketAssignment);
  document.getElementById('cancelTicketAssignmentBtn')?.addEventListener('click', closeTicketAssignment);
  loadAdminTicketManagement();
});
