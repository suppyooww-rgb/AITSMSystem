
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

function toggleRowActionMenu(event, id) {
  event.stopPropagation();
  const menu = document.getElementById(`ticketRowMenu-${id}`);
  if (!menu) return;
  const trigger = event.currentTarget;
  const isHidden = menu.classList.contains('hidden');
  closeAllRowMenus();
  if (!isHidden) return;
  ensureActionMenuBackdrop();
  document.getElementById('actionMenuBackdrop')?.classList.remove('hidden');
  const rect = trigger.getBoundingClientRect();
  menu.style.position = 'fixed';
  menu.style.top = `${rect.bottom + 4}px`;
  menu.style.left = `${Math.max(8, rect.right - 180)}px`;
  menu.classList.remove('hidden');
}

function actionMenu(ticket){
  const status = ticket.status || '';
  const canFollowUp = !['Resolved','Closed','Cancelled'].includes(status) && hoursSince(ticket.updatedAt) >= FOLLOW_UP_HOURS;
  const items = [];
  if(status === 'Resolved') items.push(`<button type='button' onclick='updateMyTicketStatus(${ticket.id}, "Closed")'>Close ticket</button>`);
  if(['Open','In Progress','Follow-up Requested'].includes(status)) items.push(`<button type='button' onclick='updateMyTicketStatus(${ticket.id}, "Cancelled")'>Cancel ticket</button>`);
  if(canFollowUp) items.push(`<button type='button' onclick='updateMyTicketStatus(${ticket.id}, "Follow-up Requested")'>Request follow-up</button>`);
  if (!items.length) return '-';
  return `<div class='row-action-wrap'>
    <button class='btn btn-ghost icon-btn' onclick='toggleRowActionMenu(event, ${ticket.id})' title='Actions' aria-label='Actions'>⋯</button>
    <div id='ticketRowMenu-${ticket.id}' class='row-action-menu hidden'>${items.join('')}</div>
  </div>`;
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

  try {
    await window.aitsmReady;
    const data = await window.aitsmFetchJson('/api/tickets', {
      method: 'POST',
      body: JSON.stringify(body)
    });
    setCreateTicketMessage(`Ticket #${data.id} created successfully. Redirecting...`, 'success');
    location.href = '/tickets';
  } catch (error) {
    setCreateTicketMessage(error.message || 'Unable to create ticket', 'danger');
  }
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

async function analyzeTicketWithAi() {
  const button = document.getElementById('analyzeTicketBtn');
  const host = document.getElementById('ticketAiInsights');
  const titleInput = document.getElementById('title');
  const descriptionInput = document.getElementById('description');
  const body = { title: titleInput.value.trim(), description: descriptionInput.value.trim() };
  if (body.description.length < 10) return setCreateTicketMessage('Add at least 10 description characters before AI analysis.', 'danger');
  button.disabled = true;
  host.classList.remove('hidden', 'text-danger');
  host.textContent = 'Preparing ticket guidance and matching published knowledge articles…';
  try {
    await window.aitsmReady;
    const data = await window.aitsmFetchJson('/api/ai/ticket-insights', {
      method: 'POST',
      body: JSON.stringify(body)
    });
    renderTicketAiPreview(data);
  } catch (error) {
    host.textContent = error.message;
    host.classList.add('text-danger');
  }
  finally { button.disabled = false; }
}

function labeledPreviewField(label, control) {
  const group = document.createElement('label');
  group.className = 'form-group';
  const text = document.createElement('span');
  text.textContent = label;
  group.append(text, control);
  return group;
}

function previewSelect(id, value, choices) {
  const select = document.createElement('select');
  select.id = id;
  choices.forEach((choice) => {
    const option = document.createElement('option');
    option.value = choice;
    option.textContent = choice;
    option.selected = choice === value;
    select.appendChild(option);
  });
  return select;
}

function renderTicketAiPreview(data) {
  const host = document.getElementById('ticketAiInsights');
  const suggestion = data.suggestion || {};
  host.replaceChildren();

  const heading = document.createElement('div');
  heading.className = 'ai-advisory-heading';
  const headingText = document.createElement('div');
  const titleText = document.createElement('strong');
  titleText.textContent = 'Review AI suggestions';
  const subtitle = document.createElement('p');
  subtitle.className = 'small';
  subtitle.textContent = suggestion.advisory || 'Review and edit every suggestion before applying it.';
  headingText.append(titleText, subtitle);
  const source = document.createElement('span');
  source.className = `badge ${data.source === 'fallback' ? 'in-progress' : 'resolved'}`;
  source.textContent = data.source === 'fallback' ? 'Deterministic fallback' : 'Local AI';
  heading.append(headingText, source);

  const fields = document.createElement('div');
  fields.className = 'ai-preview-grid';
  const suggestedTitle = document.createElement('input');
  suggestedTitle.id = 'aiSuggestedTitle';
  suggestedTitle.maxLength = 200;
  suggestedTitle.value = suggestion.title || '';
  const suggestedDescription = document.createElement('textarea');
  suggestedDescription.id = 'aiSuggestedDescription';
  suggestedDescription.value = suggestion.description || '';
  fields.append(
    labeledPreviewField('Suggested title', suggestedTitle),
    labeledPreviewField('Suggested description', suggestedDescription),
    labeledPreviewField('Priority', previewSelect('aiSuggestedPriority', suggestion.priority || 'Medium', ['Critical', 'High', 'Medium', 'Low'])),
    labeledPreviewField('Category', previewSelect('aiSuggestedCategory', suggestion.category || 'Other', ['Hardware', 'Software', 'Network', 'Database', 'Security', 'Other']))
  );

  const service = document.createElement('p');
  service.className = 'small';
  service.textContent = `Affected service or area: ${suggestion.affectedService || 'Not identified'}`;

  const recommendations = document.createElement('div');
  recommendations.className = 'ai-recommendation-grid';
  const articles = Array.isArray(data.knowledgeArticles) ? data.knowledgeArticles : [];
  const knowledgeBlock = document.createElement('section');
  const knowledgeTitle = document.createElement('strong');
  knowledgeTitle.textContent = 'Recommended knowledge articles';
  knowledgeBlock.appendChild(knowledgeTitle);
  if (!articles.length) {
    const empty = document.createElement('p'); empty.className = 'small'; empty.textContent = 'No relevant published article was found.'; knowledgeBlock.appendChild(empty);
  } else {
    articles.forEach((article) => {
      const item = document.createElement('a');
      item.className = 'ai-recommendation-item';
      item.href = article.articleUrl;
      const itemTitle = document.createElement('strong'); itemTitle.textContent = article.title;
      const meta = document.createElement('span'); meta.textContent = `${article.category} — ${article.relevance}`;
      item.append(itemTitle, meta); knowledgeBlock.appendChild(item);
    });
  }

  const duplicatesBlock = document.createElement('section');
  const duplicatesTitle = document.createElement('strong'); duplicatesTitle.textContent = 'Possible duplicates'; duplicatesBlock.appendChild(duplicatesTitle);
  const duplicates = Array.isArray(data.duplicates) ? data.duplicates : [];
  if (!duplicates.length) {
    const empty = document.createElement('p'); empty.className = 'small'; empty.textContent = 'No similar open tickets were found.'; duplicatesBlock.appendChild(empty);
  } else {
    duplicates.forEach((ticket) => {
      const item = document.createElement('a'); item.className = 'ai-recommendation-item'; item.href = `/tickets?ticketId=${encodeURIComponent(ticket.id)}`;
      const itemTitle = document.createElement('strong'); itemTitle.textContent = `#${ticket.id} ${ticket.title}`;
      const meta = document.createElement('span'); meta.textContent = `${ticket.status} • ${ticket.priority} — ${ticket.reason}`;
      item.append(itemTitle, meta); duplicatesBlock.appendChild(item);
    });
  }
  recommendations.append(knowledgeBlock, duplicatesBlock);

  const actions = document.createElement('div');
  actions.className = 'inline-actions';
  const apply = document.createElement('button');
  apply.type = 'button'; apply.className = 'btn btn-primary'; apply.textContent = 'Apply reviewed suggestions';
  apply.addEventListener('click', applyTicketAiSuggestions);
  const dismiss = document.createElement('button');
  dismiss.type = 'button'; dismiss.className = 'btn btn-ghost'; dismiss.textContent = 'Dismiss';
  dismiss.addEventListener('click', () => { host.replaceChildren(); host.classList.add('hidden'); });
  actions.append(apply, dismiss);
  host.append(heading, fields, service, recommendations, actions);
}

function applyTicketAiSuggestions() {
  document.getElementById('title').value = document.getElementById('aiSuggestedTitle')?.value.trim() || document.getElementById('title').value;
  document.getElementById('description').value = document.getElementById('aiSuggestedDescription')?.value.trim() || document.getElementById('description').value;
  document.getElementById('priority').value = document.getElementById('aiSuggestedPriority')?.value || document.getElementById('priority').value;
  document.getElementById('category').value = document.getElementById('aiSuggestedCategory')?.value || document.getElementById('category').value;
  setCreateTicketMessage('Reviewed AI suggestions were applied. You can continue editing before creating the ticket.', 'success');
}

async function updateMyTicketStatus(id, status){
  closeAllRowMenus();
  try {
    await window.aitsmReady;
    await window.aitsmFetchJson(`/api/tickets/${id}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status })
    });
    await loadTickets();
  } catch (error) {
    alert(error.message || 'Unable to update ticket status');
  }
}

async function loadTickets() {
  const rows = document.getElementById('ticketRows');
  if (!rows) return;
  const role = currentRole();
  let tickets;
  try {
    tickets = await window.aitsmFetchAllPaginated('/api/tickets');
  } catch (error) {
    rows.innerHTML = `<tr><td colspan='6' class='small text-danger'>${aitsmEscapeHtml(error.message || 'Unable to load tickets')}</td></tr>`;
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
  }).join('') || `<tr><td colspan='6' class='small'>No tickets found.</td></tr>`;
  focusRequestedTicket();
}

document.addEventListener('click', () => closeAllRowMenus());
document.addEventListener('DOMContentLoaded', () => { loadTickets(); document.getElementById('analyzeTicketBtn')?.addEventListener('click', analyzeTicketWithAi); });
