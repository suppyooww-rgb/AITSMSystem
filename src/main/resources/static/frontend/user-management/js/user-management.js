const userMgmtState = {
  users: [],
  filters: {
    search: '',
    role: 'all',
    verification: 'all',
    sort: 'name-asc'
  }
};
let accessApprovalUserId = null;
let passwordResetResolver = null;

function openAccessApprovalModal(userId, email) {
  accessApprovalUserId = userId;
  document.getElementById('accessApprovalTarget').textContent = email;
  document.getElementById('accessApprovalModal').classList.replace('hidden', 'show');
}
function closeAccessApprovalModal() {
  document.getElementById('accessApprovalModal').classList.replace('show', 'hidden');
  accessApprovalUserId = null;
}
async function submitAccessApproval() {
  const department = document.getElementById('accessApprovalDepartment').value;
  const role = document.getElementById('accessApprovalRole').value;
  if (!department) return alert('Select a department before granting access.');
  try {
    const reauthToken = await obtainReauthToken('admin-access');
    await fetchJsonOrThrow(`/api/users/${accessApprovalUserId}/access-approval`, {
      method: 'PUT', headers: { ...authHeaders(), 'X-Reauth-Token': reauthToken }, body: JSON.stringify({ department, role })
    });
    consumeCachedReauth('admin-access');
    closeAccessApprovalModal();
    alert('Account access granted.');
    await loadUserManagement();
  } catch (error) { alert(error.message || 'Unable to grant account access.'); }
}

function ensureActionMenuBackdrop() {
  if (document.getElementById('actionMenuBackdrop')) return;
  const backdrop = document.createElement('div');
  backdrop.id = 'actionMenuBackdrop';
  backdrop.className = 'action-menu-backdrop hidden';
  backdrop.addEventListener('click', () => {
    document.querySelectorAll('.row-action-menu').forEach((el) => el.classList.add('hidden'));
    document.getElementById('actionMenuBackdrop')?.classList.add('hidden');
    backdrop.classList.add('hidden');
  });
  document.body.appendChild(backdrop);
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function closeUserMgmtMenus() {
  document.querySelectorAll('.row-action-menu').forEach((el) => el.classList.add('hidden'));
  document.getElementById('actionMenuBackdrop')?.classList.add('hidden');
}

function toggleUserMgmtMenu(event, id) {
  event.stopPropagation();
  const menu = document.getElementById(`userMgmtMenu-${id}`);
  if (!menu) return;
  const trigger = event.currentTarget;
  const open = menu.classList.contains('hidden');
  closeUserMgmtMenus();
  if (!open) return;
  ensureActionMenuBackdrop();
  document.getElementById('actionMenuBackdrop')?.classList.remove('hidden');
  const rect = trigger.getBoundingClientRect();
  menu.style.position = 'fixed';
  menu.style.top = `${rect.bottom + 4}px`;
  menu.style.left = `${Math.max(8, rect.right - 200)}px`;
  menu.classList.remove('hidden');
}

async function fetchJsonOrThrow(url, options = {}) {
  return window.aitsmFetchJson(url, options);
}

async function changeRoleFromUserManagement(userId, role) {
  const reauthToken = await obtainReauthToken('admin-access');
  const res = await fetch(`/api/users/${userId}/role`, {
    method: 'PUT',
    headers: { ...authHeaders(), 'X-Reauth-Token': reauthToken },
    body: JSON.stringify({ role })
  });
  const data = await res.json();
  consumeCachedReauth('admin-access');
  if (!res.ok) return alert(data.error || 'Unable to update role');
  await loadUserManagement();
}

async function resetUserPasswordFromUserManagement(userId, email) {
  const values = await requestTemporaryPassword(email);
  if (!values) return;
  const { newPassword, confirmPassword } = values;

  const reauthToken = await obtainReauthToken('admin-access');
  const res = await fetch(`/api/users/${userId}/reset-password`, {
    method: 'PUT',
    headers: { ...authHeaders(), 'X-Reauth-Token': reauthToken },
    body: JSON.stringify({ newPassword, confirmPassword })
  });
  const data = await res.json();
  consumeCachedReauth('admin-access');
  if (!res.ok) return alert(data.error || 'Unable to reset password');
  alert(data.message || 'Password reset successful');
}

async function deleteUserFromUserManagement(userId, email) {
  const confirmed = confirm(`Delete account ${email}? This also removes user tickets and notifications.`);
  if (!confirmed) return;

  const reauthToken = await obtainReauthToken('account-delete');
  const res = await fetch(`/api/users/${userId}`, {
    method: 'DELETE',
    headers: { ...authHeaders(), 'X-Reauth-Token': reauthToken }
  });
  consumeCachedReauth('account-delete');
  const data = await res.json();
  if (!res.ok) return alert(data.error || 'Unable to delete user');

  alert(data.message || 'User deleted');
  await loadUserManagement();
}

async function setUserEmailApprovalFromUserManagement(userId, approved) {
  const reauthToken = await obtainReauthToken('admin-access');
  const res = await fetch(`/api/users/${userId}/email-approval`, {
    method: 'PUT',
    headers: { ...authHeaders(), 'X-Reauth-Token': reauthToken },
    body: JSON.stringify({ approved })
  });
  const data = await res.json();
  consumeCachedReauth('admin-access');
  if (!res.ok) return alert(data.error || 'Unable to update email approval');
  await loadUserManagement();
}

function finishTemporaryPassword(values) {
  const modal = document.getElementById('passwordResetModal');
  modal?.classList.replace('show', 'hidden');
  const resolver = passwordResetResolver;
  passwordResetResolver = null;
  resolver?.(values);
}

function requestTemporaryPassword(email) {
  if (passwordResetResolver) return Promise.reject(new Error('A password dialog is already open.'));
  const modal = document.getElementById('passwordResetModal');
  const form = document.getElementById('passwordResetForm');
  form?.reset();
  document.getElementById('passwordResetTarget').textContent = `Set a temporary password for ${email}.`;
  document.getElementById('passwordResetMessage').classList.add('hidden');
  modal.classList.replace('hidden', 'show');
  window.setTimeout(() => document.getElementById('temporaryPassword')?.focus(), 0);
  return new Promise((resolve) => { passwordResetResolver = resolve; });
}

function submitTemporaryPassword(event) {
  event.preventDefault();
  const newPassword = document.getElementById('temporaryPassword').value;
  const confirmPassword = document.getElementById('temporaryPasswordConfirm').value;
  const message = document.getElementById('passwordResetMessage');
  if (newPassword.length < 12) {
    message.textContent = 'Temporary passwords must contain at least 12 characters.';
    message.classList.remove('hidden');
    return;
  }
  if (newPassword !== confirmPassword) {
    message.textContent = 'The passwords do not match.';
    message.classList.remove('hidden');
    return;
  }
  finishTemporaryPassword({ newPassword, confirmPassword });
}

async function resetAdministratorMfa(userId, email) {
  if (!confirm(`Reset MFA for ${email}? Every active session for that administrator will be revoked.`)) return;
  try {
    const reauthToken = await obtainReauthToken('mfa-change');
    await fetchJsonOrThrow(`/api/users/admin/${userId}/mfa-reset`, {
      method: 'POST', headers: { ...authHeaders(), 'X-Reauth-Token': reauthToken }
    });
    consumeCachedReauth('mfa-change');
    alert('MFA was reset and the administrator must enroll again at next sign-in.');
  } catch (error) { alert(error.message || 'Unable to reset administrator MFA.'); }
}

async function openAddAdminFromUserManagement(targetEmail) {
  const password = await requestReauthPassword();
  if (!password) return;

  const verifyRes = await fetch('/api/users/admin/verify', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ password })
  });
  const verifyData = await verifyRes.json();
  if (!verifyRes.ok || !verifyData.verified || !verifyData.verificationToken) {
    return alert(verifyData.message || verifyData.error || 'Verification failed');
  }

  const grantRes = await fetch('/api/users/admin/grant', {
    method: 'POST',
    headers: { ...authHeaders(), 'X-Reauth-Token': verifyData.reauthToken || '' },
    body: JSON.stringify({ targetEmail, verificationToken: verifyData.verificationToken })
  });
  const grantData = await grantRes.json();
  if (!grantRes.ok || !grantData.success) return alert(grantData.message || grantData.error || 'Unable to grant admin role');

  alert(grantData.message || 'Admin role granted successfully');
  await loadUserManagement();
}

function userSortComparator(sortKey) {
  if (sortKey === 'name-desc') return (a, b) => String(b.fullName || '').localeCompare(String(a.fullName || ''));
  if (sortKey === 'email-asc') return (a, b) => String(a.email || '').localeCompare(String(b.email || ''));
  if (sortKey === 'email-desc') return (a, b) => String(b.email || '').localeCompare(String(a.email || ''));
  if (sortKey === 'role-asc') return (a, b) => String(a.role || '').localeCompare(String(b.role || ''));
  return (a, b) => String(a.fullName || '').localeCompare(String(b.fullName || ''));
}

function applyUserManagementFilters() {
  const query = userMgmtState.filters.search.trim().toLowerCase();
  const role = userMgmtState.filters.role;
  const verification = userMgmtState.filters.verification;

  const filtered = userMgmtState.users.filter((user) => {
    const userRole = String(user.role || '').toLowerCase();
    const isVerified = Boolean(user.emailVerified);

    const roleMatch = role === 'all' || userRole === role;
    const verificationMatch = verification === 'all'
      || (verification === 'verified' && isVerified)
      || (verification === 'pending' && !isVerified);

    if (!query) return roleMatch && verificationMatch;

    const tokens = [user.fullName, user.email, user.role].map((value) => String(value || '').toLowerCase());
    return roleMatch && verificationMatch && tokens.some((token) => token.includes(query));
  });

  return [...filtered].sort(userSortComparator(userMgmtState.filters.sort));
}

function renderUserManagementCount(visible, total) {
  const el = document.getElementById('userMgmtCount');
  if (!el) return;
  el.textContent = `${visible} shown / ${total} total`;
}

function renderUserManagementRows() {
  const rows = document.getElementById('userMgmtRows');
  if (!rows) return;

  const users = applyUserManagementFilters();
  renderUserManagementCount(users.length, userMgmtState.users.length);

  if (!users.length) {
    renderTableEmptyState(rows, 5, 'No users matched the selected filters.');
    return;
  }

  rows.innerHTML = users.map((u) => {
    const currentUserId = Number(window.currentUser?.id || 0);
    const canChange = u.role !== 'superadmin' && u.id !== currentUserId;
    const encodedEmail = encodeURIComponent(u.email || '');
    const needsAccessProvisioning = !u.emailVerified || String(u.department || '').trim().toLowerCase() === 'pending approval';
    const menuItems = [
      needsAccessProvisioning ? `<button type='button' ${canChange ? '' : 'disabled'} onclick='openAccessApprovalModal(${u.id}, decodeURIComponent("${encodedEmail}"))'>Grant access</button>` : '',
      normalizeRole(u.role) === 'admin'
        ? `<button type='button' ${canChange ? '' : 'disabled'} onclick='changeRoleFromUserManagement(${u.id}, "END_USER")'>Set as end-user</button>`
        : `<button type='button' ${canChange ? '' : 'disabled'} onclick='openAddAdminFromUserManagement(decodeURIComponent("${encodedEmail}"))'>Grant admin role</button>`,
      !u.emailVerified
        ? `<button type='button' ${canChange ? '' : 'disabled'} onclick='setUserEmailApprovalFromUserManagement(${u.id}, true)'>Approve email for login</button>`
        : '',
      `<button type='button' ${canChange ? '' : 'disabled'} onclick='resetUserPasswordFromUserManagement(${u.id}, decodeURIComponent("${encodedEmail}"))'>Reset password</button>`,
      currentRole() === 'superadmin' && ['admin', 'it-admin'].includes(normalizeRole(u.role))
        ? `<button type='button' ${canChange ? '' : 'disabled'} onclick='resetAdministratorMfa(${u.id}, decodeURIComponent("${encodedEmail}"))'>Reset administrator MFA</button>` : '',
      `<button type='button' ${canChange ? '' : 'disabled'} onclick='deleteUserFromUserManagement(${u.id}, decodeURIComponent("${encodedEmail}"))'>Delete account</button>`
    ].join('');

    const actionMenu = `<div class='row-action-wrap'>
      <button class='btn btn-ghost icon-btn' onclick='toggleUserMgmtMenu(event, ${u.id})' title='Actions' aria-label='Actions'>...</button>
      <div id='userMgmtMenu-${u.id}' class='row-action-menu hidden'>${menuItems}</div>
    </div>`;

    return `<tr>
      <td>${escapeHtml(u.fullName)}</td>
      <td>${escapeHtml(u.email)}</td>
      <td><span class='badge ${isAdminPortalRole(u.role) ? 'in-progress' : 'resolved'}'>${escapeHtml(normalizeRole(u.role))}</span></td>
      <td>${u.emailVerified ? '<span class="badge resolved">Verified</span>' : '<span class="badge warning">Pending</span>'}</td>
      <td>${actionMenu}</td>
    </tr>`;
  }).join('');
}

async function loadUserManagement() {
  const rows = document.getElementById('userMgmtRows');
  if (!rows) return;
  showTableSkeleton(rows, { rowCount: 6, columnCount: 5, hasActions: true });

  try {
    userMgmtState.users = await fetchJsonOrThrow('/api/users');
    clearTableSkeleton(rows);
    renderUserManagementRows();
  } catch (error) {
    renderTableErrorState(rows, 5, error.message);
  } finally {
    clearTableSkeleton(rows);
  }
}

async function loadProfilePhotoApprovals() {
  const rows = document.getElementById('profilePhotoApprovalRows');
  if (!rows) return;
  showTableSkeleton(rows, { rowCount: 3, columnCount: 4, hasActions: true });
  try {
    const requests = await fetchJsonOrThrow('/api/profile-photo-requests/pending');
    clearTableSkeleton(rows);
    if (!requests.length) return renderTableEmptyState(rows, 4, 'No profile photos are waiting for approval.');
    const reviewerId = Number(window.currentUser?.id || 0);
    const reviewerRole = String(window.currentUser?.role || '').toLowerCase();
    rows.innerHTML = requests.map((request) => {
      const ownerRole = String(request.userRole || '').toLowerCase();
      const blocked = request.userId === reviewerId || (ownerRole === 'superadmin' && reviewerRole !== 'superadmin');
      const reason = request.userId === reviewerId ? 'You cannot review your own photo.' : (ownerRole === 'superadmin' ? 'Only another superadmin can review this photo.' : '');
      return `<tr>
      <td>${escapeHtml(request.userName || 'Unknown')}<br><span class='small'>${escapeHtml(request.userEmail || '')}</span></td>
      <td><img class='approval-photo-preview' src='${encodeURI(request.photoUrl)}' alt='Submitted profile photo for ${escapeHtml(request.userName || 'user')}'></td>
      <td>${escapeHtml(new Date(request.submittedAt).toLocaleString())}</td>
      <td><div class='inline-actions'><button class='btn btn-primary' type='button' ${blocked ? 'disabled' : ''} title='${reason}' onclick='reviewProfilePhoto(${request.id}, true, this)'>Approve</button><button class='btn btn-ghost' type='button' ${blocked ? 'disabled' : ''} title='${reason}' onclick='reviewProfilePhoto(${request.id}, false, this)'>Reject</button></div>${reason ? `<span class='small text-danger'>${reason}</span>` : ''}</td>
    </tr>`; }).join('');
  } catch (error) {
    renderTableErrorState(rows, 4, error.message || 'Unable to load profile photo requests.');
  } finally { clearTableSkeleton(rows); }
}

function confirmPhotoReview(approved) {
  return new Promise((resolve) => {
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay show';
    overlay.innerHTML = `<section class='modal-card' role='dialog' aria-modal='true' aria-labelledby='photoReviewTitle'>
      <h3 id='photoReviewTitle' class='section-title'>${approved ? 'Approve profile photo?' : 'Reject profile photo?'}</h3>
      <p class='small'>${approved ? 'The submitted photo will replace the current approved photo.' : 'The user will be told that the photo does not comply with the company’s code of conduct or ethics guidelines and may upload a replacement.'}</p>
      ${approved ? '' : `<div class='form-group'><label for='photoReviewReason'>Optional professional explanation</label><textarea id='photoReviewReason' maxlength='500' rows='4'></textarea><span class='small'>Maximum 500 characters. Do not include private reviewer information.</span></div>`}
      <div class='inline-actions' style='justify-content:flex-end'><button class='btn btn-ghost' type='button' data-cancel>Cancel</button><button class='btn ${approved ? 'btn-primary' : 'btn-danger'}' type='button' data-confirm>${approved ? 'Confirm approval' : 'Confirm rejection'}</button></div>
    </section>`;
    const finish = (value) => { overlay.remove(); resolve(value); };
    overlay.querySelector('[data-cancel]').addEventListener('click', () => finish(null));
    overlay.querySelector('[data-confirm]').addEventListener('click', () => finish({ reason: overlay.querySelector('#photoReviewReason')?.value.trim() || null }));
    overlay.addEventListener('keydown', (event) => { if (event.key === 'Escape') finish(null); });
    document.body.appendChild(overlay);
    (overlay.querySelector('#photoReviewReason') || overlay.querySelector('[data-confirm]')).focus();
  });
}

async function reviewProfilePhoto(id, approved, trigger) {
  const action = approved ? 'approve' : 'reject';
  const decision = await confirmPhotoReview(approved);
  if (!decision) return;
  if (trigger?.disabled) return;
  if (trigger) trigger.disabled = true;
  try {
    await fetchJsonOrThrow(`/api/profile-photo-requests/${id}/review`, { method: 'POST', body: JSON.stringify({ approved, reason: decision.reason }) });
    alert(`Profile photo ${approved ? 'approved' : 'rejected'}.`);
    await loadProfilePhotoApprovals();
  } catch (error) {
    if (trigger) trigger.disabled = false;
    alert(error.message || `Unable to ${action} profile photo.`);
  }
}

function wireUserManagementFilters() {
  const search = document.getElementById('userMgmtSearch');
  if (search) {
    search.addEventListener('input', () => {
      userMgmtState.filters.search = search.value || '';
      renderUserManagementRows();
    });
  }

  const bindSelect = (id, key) => {
    const el = document.getElementById(id);
    if (!el) return;
    el.addEventListener('change', () => {
      userMgmtState.filters[key] = el.value || 'all';
      renderUserManagementRows();
    });
  };

  bindSelect('userMgmtRoleFilter', 'role');
  bindSelect('userMgmtVerificationFilter', 'verification');
  bindSelect('userMgmtSort', 'sort');
}

async function loadInvitations() {
  const rows = document.getElementById('invitationRows');
  if (!rows) return;
  try {
    const invitations = await fetchJsonOrThrow('/api/auth/invitations');
    const active = invitations.filter(invite => !invite.acceptedAt && !invite.revokedAt);
    if (!active.length) {
      rows.innerHTML = `<tr><td colspan='5' class='small'>No active invitations.</td></tr>`;
      return;
    }
    rows.innerHTML = active.map(invite => `<tr>
      <td>${escapeHtml(invite.email)}</td><td>${escapeHtml(invite.role)}</td>
      <td>${escapeHtml(new Date(invite.expiresAt).toLocaleString())}</td>
      <td><span class='badge ${new Date(invite.expiresAt) > new Date() ? 'in-progress' : 'warning'}'>${new Date(invite.expiresAt) > new Date() ? 'Active' : 'Expired'}</span></td>
      <td><div class='inline-actions'><button class='btn btn-ghost' type='button' onclick='resendInvitation("${invite.id}", ${JSON.stringify(invite.email)}, ${JSON.stringify(invite.role)})'>Resend</button><button class='btn btn-ghost' type='button' onclick='revokeInvitation("${invite.id}")'>Revoke</button></div></td>
    </tr>`).join('');
  } catch (error) { rows.innerHTML = `<tr><td colspan='5' class='text-danger'>${escapeHtml(error.message)}</td></tr>`; }
}

async function createInvitation(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const emailInput = document.getElementById('invitationEmail');
  const email = emailInput?.value.trim() || '';
  const role = document.getElementById('invitationRole')?.value || 'END_USER';
  const submit = document.getElementById('sendInvitationBtn');
  const message = document.getElementById('invitationMessage');
  const showMessage = (text, tone = 'info') => {
    if (!message) return;
    message.textContent = text;
    message.classList.toggle('hidden', !text);
    message.classList.toggle('text-danger', tone === 'danger');
    message.classList.toggle('text-success', tone === 'success');
  };

  if (!email || !emailInput?.checkValidity()) {
    emailInput?.reportValidity();
    showMessage('Enter a valid email address.', 'danger');
    return;
  }

  if (submit) {
    submit.disabled = true;
    submit.textContent = 'Sending…';
  }
  showMessage('Creating and sending the invitation…');
  try {
    const reauthToken = await obtainReauthToken('admin-access');
    await fetchJsonOrThrow('/api/auth/invitations', {
      method: 'POST', headers: { ...authHeaders(), 'X-Reauth-Token': reauthToken }, body: JSON.stringify({ email, role })
    });
    consumeCachedReauth('admin-access');
    form.reset();
    await loadInvitations();
    showMessage(`Invitation sent to ${email}.`, 'success');
  } catch (error) {
    showMessage(error.message || 'Unable to create or send the invitation.', 'danger');
  } finally {
    if (submit) {
      submit.disabled = false;
      submit.textContent = 'Send invitation';
    }
  }
}

function wireInvitationForm() {
  const form = document.getElementById('invitationForm');
  if (!form || form.dataset.submitBound === 'true') return;
  form.dataset.submitBound = 'true';
  form.addEventListener('submit', createInvitation);
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', wireInvitationForm, { once: true });
} else {
  wireInvitationForm();
}

async function revokeInvitation(id, skipConfirmation = false) {
  if (!skipConfirmation && !confirm('Revoke this invitation?')) return;
  const reauthToken = await obtainReauthToken('admin-access');
  await fetchJsonOrThrow(`/api/auth/invitations/${id}`, {
    method: 'DELETE', headers: { ...authHeaders(), 'X-Reauth-Token': reauthToken }
  });
  consumeCachedReauth('admin-access');
  await loadInvitations();
}

async function resendInvitation(id, email, role) {
  if (!confirm(`Invalidate the current link and send a new invitation to ${email}?`)) return;
  try {
    const reauthToken = await obtainReauthToken('admin-access');
    await fetchJsonOrThrow('/api/auth/invitations', {
      method: 'POST', headers: { ...authHeaders(), 'X-Reauth-Token': reauthToken }, body: JSON.stringify({ email, role })
    });
    consumeCachedReauth('admin-access');
    await loadInvitations();
  } catch (error) { alert(error.message || 'Unable to resend invitation.'); }
}

document.addEventListener('click', () => closeUserMgmtMenus());

document.addEventListener('DOMContentLoaded', async () => {
  await window.aitsmReady;
  wireUserManagementFilters();
  loadUserManagement();
  loadProfilePhotoApprovals();
  loadInvitations();
  document.getElementById('accessApprovalModal')?.addEventListener('click', (e) => {
    if (e.target.id === 'accessApprovalModal') closeAccessApprovalModal();
  });
});
