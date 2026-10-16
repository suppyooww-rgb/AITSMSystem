const AI_CHAT_STATE_KEY = 'aiAssistantChatState';
const AI_TICKET_PREFILL_KEY = 'aiTicketPrefill';
const AI_SESSION_KEY = 'aiAssistantSessionId';

let aiState = {
  messages: [],
  pending: false,
  lastVisible: null
};

function getSessionId() {
  let id = localStorage.getItem(AI_SESSION_KEY);
  if (id) return id;
  id = (crypto?.randomUUID?.() || `sess-${Date.now()}-${Math.random().toString(16).slice(2)}`);
  localStorage.setItem(AI_SESSION_KEY, id);
  return id;
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function saveState() {
  try {
    localStorage.setItem(AI_CHAT_STATE_KEY, JSON.stringify(aiState));
  } catch {
    // noop
  }
}

function loadState() {
  try {
    const raw = localStorage.getItem(AI_CHAT_STATE_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed.messages)) return;
    aiState.messages = parsed.messages.slice(-20);
    aiState.lastVisible = parsed.lastVisible || null;
  } catch {
    aiState.messages = [];
    aiState.lastVisible = null;
  }
}

function renderFallbackSections(container, fallback = {}) {
  const text = [
    'Issue summary', fallback.issueSummary || 'Not provided', '',
    'Likely causes', ...(fallback.likelyCauses || ['No likely causes provided.']).map((item) => `- ${item}`), '',
    'Troubleshooting steps', ...(fallback.troubleshootingSteps || ['No troubleshooting steps provided.']).map((item, index) => `${index + 1}. ${item}`), '',
    'Escalation guidance', ...(fallback.escalationCriteria || ['No escalation guidance provided.']).map((item) => `- ${item}`), '',
    'Suggested priority', fallback.suggestedPriority || 'Medium'
  ].join('\n');
  window.AitsmAiRenderer.render(container, text);
}

function renderMessages() {
  const thread = document.getElementById('aiThread');
  if (!thread) return;

  if (!aiState.messages.length) {
    const empty = document.createElement('p');
    empty.className = 'small';
    empty.textContent = 'Start by describing your issue. The assistant will return troubleshooting guidance.';
    thread.replaceChildren(empty);
  } else {
    thread.replaceChildren(...aiState.messages.map((message) => {
      const article = document.createElement('article');
      article.className = `ai-message ${message.role === 'user' ? 'ai-message-user' : 'ai-message-assistant'}`;
      const bubble = document.createElement('div');
      bubble.className = 'ai-message-bubble';
      if (message.role === 'user') bubble.textContent = message.content || '';
      else window.AitsmAiRenderer.render(bubble, message.content || '');
      article.append(bubble);
      return article;
    }));
  }

  thread.scrollTop = thread.scrollHeight;
}

function renderFallbackPanel() {
  const panel = document.getElementById('aiFallbackPanel');
  const message = document.getElementById('aiFallbackMessage');
  const details = document.getElementById('aiFallbackDetails');
  if (!panel || !message || !details) return;

  const visible = aiState.lastVisible;
  if (!visible || visible.type !== 'fallback' || !visible.fallback) {
    panel.classList.add('hidden');
    message.textContent = '';
    details.innerHTML = '';
    return;
  }

  panel.classList.remove('hidden');
  message.textContent = visible.fallback.message || 'AI backend is unavailable.';
  renderFallbackSections(details, visible.fallback);
}

function setPending(isPending) {
  aiState.pending = isPending;
  document.getElementById('aiSendBtn')?.toggleAttribute('disabled', isPending);
  const textarea = document.getElementById('aiMessage');
  if (textarea) textarea.disabled = isPending;
  document.getElementById('aiLoading')?.classList.toggle('hidden', !isPending);
}

function addUserMessage(content) {
  aiState.messages.push({
    role: 'user',
    content: String(content || '').trim(),
    timestamp: new Date().toISOString()
  });
  aiState.messages = aiState.messages.slice(-20);
  saveState();
  renderMessages();
}

function addAssistantReply(replyText) {
  aiState.messages.push({
    role: 'assistant',
    content: String(replyText || '').trim(),
    timestamp: new Date().toISOString()
  });
  aiState.messages = aiState.messages.slice(-20);
}

function applyAiResponse(payload, userMessage) {
  if (!payload || typeof payload !== 'object') {
    aiState.lastVisible = {
      type: 'fallback',
      fallback: localFallback('Malformed AI response payload.', userMessage)
    };
    saveState();
    renderMessages();
    renderFallbackPanel();
    return;
  }

  const source = payload.source;
  if (source !== 'fallback' && typeof payload.reply === 'string' && payload.reply.trim()) {
    addAssistantReply(payload.reply.trim());
    aiState.lastVisible = { type: 'ai', reply: payload.reply.trim() };
    saveState();
    renderMessages();
    renderFallbackPanel();
    return;
  }

  if (source === 'fallback' && payload.fallback && typeof payload.fallback === 'object') {
    aiState.lastVisible = { type: 'fallback', fallback: payload.fallback };
    saveState();
    renderMessages();
    renderFallbackPanel();
    return;
  }

  aiState.lastVisible = {
    type: 'fallback',
    fallback: localFallback('AI response shape was invalid.', userMessage)
  };
  saveState();
  renderMessages();
  renderFallbackPanel();
}

function localFallback(reason, userMessage) {
  return {
    message: 'Local AI is unavailable until the secure AITSM AI Bridge is installed. You can continue with fallback guidance or create a support ticket.',
    issueSummary: userMessage || 'Unable to process request',
    likelyCauses: [reason || 'Secure local AI bridge is not available'],
    troubleshootingSteps: ['Continue with the fallback checklist', 'Contact an administrator if local AI assistance is required'],
    escalationCriteria: ['Escalate when technician diagnosis is required'],
    suggestedPriority: 'Medium',
    ticketTitle: 'AI assistant unavailable',
    ticketDescription: 'Local AI assistance is pending the secure AITSM AI Bridge. Continue with manual troubleshooting and technician review.'
  };
}

async function sendMessage() {
  if (aiState.pending) return;
  const textarea = document.getElementById('aiMessage');
  if (!textarea) return;
  const message = textarea.value.trim();
  if (!message) return;

  addUserMessage(message);
  textarea.value = '';
  setPending(true);

  try {
    const res = await fetch('/api/ai/chat', {
      method: 'POST',
      headers: {
        ...authHeaders(),
        'X-AI-Session-Id': getSessionId()
      },
      body: JSON.stringify({ message })
    });

    const data = await res.json().catch(() => null);
    if (!res.ok) {
      aiState.lastVisible = { type: 'fallback', fallback: localFallback(data?.error || data?.message || 'Unable to contact AI assistant', message) };
      saveState();
      renderFallbackPanel();
      return;
    }
    applyAiResponse(data, message);
  } catch (error) {
    aiState.lastVisible = { type: 'fallback', fallback: localFallback(error?.message || 'Unable to contact AI assistant', message) };
    saveState();
    renderFallbackPanel();
  } finally {
    setPending(false);
    textarea.focus();
  }
}

async function clearChat() {
  aiState = { messages: [], pending: false, lastVisible: null };
  saveState();
  renderMessages();
  renderFallbackPanel();

  try {
    await fetch('/api/ai/clear', {
      method: 'POST',
      headers: {
        ...authHeaders(),
        'X-AI-Session-Id': getSessionId()
      }
    });
  } catch {
    // local clear is enough
  }
}

function draftFromVisible() {
  const visible = aiState.lastVisible;
  const lastUser = aiState.messages.filter((m) => m.role === 'user').slice(-1)[0]?.content || '';
  if (!visible) return null;

  if (visible.type === 'ai' && visible.reply) {
    return {
      title: 'AI-assisted IT support request',
      description: visible.reply,
      priority: 'Medium',
      issueSummary: lastUser || 'IT support request',
      originalUserMessage: lastUser
    };
  }

  if (visible.type === 'fallback' && visible.fallback) {
    return {
      title: visible.fallback.ticketTitle || visible.fallback.issueSummary || 'IT support request',
      description: visible.fallback.ticketDescription || visible.fallback.message || '',
      priority: visible.fallback.suggestedPriority || 'Medium',
      issueSummary: visible.fallback.issueSummary || lastUser,
      originalUserMessage: lastUser
    };
  }

  return null;
}

async function createDraftFromVisible() {
  const draft = draftFromVisible();
  if (!draft) throw new Error('No assistant response available yet.');

  const res = await fetch('/api/ai/create-ticket-draft', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({
      issueSummary: draft.issueSummary,
      ticketDescription: draft.description,
      suggestedPriority: draft.priority,
      originalUserMessage: draft.originalUserMessage,
      ticketTitle: draft.title
    })
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || 'Unable to build ticket draft');

  sessionStorage.setItem(AI_TICKET_PREFILL_KEY, JSON.stringify({
    title: data.title,
    description: data.description,
    priority: data.priority
  }));
  location.href = '/create-ticket';
}

function usePrompt(prompt) {
  const textarea = document.getElementById('aiMessage');
  if (!textarea) return;
  textarea.value = prompt;
  textarea.focus();
}

function applyTicketPrefillFromAI() {
  const title = document.getElementById('title');
  const description = document.getElementById('description');
  const priority = document.getElementById('priority');
  if (!title || !description || !priority) return;

  const raw = sessionStorage.getItem(AI_TICKET_PREFILL_KEY);
  if (!raw) return;

  try {
    const prefill = JSON.parse(raw);
    if (prefill.title) title.value = String(prefill.title).slice(0, 255);
    if (prefill.description) description.value = String(prefill.description).slice(0, 5000);
    if (prefill.priority) priority.value = prefill.priority;
  } finally {
    sessionStorage.removeItem(AI_TICKET_PREFILL_KEY);
  }
}

function wireEvents() {
  document.getElementById('aiComposer')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    await sendMessage();
  });

  document.getElementById('aiMessage')?.addEventListener('keydown', async (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      await sendMessage();
    }
  });

  document.getElementById('clearAiChatBtn')?.addEventListener('click', clearChat);
  document.querySelectorAll('.ai-prompt').forEach((button) => {
    button.addEventListener('click', () => usePrompt(button.dataset.prompt || ''));
  });

  document.getElementById('createTicketFromLastBtn')?.addEventListener('click', async () => {
    try {
      await createDraftFromVisible();
    } catch (error) {
      alert(error?.message || 'Unable to create ticket draft');
    }
  });

  document.getElementById('aiFallbackTicketBtn')?.addEventListener('click', async () => {
    try {
      await createDraftFromVisible();
    } catch (error) {
      alert(error?.message || 'Unable to create ticket draft');
    }
  });
}

document.addEventListener('DOMContentLoaded', () => {
  applyTicketPrefillFromAI();
  if (!document.getElementById('aiComposer')) return;
  loadState();
  renderMessages();
  renderFallbackPanel();
  wireEvents();
});
