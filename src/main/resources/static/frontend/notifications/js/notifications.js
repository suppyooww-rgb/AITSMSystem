let notificationHistoryPage = 1;
let notificationHistoryPayload = null;

function historyEscape(value) {
  return String(value ?? '').replace(/[&<>'"]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[char]));
}

function historyDestination(item, role) {
  const type = String(item.type || '').toLowerCase();
  if (type.startsWith('ticket_') && item.relatedTicketId) return `${role === 'admin' || role === 'superadmin' ? '/ticket-management' : '/tickets'}?ticketId=${encodeURIComponent(item.relatedTicketId)}`;
  if (type.startsWith('security_')) return '/security';
  if (type.startsWith('knowledge_')) return role === 'admin' || role === 'superadmin' ? '/knowledge' : '/knowledge-library';
  if (type.startsWith('profile_photo_') || ['account_approved', 'account_rejected', 'account_created', 'role_changed', 'account_state_changed'].includes(type)) return '/profile';
  if (item.relatedUserId && ['colleague_added', 'colleague_accepted', 'colleague_request'].includes(type)) return `/profile?id=${encodeURIComponent(item.relatedUserId)}`;
  return null;
}

async function historyRequest(url, options = {}) {
  if (typeof window.aitsmFetchJson === 'function') return window.aitsmFetchJson(url, options);
  const response = await fetch(url, { credentials: 'same-origin', ...options, headers: { ...authHeaders(), ...(options.headers || {}) } });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || payload.message || 'Notification request failed.');
  return payload;
}

async function loadNotificationHistory(page = notificationHistoryPage) {
  const status = document.getElementById('notificationStatus').value;
  const type = document.getElementById('notificationType').value.trim();
  const pageSize = document.getElementById('notificationPageSize').value;
  const params = new URLSearchParams({ page: String(page), pageSize, status });
  if (type) params.set('type', type);
  const statusNode = document.getElementById('notificationHistoryStatus');
  statusNode.textContent = 'Loading…';
  try {
    notificationHistoryPayload = await historyRequest(`/api/notifications?${params}`);
    notificationHistoryPage = notificationHistoryPayload.page;
    const me = await historyRequest('/api/auth/me');
    const role = String(me.role || '').toLowerCase();
    const host = document.getElementById('notificationHistory');
    host.innerHTML = notificationHistoryPayload.items.length ? notificationHistoryPayload.items.map(item => {
      const destination = historyDestination(item, role);
      return `<article class="notification-item${item.isRead ? '' : ' is-unread'}"><div class="notification-line"><span class="badge ${item.isRead ? 'resolved' : 'in-progress'}">${historyEscape(item.isRead ? 'Read' : 'Unread')}</span><time class="small">${historyEscape(new Date(item.createdAt).toLocaleString())}</time></div><h3 class="section-title">${historyEscape(item.title)}</h3><p>${historyEscape(item.message)}</p><div class="inline-actions"><span class="small">${historyEscape(item.type)}</span>${destination ? `<button class="btn btn-ghost" type="button" onclick="openHistoryNotification(${Number(item.id)}, '${historyEscape(destination)}')">Open</button>` : (!item.isRead ? `<button class="btn btn-ghost" type="button" onclick="readHistoryNotification(${Number(item.id)})">Mark read</button>` : '')}</div></article>`;
    }).join('') : '<div class="empty-state"><h3>No notifications found</h3><p>Try a different status or event type.</p></div>';
    const totalPages = Math.max(1, Number(notificationHistoryPayload.totalPages) || 1);
    document.getElementById('notificationPageSummary').textContent = `Page ${notificationHistoryPage} of ${totalPages} · ${notificationHistoryPayload.total} notifications`;
    document.getElementById('notificationPrevious').disabled = notificationHistoryPage <= 1;
    document.getElementById('notificationNext').disabled = !notificationHistoryPayload.hasNext;
    statusNode.textContent = `Showing ${notificationHistoryPayload.items.length} notifications.`;
  } catch (error) {
    statusNode.textContent = error.message || 'Unable to load notification history.';
    statusNode.classList.add('text-danger');
  }
}

async function readHistoryNotification(id) {
  await historyRequest(`/api/notifications/${encodeURIComponent(id)}/read`, { method: 'PATCH' });
  await loadNotificationHistory();
}

async function openHistoryNotification(id, destination) {
  await historyRequest(`/api/notifications/${encodeURIComponent(id)}/read`, { method: 'PATCH' });
  location.assign(destination);
}

document.addEventListener('DOMContentLoaded', async () => {
  await window.aitsmReady;
  document.getElementById('notificationStatus').addEventListener('change', () => loadNotificationHistory(1));
  document.getElementById('notificationType').addEventListener('change', () => loadNotificationHistory(1));
  document.getElementById('notificationPageSize').addEventListener('change', () => loadNotificationHistory(1));
  document.getElementById('notificationPrevious').addEventListener('click', () => loadNotificationHistory(notificationHistoryPage - 1));
  document.getElementById('notificationNext').addEventListener('click', () => loadNotificationHistory(notificationHistoryPage + 1));
  document.getElementById('markAllHistoryRead').addEventListener('click', async () => { await historyRequest('/api/notifications/read-all', { method: 'PATCH' }); await loadNotificationHistory(); });
  await loadNotificationHistory();
});
