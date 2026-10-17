
function ensureActionMenuBackdrop() {
  if (document.getElementById('actionMenuBackdrop')) return;
  const backdrop = document.createElement('div');
  backdrop.id = 'actionMenuBackdrop';
  backdrop.className = 'action-menu-backdrop hidden';
  backdrop.addEventListener('click', () => {
    closeAllRowMenus();
    backdrop.classList.add('hidden');
  });
  document.body.appendChild(backdrop);
}

const FOLLOW_UP_HOURS = 24;

function parseDate(v){ const d=new Date(v||''); return Number.isNaN(d.getTime()) ? null : d; }
function hoursSince(v){ const d=parseDate(v); if(!d) return 999; return (Date.now()-d.getTime())/(1000*60*60); }
function normalizeListResponse(payload) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.data)) return payload.data;
  return [];
}

function setCreateTicketMessage(message = '', tone = 'info') {
  const el = document.getElementById('createTicketMessage');
  if (!el) return;
  el.textContent = message;
  el.classList.remove('text-success', 'text-danger');
  if (tone === 'success') el.classList.add('text-success');
  if (tone === 'danger') el.classList.add('text-danger');
}

function closeAllRowMenus() {
  document.querySelectorAll('.row-action-menu').forEach((el) => el.classList.add('hidden'));
  document.getElementById('actionMenuBackdrop')?.classList.add('hidden');
}

function userTicketHref(ticketId) {
  return `/tickets?ticketId=${encodeURIComponent(ticketId)}`;
}

function getRequestedTicketId() {
  const params = new URLSearchParams(window.location.search);
  return params.get('ticketId');
}

function focusRequestedTicket() {
  const requestedId = getRequestedTicketId();
  if (!requestedId) return;
  const row = document.querySelector(`[data-ticket-id="${CSS.escape(requestedId)}"]`);
  if (!row) return;
  row.classList.add('ticket-row-focus');
  row.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function formatSla(ticket) {
  const status = String(ticket.status || '').toLowerCase();
  if (status === 'resolved' || status === 'closed') return 'Completed';
  const minutes = Number(ticket.slaRemainingMinutes);
  if (!Number.isFinite(minutes)) return 'Not available';
  const absolute = Math.abs(minutes);
  const days = Math.floor(absolute / 1440);
  const hours = Math.floor((absolute % 1440) / 60);
  const mins = absolute % 60;
  const duration = [days ? `${days}d` : '', hours ? `${hours}h` : '', `${mins}m`].filter(Boolean).join(' ');
  return ticket.overdue ? `Breached by ${duration}` : `${duration} remaining`;
}

function renderRequestedTicketDetails(tickets) {
  const panel = document.getElementById('selectedTicketDetails');
  const requestedId = Number(getRequestedTicketId());
  const ticket = tickets.find((item) => Number(item.id) === requestedId);
  if (!panel || !ticket) {
    panel?.classList.add('hidden');
    return;
  }
  panel.classList.remove('hidden');
  document.getElementById('selectedTicketTitle').textContent = `Ticket #${ticket.id}: ${ticket.title}`;
  document.getElementById('selectedTicketMeta').textContent = `${ticket.status} • ${ticket.priority} • ${ticket.category}`;
  document.getElementById('selectedTicketDescription').textContent = ticket.description || 'No description provided.';
  document.getElementById('selectedTicketResolution').textContent = ticket.reviewedResolution || 'No reviewed resolution has been posted yet.';
  document.getElementById('selectedTicketResolutionMeta').textContent = ticket.resolutionUpdatedAt
    ? `Last updated ${parseDate(ticket.resolutionUpdatedAt)?.toLocaleString() || ticket.resolutionUpdatedAt}.`
    : '';
  loadMyTicketHistory(ticket.id);
}

function userHistoryStatus(value) {
  const normalized = String(value || '').replaceAll('_', ' ').toLowerCase();
  if (normalized === 'pending') return 'In Progress';
  return normalized ? normalized.replace(/\b\w/g, (char) => char.toUpperCase()) : 'Updated';
}

async function loadMyTicketHistory(ticketId) {
  const host = document.getElementById('selectedTicketActivity');
  if (!host) return;
  host.textContent = 'Loading activity…';
  try {
    const history = await window.aitsmFetchJson(`/api/tickets/${ticketId}/history`);
    const items = (history || []).slice().reverse().map((event) => {
      const item = document.createElement('article');
      item.className = 'ticket-activity-item';
      const title = document.createElement('strong');
      title.textContent = event.previousStatus
        ? `${userHistoryStatus(event.previousStatus)} → ${userHistoryStatus(event.status)}`
        : userHistoryStatus(event.status);
      const meta = document.createElement('div');
      meta.className = 'ticket-activity-meta';
      meta.textContent = `${event.updatedBy || 'System'} • ${parseDate(event.timestamp)?.toLocaleString() || event.timestamp}`;
      item.append(title, meta);
      if (event.resolutionCategory) {
        const category = document.createElement('p');
        category.className = 'small';
        category.textContent = `Resolution category: ${event.resolutionCategory}`;
        item.appendChild(category);
      }
      if (event.userMessage) {
        const message = document.createElement('p');
        message.className = 'ticket-activity-message';
        message.textContent = event.userMessage;
        item.appendChild(message);
      }
      return item;
    });
    host.replaceChildren(...items);
    if (!items.length) host.textContent = 'No activity recorded.';
  } catch (error) {
    host.textContent = error.message || 'Unable to load ticket activity.';
  }
}

function toggleRowActionMenu(event, id) {
  event.stopPropagation();
  const menu = document.getElementById(`ticketRowMenu-${id}`);
  if (!menu) return;
  const trigger = event.currentTarget;
  const open = menu.classList.contains('hidden');
  closeAllRowMenus();
  if (!open) return;
  ensureActionMenuBackdrop();
  document.getElementById('actionMenuBackdrop')?.classList.remove('hidden');
  const rect = trigger.getBoundingClientRect();
  menu.style.position = 'fixed';
  menu.style.top = `${rect.bottom + 4}px`;
  menu.style.left = `${Math.max(8, rect.right - 180)}px`;
  menu.classList.remove('hidden');
}

function actionMenu(ticket){
  const rawStatus = String(ticket.status || '').trim();
  const status = rawStatus.toLowerCase();
  const isResolved = status === 'resolved';
  const isOpen = status === 'open';
  const isPending = status === 'pending' || status === 'in progress' || status === 'follow-up requested';
  const canFollowUp = !['resolved', 'closed', 'cancelled'].includes(status) && hoursSince(ticket.updatedAt) >= FOLLOW_UP_HOURS;
  const items = [`<button type='button' onclick='viewMyTicket(${ticket.id})'>View details</button>`];
  if (isResolved) items.push(`<button type='button' onclick='updateMyTicketStatus(${ticket.id}, "Closed")'>Close ticket</button>`);
  if (isOpen || isPending) items.push(`<button type='button' onclick='updateMyTicketStatus(${ticket.id}, "Cancelled")'>Cancel ticket</button>`);
  if(canFollowUp) items.push(`<button type='button' onclick='updateMyTicketStatus(${ticket.id}, "Follow-up Requested")'>Request follow-up</button>`);
  return `<div class='row-action-wrap'>
    <button class='btn btn-ghost icon-btn' onclick='toggleRowActionMenu(event, ${ticket.id})' title='Actions' aria-label='Actions'>...</button>
    <div id='ticketRowMenu-${ticket.id}' class='row-action-menu hidden'>${items.join('')}</div>
  </div>`;
}

function viewMyTicket(id) {
  closeAllRowMenus();
  location.href = userTicketHref(id);
}

async function createTicket() {
  setCreateTicketMessage('');
  const body = {
    title: title.value.trim(),
    description: description.value.trim(),
    priority: priority.value,
    category: category.value
  };
  if (body.title.length < 5) {
    setCreateTicketMessage('Title must be at least 5 characters.', 'danger');
    return;
  }
  if (body.description.length < 10) {
    setCreateTicketMessage('Description must be at least 10 characters.', 'danger');
    return;
  }
  if (!body.priority || !body.category) {
    setCreateTicketMessage('Priority and category are required.', 'danger');
    return;
  }
  const res = await fetch('/api/tickets', { method: 'POST', headers: authHeaders(), body: JSON.stringify(body) });
  const data = await res.json();
  if (!res.ok) {
    setCreateTicketMessage(data.error || 'Unable to create ticket', 'danger');
    return;
  }
  setCreateTicketMessage(`Ticket #${data.id} created successfully. Redirecting...`, 'success');
  location.href = '/tickets';
}

async function updateMyTicketStatus(id, status){
  closeAllRowMenus();
  const res = await fetch(`/api/tickets/${id}/status`, {
    method:'PUT', headers:authHeaders(), body: JSON.stringify({ status })
  });
  const data = await res.json();
  if(!res.ok) return alert(data.error || 'Unable to update ticket status');
  await loadTickets();
}

async function loadTickets() {
  const rows = document.getElementById('ticketRows');
  if (!rows) return;
  const role = currentRole();
  showTableSkeleton(rows, { rowCount: 6, columnCount: 6, hasActions: true });
  try {
    const tickets = await window.aitsmFetchAllPaginated('/api/tickets');

    clearTableSkeleton(rows);
    if (!tickets.length) {
      renderTableEmptyState(rows, 6, 'No tickets found.');
      return;
    }

    rows.innerHTML = tickets.map(t => {
      const klass = (t.status || 'Open').toLowerCase().replace(/\s+/g, '-');
      const actions = role === 'end-user' ? `<td>${actionMenu(t)}</td>` : '<td>-</td>';
      return `<tr data-ticket-id='${Number(t.id)}'>
        <td><a class='ticket-link' href='${userTicketHref(t.id)}'>#${Number(t.id)}</a></td><td><a class='ticket-link ticket-link-title' href='${userTicketHref(t.id)}' title='${aitsmEscapeHtml(t.title || '-')}'>${aitsmEscapeHtml(t.title || '-')}</a></td><td>${aitsmEscapeHtml(t.priority || '-')}</td><td><span class='badge ${aitsmEscapeHtml(klass)}'>${aitsmEscapeHtml(t.status || '-')}</span></td>
        <td style='color:${t.overdue ? "var(--danger)" : "inherit"}'>${aitsmEscapeHtml(formatSla(t))}</td>
        ${actions}
      </tr>`;
    }).join('');
    focusRequestedTicket();
    renderRequestedTicketDetails(tickets);
  } catch {
    renderTableErrorState(rows, 6, 'Unable to load tickets');
  } finally {
    clearTableSkeleton(rows);
  }
}

document.addEventListener('click', () => closeAllRowMenus());
document.addEventListener('DOMContentLoaded', async () => {
  await window.aitsmReady;
  await loadTickets();
});
