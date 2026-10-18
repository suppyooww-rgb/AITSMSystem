(function createNotificationCenter(global) {
  if (global.AITSMNotifications) return;

  const DEFAULT_AVATAR = '/profile/assets/default-avatar.png';
  const centers = new Map();

  function cookie(name) {
    const prefix = `${name}=`;
    return document.cookie.split(';').map((part) => part.trim()).find((part) => part.startsWith(prefix))?.slice(prefix.length) || '';
  }

  function headers(mutating = false) {
    const result = { Accept: 'application/json' };
    if (mutating) result['X-CSRF-Token'] = decodeURIComponent(cookie('aitsm_csrf'));
    return result;
  }

  async function recoverCsrf() {
    const response = await fetch('/api/auth/csrf-token', {
      method: 'GET', credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' }
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok || !payload.csrfToken) throw new Error(payload.error || payload.message || 'Unable to refresh session security.');
    return payload.csrfToken;
  }

  async function request(url, options = {}, allowRecovery = true) {
    if (typeof global.aitsmFetchJson === 'function') {
      try {
        return await global.aitsmFetchJson(url, options);
      } catch (error) {
        const mutating = options.method && options.method !== 'GET';
        if (!allowRecovery || !mutating || error?.status !== 403) throw error;
        const csrfToken = await recoverCsrf();
        return global.aitsmFetchJson(url, {
          ...options,
          headers: { ...(options.headers || {}), 'X-CSRF-Token': csrfToken }
        });
      }
    }
    const response = await fetch(url, {
      credentials: 'same-origin',
      cache: 'no-store',
      ...options,
      headers: { ...headers(options.method && options.method !== 'GET'), ...(options.headers || {}) }
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      const error = new Error(payload.error || payload.message || `Request failed (${response.status}).`);
      error.status = response.status;
      const mutating = options.method && options.method !== 'GET';
      if (allowRecovery && mutating && response.status === 403) {
        const csrfToken = await recoverCsrf();
        return request(url, { ...options, headers: { ...(options.headers || {}), 'X-CSRF-Token': csrfToken } }, false);
      }
      throw error;
    }
    return payload;
  }

  function destination(notification, portal) {
    const type = String(notification.type || '').toLowerCase();
    const userId = Number(notification.relatedUserId || 0);
    const ticketId = Number(notification.relatedTicketId || 0);
    if (['colleague_added', 'colleague_accepted', 'colleague_request'].includes(type)) {
      return userId > 0 ? `/profile?id=${encodeURIComponent(userId)}` : null;
    }
    if (type.startsWith('ticket_')) {
      return ticketId > 0 ? `${portal === 'admin' ? '/ticket-management' : '/tickets'}?ticketId=${encodeURIComponent(ticketId)}` : null;
    }
    if (type.startsWith('profile_photo_') || ['account_approved', 'account_rejected', 'account_created', 'role_changed', 'account_state_changed'].includes(type)) return '/profile';
    if (type.startsWith('security_')) return '/security';
    if (type.startsWith('knowledge_')) return portal === 'admin' ? '/knowledge' : '/knowledge-library';
    const supplied = String(notification.destination || '');
    if (supplied.startsWith('/') && !supplied.startsWith('//')) return supplied;
    return portal === 'admin' ? '/dashboard-admin' : '/dashboard-user';
  }

  function readableTime(value) {
    if (!value) return '';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium', timeStyle: 'short'
    }).format(date);
  }

  function setStatus(center, message = '', error = false) {
    center.status.textContent = message;
    center.status.classList.toggle('text-danger', error);
    center.status.hidden = !message;
  }

  function actionError(error, fallback) {
    if (error?.status === 403 && !cookie('aitsm_csrf')) {
      return 'Your session security token is stale. Sign out and sign in again, then retry.';
    }
    return error?.message || fallback;
  }

  function updateBadge(center, unread) {
    const value = Math.max(0, Number(unread) || 0);
    center.count.textContent = String(value);
    center.count.classList.toggle('hidden', value === 0);
    center.trigger.setAttribute('aria-label', value ? `Notifications, ${value} unread` : 'Notifications');
  }

  function avatar(notification) {
    const image = document.createElement('img');
    image.className = 'notification-avatar';
    image.alt = '';
    image.src = notification.relatedUserProfileImageUrl || DEFAULT_AVATAR;
    image.addEventListener('error', () => {
      if (!image.src.endsWith(DEFAULT_AVATAR)) image.src = DEFAULT_AVATAR;
    }, { once: true });
    return image;
  }

  function notificationButton(center, notification) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `notification-entry${notification.isRead ? '' : ' is-unread'}`;
    button.dataset.notificationId = String(notification.id);
    const copy = document.createElement('span');
    copy.className = 'notification-entry-copy';
    const title = document.createElement('strong');
    title.textContent = notification.title || 'Notification';
    const message = document.createElement('span');
    message.textContent = notification.message || 'Notification received.';
    const time = document.createElement('time');
    time.dateTime = notification.createdAt || '';
    time.textContent = readableTime(notification.createdAt);
    copy.append(title, message, time);
    button.append(avatar(notification), copy);
    button.addEventListener('click', () => open(center, notification));
    return button;
  }

  function render(center) {
    center.list.replaceChildren();
    const toolbar = document.createElement('div');
    toolbar.className = 'notification-toolbar';
    const heading = document.createElement('strong');
    heading.textContent = 'Notifications';
    const markAll = document.createElement('button');
    markAll.type = 'button';
    markAll.className = 'btn btn-ghost notification-mark-all';
    markAll.textContent = center.markingAll ? 'Marking…' : 'Mark all read';
    markAll.disabled = center.markingAll || !center.items.some((item) => !item.isRead);
    markAll.addEventListener('click', () => markAllRead(center));
    const viewAll = document.createElement('a');
    viewAll.className = 'btn btn-ghost notification-view-all';
    viewAll.href = '/notifications';
    viewAll.textContent = 'View all';
    toolbar.append(heading, viewAll, markAll);
    center.status = document.createElement('p');
    center.status.className = 'small notification-status';
    center.status.setAttribute('role', 'status');
    center.status.setAttribute('aria-live', 'polite');
    center.status.hidden = true;
    center.list.append(toolbar, center.status);
    if (!center.items.length) {
      const empty = document.createElement('p');
      empty.className = 'small notification-empty';
      empty.textContent = 'No notifications.';
      center.list.appendChild(empty);
      return;
    }
    center.items.forEach((item) => center.list.appendChild(notificationButton(center, item)));
  }

  async function refresh(center, { silent = false } = {}) {
    if (center.loading) return center.loading;
    center.loading = (async () => {
      try {
        const [page, count] = await Promise.all([
          request('/api/notifications?page=1&pageSize=12'),
          request('/api/notifications/unread-count')
        ]);
        center.items = Array.isArray(page?.items) ? page.items : [];
        render(center);
        updateBadge(center, count.unreadCount);
      } catch (error) {
        if (!silent) {
          if (!center.status?.isConnected) render(center);
          setStatus(center, error.message || 'Unable to load notifications.', true);
        }
      } finally {
        center.loading = null;
      }
    })();
    return center.loading;
  }

  async function open(center, notification) {
    if (center.opening.has(notification.id)) return;
    center.opening.add(notification.id);
    setStatus(center);
    try {
      const result = await request(`/api/notifications/${encodeURIComponent(notification.id)}/read`, { method: 'PATCH' });
      notification.isRead = true;
      notification.readAt = notification.readAt || new Date().toISOString();
      render(center);
      updateBadge(center, result.unreadCount);
      const target = destination(notification, center.portal);
      if (!target) {
        setStatus(center, 'The item linked to this notification is no longer available.', true);
        return;
      }
      center.menu.classList.add('hidden');
      location.assign(target);
    } catch (error) {
      setStatus(center, actionError(error, 'Unable to open this notification.'), true);
    } finally {
      center.opening.delete(notification.id);
    }
  }

  async function markAllRead(center) {
    if (center.markingAll) return;
    center.markingAll = true;
    render(center);
    try {
      const result = await request('/api/notifications/read-all', { method: 'PATCH' });
      center.items.forEach((item) => { item.isRead = true; });
      center.markingAll = false;
      render(center);
      updateBadge(center, result.unreadCount);
      setStatus(center, 'All notifications are marked as read.');
    } catch (error) {
      center.markingAll = false;
      render(center);
      setStatus(center, actionError(error, 'Unable to mark notifications as read.'), true);
    }
  }

  function mount(options) {
    const key = options.portal;
    const list = document.getElementById(options.listId);
    const count = document.getElementById(options.countId);
    const menu = document.getElementById(options.menuId);
    const trigger = document.getElementById(options.triggerId);
    if (!list || !count || !menu || !trigger) return null;
    const existing = centers.get(key);
    if (existing?.list === list) return existing;
    if (existing?.timer) clearInterval(existing.timer);
    const center = { ...options, list, count, menu, trigger, items: [], loading: null, markingAll: false, opening: new Set(), status: null, timer: null };
    centers.set(key, center);
    render(center);
    refresh(center);
    center.timer = setInterval(() => refresh(center, { silent: true }), 30000);
    return center;
  }

  global.AITSMNotifications = {
    mount,
    refresh(portal) { const center = centers.get(portal); return center ? refresh(center) : Promise.resolve(); },
    markAll(portal) { const center = centers.get(portal); return center ? markAllRead(center) : Promise.resolve(); }
  };
})(window);
