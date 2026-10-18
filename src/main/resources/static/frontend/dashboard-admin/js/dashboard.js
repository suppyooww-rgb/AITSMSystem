const dashboardState = {
  tickets: [],
  monitoringSummary: null,
  lanDevices: [],
  notifications: [],
  inventoryStats: null,
  slaAnalytics: null,
  trend: {
    view: 'monthly',
    month: 'all',
    week: 'all',
    sort: 'latest'
  }
};

const DASHBOARD_TELEMETRY_REFRESH_MS = 5 * 1000;
const DASHBOARD_FEED_REFRESH_MS = 10 * 1000;
let dashboardTelemetryRefreshTimer = null;
let dashboardTelemetryRefreshInFlight = false;
let dashboardFeedRefreshTimer = null;
let dashboardFeedRefreshInFlight = false;

function parseDate(value) {
  const date = new Date(value || '');
  return Number.isNaN(date.getTime()) ? null : date;
}

function formatDateTime(value) {
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

function normalizeTicketList(payload) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.data)) return payload.data;
  return [];
}

function normalizeTicketStatus(value) {
  const normalized = String(value || '').trim().replaceAll('_', ' ').toLowerCase();
  if (normalized === 'pending' || normalized === 'in progress' || normalized === 'follow-up requested' || normalized === 'follow up requested') {
    return 'in progress';
  }
  if (normalized === 'resolved') return 'resolved';
  if (normalized === 'closed' || normalized === 'cancelled' || normalized === 'canceled') return 'closed';
  return 'open';
}

function readableTicketStatus(value) {
  const normalized = normalizeTicketStatus(value);
  if (normalized === 'in progress') return 'In Progress';
  if (normalized === 'resolved') return 'Resolved';
  if (normalized === 'closed') return 'Closed';
  return 'Open';
}

function statusBadgeClass(status) {
  const normalized = normalizeTicketStatus(status);
  if (normalized === 'resolved' || normalized === 'closed') return 'resolved';
  if (normalized === 'in progress') return 'in-progress';
  return 'open';
}

function fetchJsonOrThrow(url, options = {}) {
  return window.aitsmFetchJson(url, options);
}

function renderSlaIntelligence() {
  const analytics = dashboardState.slaAnalytics || {};
  const summary = document.getElementById('slaAnalyticsSummary');
  if (summary) {
    const cards = [
      ['Response compliance', `${Number(analytics.responseCompliancePercent || 0).toFixed(1)}%`, `${Number(analytics.responseMet || 0)} met / ${Number(analytics.responseMeasured || 0)} measured`],
      ['Resolution compliance', `${Number(analytics.resolutionCompliancePercent || 0).toFixed(1)}%`, `${Number(analytics.resolutionMet || 0)} met / ${Number(analytics.resolutionMeasured || 0)} measured`],
      ['Active breaches', Number(analytics.activeBreaches || 0), 'Response or resolution deadline exceeded'],
      ['High AI risk', Number(analytics.activeHighRisk || 0), 'High or critical predicted breach risk'],
      ['Prediction precision', `${Number(analytics.highRiskPrecisionPercent || 0).toFixed(1)}%`, `${Number(analytics.predictionSamples || 0)} completed high-risk samples`]
    ];
    summary.innerHTML = cards.map(([label, value, hint]) => `<div class='insight-item'><div class='small'>${escapeHtml(label)}</div><strong>${escapeHtml(value)}</strong><div class='small'>${escapeHtml(hint)}</div></div>`).join('');
  }

  const riskTarget = document.getElementById('slaRiskTickets');
  if (!riskTarget) return;
  const risky = dashboardState.tickets
    .filter(ticket => !['resolved', 'closed'].includes(normalizeTicketStatus(ticket.status)))
    .sort((a, b) => Number(b.slaRiskScore || 0) - Number(a.slaRiskScore || 0))
    .slice(0, 6);
  riskTarget.innerHTML = risky.length ? risky.map(ticket => `
    <a class='notification-item' href='/ticket-management?ticketId=${encodeURIComponent(ticket.id)}'>
      <div><strong>#${Number(ticket.id)} · ${escapeHtml(ticket.slaRiskLevel || 'LOW')} risk (${Number(ticket.slaRiskScore || 0)}%)</strong>
      <div class='small'>${escapeHtml((ticket.slaRiskReasons || []).slice(0, 2).join(' · '))}</div></div>
    </a>`).join('') : `<div class='empty-state'><h3>No active SLA risks</h3><p>Risk predictions will appear as tickets enter the support queue.</p></div>`;
}

function monthKey(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  return `${year}-${month}`;
}

function weekOfMonth(date) {
  return Math.ceil(date.getDate() / 7);
}

function monthLabel(monthToken) {
  const [yearText, monthText] = String(monthToken || '').split('-');
  const year = Number(yearText);
  const month = Number(monthText);
  if (!year || !month) return monthToken || 'Unknown';
  const date = new Date(year, month - 1, 1);
  return date.toLocaleString(undefined, { month: 'short', year: 'numeric' });
}

function sortLabels(labels, { view, sort }) {
  const desc = sort === 'latest';
  return [...labels].sort((a, b) => {
    if (view === 'weekly') {
      return desc ? Number(b) - Number(a) : Number(a) - Number(b);
    }
    return desc ? b.localeCompare(a) : a.localeCompare(b);
  });
}

function buildTrendData(tickets, trendState) {
  const safeTickets = Array.isArray(tickets) ? tickets : [];
  const filteredByDate = safeTickets
    .map((ticket) => {
      const date = parseDate(ticket.updatedAt || ticket.createdAt);
      if (!date) return null;
      return { ticket, date, month: monthKey(date), week: weekOfMonth(date) };
    })
    .filter(Boolean);

  const availableMonths = [...new Set(filteredByDate.map((entry) => entry.month))].sort((a, b) => b.localeCompare(a));
  const effectiveMonth = trendState.month === 'all' ? (availableMonths[0] || 'all') : trendState.month;

  let scoped = filteredByDate;
  if (trendState.view === 'weekly') {
    scoped = scoped.filter((entry) => entry.month === effectiveMonth);
  } else if (trendState.month !== 'all') {
    scoped = scoped.filter((entry) => entry.month === trendState.month);
  }

  if (trendState.week !== 'all') {
    scoped = scoped.filter((entry) => entry.week === Number(trendState.week));
  }

  const grouped = new Map();
  scoped.forEach((entry) => {
    const key = trendState.view === 'weekly' ? String(entry.week) : entry.month;
    if (!grouped.has(key)) grouped.set(key, { active: 0, resolved: 0, total: 0 });
    const bucket = grouped.get(key);
    const status = normalizeTicketStatus(entry.ticket.status);
    if (status === 'resolved' || status === 'closed') bucket.resolved += 1;
    if (status === 'open' || status === 'in progress') bucket.active += 1;
    bucket.total += 1;
  });

  const sortedLabels = sortLabels([...grouped.keys()], trendState);
  const labels = sortedLabels.map((key) => {
    if (trendState.view === 'weekly') return `Week ${key}`;
    return monthLabel(key);
  });

  const active = sortedLabels.map((key) => grouped.get(key)?.active || 0);
  const resolved = sortedLabels.map((key) => grouped.get(key)?.resolved || 0);
  const details = sortedLabels.map((key, index) => ({
    key,
    label: labels[index],
    active: active[index],
    resolved: resolved[index],
    total: grouped.get(key)?.total || 0
  }));

  return {
    labels,
    active,
    resolved,
    details,
    availableMonths,
    effectiveMonth
  };
}

function renderLineChart(elId, labels, activeSeries, resolvedSeries) {
  const el = document.getElementById(elId);
  if (!el) return;

  if (!labels.length) {
    el.innerHTML = "<div class='empty-state'><h3>No trend data</h3><p>Ticket activity will appear here when records are available.</p></div>";
    return;
  }

  const width = 640;
  const height = 280;
  const pad = { top: 24, right: 22, bottom: 48, left: 36 };
  const maxValue = Math.max(1, ...activeSeries, ...resolvedSeries);
  const plotW = width - pad.left - pad.right;
  const plotH = height - pad.top - pad.bottom;

  const x = (i) => pad.left + (i * (plotW / Math.max(1, labels.length - 1)));
  const y = (v) => pad.top + (plotH - ((v / maxValue) * plotH));
  const toPath = (series) => series.map((v, i) => `${i === 0 ? 'M' : 'L'} ${x(i)} ${y(v)}`).join(' ');

  const grid = [0, 0.25, 0.5, 0.75, 1].map((r) => {
    const gy = pad.top + (plotH * r);
    return `<line x1='${pad.left}' y1='${gy}' x2='${width - pad.right}' y2='${gy}' stroke='#e2e8f0' stroke-dasharray='3 4'/>`;
  }).join('');

  el.innerHTML = `
    <svg viewBox='0 0 ${width} ${height}' class='viz-svg' role='img' aria-label='Ticket trend chart'>
      ${grid}
      <path d='${toPath(activeSeries)}' fill='none' stroke='#2563eb' stroke-width='2.5' stroke-linecap='round'/>
      <path d='${toPath(resolvedSeries)}' fill='none' stroke='#22c55e' stroke-width='2.5' stroke-linecap='round'/>
      ${labels.map((label, i) => `<text x='${x(i)}' y='${height - 14}' text-anchor='middle' class='viz-axis-label'>${escapeHtml(label)}</text>`).join('')}
      <text x='${pad.left}' y='${pad.top - 8}' class='viz-axis-hint'>0-${maxValue}</text>
    </svg>
    <div class='viz-legend'>
      <span><i class='viz-dot open'></i>active tickets</span>
      <span><i class='viz-dot resolved'></i>resolved or closed</span>
    </div>`;
}

function renderTrendDetails(details) {
  const el = document.getElementById('ticketTrendDetails');
  if (!el) return;

  if (!details.length) {
    el.textContent = 'No ticket trend records for the selected range.';
    return;
  }

  const totalActive = details.reduce((sum, row) => sum + row.active, 0);
  const totalResolved = details.reduce((sum, row) => sum + row.resolved, 0);
  const strongest = [...details].sort((a, b) => (b.active + b.resolved) - (a.active + a.resolved))[0];

  el.innerHTML = `
    <div class='table-wrap'>
      <table>
        <thead><tr><th>Period</th><th>Active</th><th>Resolved</th><th>Total</th></tr></thead>
        <tbody>
          ${details.map((row) => `<tr><td>${escapeHtml(row.label)}</td><td>${row.active}</td><td>${row.resolved}</td><td>${row.total}</td></tr>`).join('')}
        </tbody>
      </table>
    </div>
    <p class='small' style='margin-top:8px'>Active total: ${totalActive} | Resolved total: ${totalResolved} | Highest volume: ${escapeHtml(strongest.label)}</p>`;
}

function renderTrendFilterOptions(availableMonths, selectedMonth) {
  const select = document.getElementById('trendMonthFilter');
  if (!select) return;

  const normalizedSelected = selectedMonth || 'all';
  const options = [`<option value='all'>All months</option>`]
    .concat(availableMonths.map((month) => `<option value='${month}'>${monthLabel(month)}</option>`));
  select.innerHTML = options.join('');
  if (['all', ...availableMonths].includes(normalizedSelected)) {
    select.value = normalizedSelected;
  } else {
    select.value = 'all';
    dashboardState.trend.month = 'all';
  }
}

function updateTrendControlState() {
  const weekFilter = document.getElementById('trendWeekFilter');
  const monthFilter = document.getElementById('trendMonthFilter');
  if (!weekFilter || !monthFilter) return;

  if (dashboardState.trend.view === 'weekly') {
    monthFilter.disabled = false;
    weekFilter.disabled = false;
    return;
  }

  monthFilter.disabled = false;
  weekFilter.disabled = false;
}

function getPriorityCounts(tickets) {
  const counts = { Critical: 0, High: 0, Medium: 0, Low: 0 };
  (Array.isArray(tickets) ? tickets : []).forEach((ticket) => {
    const priority = String(ticket.priority || '').trim();
    if (counts[priority] != null) counts[priority] += 1;
  });
  return counts;
}

function renderPriorityPie(elId, counts) {
  const el = document.getElementById(elId);
  if (!el) return;

  const labels = ['Critical', 'High', 'Medium', 'Low'];
  const colors = { Critical: '#ef4444', High: '#f97316', Medium: '#ca8a04', Low: '#22c55e' };
  const values = labels.map((label) => counts[label] || 0);
  const total = Math.max(1, values.reduce((sum, value) => sum + value, 0));

  let start = 0;
  const cx = 170;
  const cy = 130;
  const radius = 72;

  const arcs = values.map((value, index) => {
    const angle = (value / total) * Math.PI * 2;
    const end = start + angle;
    const x1 = cx + radius * Math.cos(start);
    const y1 = cy + radius * Math.sin(start);
    const x2 = cx + radius * Math.cos(end);
    const y2 = cy + radius * Math.sin(end);
    const large = angle > Math.PI ? 1 : 0;
    const path = value === 0 ? '' : `M ${cx} ${cy} L ${x1} ${y1} A ${radius} ${radius} 0 ${large} 1 ${x2} ${y2} Z`;
    start = end;
    return `<path d='${path}' fill='${colors[labels[index]]}' stroke='#fff' stroke-width='1'/>`;
  }).join('');

  const legends = labels.map((label) => `<span class='viz-priority ${label.toLowerCase()}'>${label}: ${counts[label] || 0}</span>`).join('');

  el.innerHTML = `
    <div class='pie-wrap'>
      <svg viewBox='0 0 340 260' class='viz-svg' role='img' aria-label='Priority distribution chart'>${arcs}</svg>
      <div class='pie-legend'>${legends}</div>
    </div>`;
}

function renderMetricCards() {
  const summary = document.getElementById('summaryCards');
  if (!summary) return;

  const tickets = dashboardState.tickets;
  const monitorSummary = dashboardState.monitoringSummary || {};
  const inventory = dashboardState.inventoryStats || {};

  const activeCount = tickets.filter((ticket) => {
    const status = normalizeTicketStatus(ticket.status);
    return status === 'open' || status === 'in progress';
  }).length;

  const resolvedCount = tickets.filter((ticket) => {
    const status = normalizeTicketStatus(ticket.status);
    return status === 'resolved' || status === 'closed';
  }).length;

  const cards = [
    ['TS', 'Total Assets', Number(inventory.total || 0), 'Registered inventory devices'],
    ['DD', 'Discoverable Devices', Number(monitorSummary.totalDiscovered || 0), 'LAN devices currently visible'],
    ['AT', 'Active Tickets', activeCount, 'Open and in-progress requests'],
    ['RC', 'Resolved Tickets', resolvedCount, 'Resolved and closed requests'],
    ['TC', 'Telemetry Coverage', `${Number(monitorSummary.telemetryAvailableDevices || 0)}/${Number(monitorSummary.totalDiscovered || 0)}`, 'Devices with telemetry stream'],
    ['CL', 'Collector Health', String(monitorSummary.collectorState || 'NOT_ENROLLED').replaceAll('_', ' '), `Last seen ${formatDateTime(monitorSummary.collectorLastSeenAt)}`],
    ['UP', 'Telemetry Updated', monitorSummary.timestamp ? formatDateTime(monitorSummary.timestamp) : 'Never', 'Authoritative server receipt time']
  ];

  summary.innerHTML = cards.map(([icon, label, value, hint]) => `
    <article class='card metric-card'>
      <div class='card-head'><div class='metric-label'>${label}</div><span class='card-icon'>${icon}</span></div>
      <div class='metric-value'>${value}</div>
      <div class='metric-hint'>${escapeHtml(hint)}</div>
    </article>`).join('');
}

function renderOperationalReport() {
  const host = document.getElementById('operationalReportStats');
  if (!host) return;

  const tickets = dashboardState.tickets;
  const monitorSummary = dashboardState.monitoringSummary || {};
  const devices = dashboardState.lanDevices;
  const criticalAlerts = devices.filter((device) => {
    const cpu = Number(device.cpuUsagePercent ?? 0);
    const memory = Number(device.memoryUsagePercent ?? 0);
    return cpu >= 85 || memory >= 90;
  });

  const activeTickets = tickets.filter((ticket) => {
    const status = normalizeTicketStatus(ticket.status);
    return status === 'open' || status === 'in progress';
  }).length;

  const monthToken = dashboardState.trend.month === 'all'
    ? buildTrendData(tickets, { ...dashboardState.trend, view: 'monthly' }).effectiveMonth
    : dashboardState.trend.month;
  const resolvedMonthToken = monthToken === 'all' ? monthKey(new Date()) : monthToken;

  const thisMonthTickets = tickets.filter((ticket) => {
    const date = parseDate(ticket.updatedAt || ticket.createdAt);
    return date ? monthKey(date) === resolvedMonthToken : false;
  });

  const monthResolved = thisMonthTickets.filter((ticket) => {
    const status = normalizeTicketStatus(ticket.status);
    return status === 'resolved' || status === 'closed';
  }).length;

  const resolutionRate = thisMonthTickets.length
    ? `${Math.round((monthResolved / thisMonthTickets.length) * 100)}%`
    : '0%';

  const stats = [
    ['Active Tickets', activeTickets, 'Tickets requiring current handling'],
    ['Ticket Trend', resolutionRate, `${monthLabel(resolvedMonthToken)} resolution rate`],
    ['Operational Alerts', criticalAlerts.length, 'High CPU/memory devices'],
    ['Discoverable Devices', Number(monitorSummary.totalDiscovered || 0), 'Devices seen in LAN discovery']
  ];

  host.innerHTML = stats.map(([label, value, hint]) => `
    <article class='card metric-card'>
      <div class='metric-label'>${label}</div>
      <div class='metric-value'>${value}</div>
      <div class='metric-hint'>${hint}</div>
    </article>`).join('');
}

function renderSystemPerformance() {
  const summaryHost = document.getElementById('systemPerformanceSummary');
  const chartHost = document.getElementById('systemPerformanceChart');
  if (!summaryHost || !chartHost) return;

  const tickets = dashboardState.tickets;
  const monitorSummary = dashboardState.monitoringSummary || {};
  const telemetryDevices = dashboardState.lanDevices.filter((device) => device.telemetryAvailable && !device.stale);
  const average = (key) => telemetryDevices.length
    ? telemetryDevices.reduce((sum, device) => sum + Number(device[key] || 0), 0) / telemetryDevices.length
    : 0;

  const activeTickets = tickets.filter((ticket) => {
    const status = normalizeTicketStatus(ticket.status);
    return status === 'open' || status === 'in progress';
  }).length;

  const totalTickets = Math.max(1, tickets.length);
  const discoverable = Math.max(1, Number(monitorSummary.totalDiscovered || 0));
  const telemetryCoverage = (Number(monitorSummary.telemetryAvailableDevices || 0) / discoverable) * 100;
  const ticketLoad = Math.min(100, (activeTickets / totalTickets) * 100);

  const metrics = [
    { label: 'Device CPU Average', value: average('cpuUsagePercent'), target: 'Reporting devices only', higherIsBetter: false },
    { label: 'Device Memory Average', value: average('memoryUsagePercent'), target: 'Reporting devices only', higherIsBetter: false },
    { label: 'Telemetry Coverage', value: telemetryCoverage, target: 'Above 70%', higherIsBetter: true },
    { label: 'Active Ticket Load', value: ticketLoad, target: 'Lower is better', higherIsBetter: false }
  ].map((metric) => ({ ...metric, value: Math.max(0, Math.min(100, Number(metric.value.toFixed(1)))) }));

  summaryHost.innerHTML = metrics.map((metric) => {
    const health = metric.higherIsBetter
      ? (metric.value >= 70 ? { tone: 'resolved', label: 'Stable' } : metric.value >= 50 ? { tone: 'warning', label: 'Watch' } : { tone: 'open', label: 'Critical' })
      : (metric.value >= 85 ? { tone: 'open', label: 'Critical' } : metric.value >= 70 ? { tone: 'warning', label: 'Watch' } : { tone: 'resolved', label: 'Stable' });
    return `<div class='insight-item'>
      <div class='insight-label'>${metric.label}</div>
      <div class='insight-value'>${metric.value}%</div>
      <div class='small'>Target: ${metric.target}</div>
      <span class='badge ${health.tone}' style='margin-top:6px'>${health.label}</span>
    </div>`;
  }).join('');

  chartHost.innerHTML = `
    <div class='perf-health-list'>
      ${metrics.map((metric) => `
        <div class='perf-health-item'>
          <div class='perf-health-line'>
            <span class='perf-health-label'>${metric.label}</span>
            <span class='perf-health-value'>${metric.value}%</span>
          </div>
          <div class='perf-health-track'>
            <span class='perf-health-fill' style='width:${metric.value}%'></span>
          </div>
        </div>
      `).join('')}
    </div>`;
}

function adminTicketHref(ticketId) {
  return `/ticket-management?ticketId=${encodeURIComponent(ticketId)}`;
}

function renderRecentAdminTickets(tickets) {
  const rows = document.getElementById('recentAdminTicketRows');
  if (!rows) return;

  const priorityOrder = { Critical: 0, High: 1, Medium: 2, Low: 3 };
  const relevant = [...(Array.isArray(tickets) ? tickets : [])]
    .sort((a, b) => {
      const priorityDiff = (priorityOrder[a.priority] ?? 99) - (priorityOrder[b.priority] ?? 99);
      if (priorityDiff !== 0) return priorityDiff;
      const aTime = parseDate(a.updatedAt || a.createdAt)?.getTime() ?? 0;
      const bTime = parseDate(b.updatedAt || b.createdAt)?.getTime() ?? 0;
      return bTime - aTime;
    })
    .slice(0, 8);

  if (!relevant.length) {
    renderTableEmptyState(rows, 5, 'No recent tickets found.');
    return;
  }

  rows.innerHTML = relevant.map((ticket) => `
    <tr>
      <td><a class='ticket-link' href='${adminTicketHref(ticket.id)}' aria-label='Open ticket ${ticket.id}'>#${ticket.id}</a></td>
      <td><a class='ticket-link ticket-link-title monitor-text-wrap' href='${adminTicketHref(ticket.id)}' title='${escapeHtml(ticket.title || '-')}'>${escapeHtml(ticket.title || '-')}</a></td>
      <td>${escapeHtml(ticket.priority || '-')}</td>
      <td><span class='badge ${statusBadgeClass(ticket.status)}'>${readableTicketStatus(ticket.status)}</span></td>
      <td>${formatDateTime(ticket.updatedAt || ticket.createdAt)}</td>
    </tr>
  `).join('');
}

function renderAdminOpsFeed(devices, notifications) {
  const feed = document.getElementById('adminOpsFeed');
  if (!feed) return;

  const criticalAlerts = (Array.isArray(devices) ? devices : [])
    .filter((device) => Number(device.cpuUsagePercent ?? 0) >= 85 || Number(device.memoryUsagePercent ?? 0) >= 90)
    .map((device) => ({
      type: 'critical',
      message: `${device.hostname || device.ipAddress || 'Device'} exceeded threshold (CPU ${Number(device.cpuUsagePercent ?? 0).toFixed(1)}%, Memory ${Number(device.memoryUsagePercent ?? 0).toFixed(1)}%).`,
      createdAt: device.lastSeen || new Date().toISOString()
    }));

  const mappedNotifications = (Array.isArray(notifications) ? notifications : []).map((notification) => ({
    type: String(notification.type || 'info').toLowerCase(),
    message: notification.message || 'Notification received.',
    createdAt: notification.createdAt
  }));

  const allItems = [...criticalAlerts, ...mappedNotifications]
    .sort((a, b) => {
      const aTime = parseDate(a.createdAt)?.getTime() ?? 0;
      const bTime = parseDate(b.createdAt)?.getTime() ?? 0;
      return bTime - aTime;
    })
    .slice(0, 8);

  if (!allItems.length) {
    feed.innerHTML = "<div class='empty-state'><h3>No operational updates</h3><p>Alerts and notifications will appear here.</p></div>";
    return;
  }

  feed.innerHTML = allItems.map((item) => {
    const badgeClass = item.type === 'critical' || item.type === 'error'
      ? 'open'
      : (item.type === 'success' ? 'resolved' : 'in-progress');

    return `<article class='notification-item'>
      <div class='notification-line'>
        <span class='badge ${badgeClass}'>${escapeHtml(item.type)}</span>
        <span class='small'>${formatDateTime(item.createdAt)}</span>
      </div>
      <p>${escapeHtml(item.message)}</p>
    </article>`;
  }).join('');
}

function updateAdminOpsFeedStatus(message, failed = false) {
  const status = document.getElementById('adminOpsFeedStatus');
  if (!status) return;
  status.textContent = message;
  status.classList.toggle('text-danger', failed);
}

function applyTrendRendering() {
  const trendData = buildTrendData(dashboardState.tickets, dashboardState.trend);
  renderTrendFilterOptions(trendData.availableMonths, dashboardState.trend.month);
  updateTrendControlState();

  renderLineChart('ticketTrendChart', trendData.labels, trendData.active, trendData.resolved);
  renderTrendDetails(trendData.details);
}

function setAnalyticsBuffering(isLoading) {
  const summary = document.getElementById('summaryCards');
  const report = document.getElementById('operationalReportStats');
  const trend = document.getElementById('ticketTrendChart');
  const priority = document.getElementById('priorityDistributionChart');
  const perf = document.getElementById('systemPerformanceChart');

  if (isLoading) {
    if (summary) {
      summary.innerHTML = Array.from({ length: 6 }).map(() => `
        <article class='card metric-card analytics-skeleton'>
          <div class='card-head'>
            <div class='skeleton skeleton-text skeleton-label'></div>
            <span class='card-icon skeleton skeleton-icon'></span>
          </div>
          <div class='skeleton skeleton-text skeleton-value'></div>
          <div class='skeleton skeleton-text skeleton-hint'></div>
        </article>`).join('');
    }

    if (report) {
      report.innerHTML = Array.from({ length: 4 }).map(() => `
        <article class='card metric-card analytics-skeleton'>
          <div class='skeleton skeleton-text skeleton-label'></div>
          <div class='skeleton skeleton-text skeleton-value'></div>
          <div class='skeleton skeleton-text skeleton-hint'></div>
        </article>`).join('');
    }

    [trend, priority, perf].forEach((el) => {
      if (!el) return;
      el.classList.add('analytics-buffering');
      el.innerHTML = "<div class='analytics-buffer-msg'><span class='page-splash-spinner' aria-hidden='true'></span><span>Loading analytics...</span></div>";
    });
    return;
  }

  [trend, priority, perf].forEach((el) => el?.classList.remove('analytics-buffering'));
}

function wireTrendControls() {
  const view = document.getElementById('trendViewSelect');
  const month = document.getElementById('trendMonthFilter');
  const week = document.getElementById('trendWeekFilter');
  const sort = document.getElementById('trendSortSelect');

  if (view) {
    view.value = dashboardState.trend.view;
    view.addEventListener('change', () => {
      dashboardState.trend.view = view.value || 'monthly';
      applyTrendRendering();
    });
  }

  if (month) {
    month.addEventListener('change', () => {
      dashboardState.trend.month = month.value || 'all';
      applyTrendRendering();
    });
  }

  if (week) {
    week.value = dashboardState.trend.week;
    week.addEventListener('change', () => {
      dashboardState.trend.week = week.value || 'all';
      applyTrendRendering();
    });
  }

  if (sort) {
    sort.value = dashboardState.trend.sort;
    sort.addEventListener('change', () => {
      dashboardState.trend.sort = sort.value || 'latest';
      applyTrendRendering();
    });
  }
}

async function loadAdminDashboard() {
  const recentRows = document.getElementById('recentAdminTicketRows');
  setAnalyticsBuffering(true);
  if (recentRows) showTableSkeleton(recentRows, { rowCount: 6, columnCount: 5 });

  try {
    const [ticketsPayload, monitorSummary, lanDevices, notifications, inventoryStats, slaAnalytics] = await Promise.all([
      window.aitsmFetchAllPaginated('/api/tickets'),
      fetchJsonOrThrow('/api/monitoring/summary'),
      fetchJsonOrThrow('/api/monitoring/lan-devices'),
      fetchJsonOrThrow('/api/notifications?page=1&pageSize=8'),
      fetchJsonOrThrow('/api/inventory/stats'),
      fetchJsonOrThrow('/api/sla/analytics')
    ]);

    dashboardState.tickets = normalizeTicketList(ticketsPayload);
    dashboardState.monitoringSummary = monitorSummary || null;
    dashboardState.lanDevices = Array.isArray(lanDevices) ? lanDevices : [];
    dashboardState.notifications = Array.isArray(notifications) ? notifications : [];
    dashboardState.inventoryStats = inventoryStats || null;
    dashboardState.slaAnalytics = slaAnalytics || null;

    renderMetricCards();

    const priorities = getPriorityCounts(dashboardState.tickets);
    renderPriorityPie('priorityDistributionChart', priorities);

    applyTrendRendering();
    renderOperationalReport();
    renderSlaIntelligence();

    renderSystemPerformance();

    if (recentRows) clearTableSkeleton(recentRows);
    renderRecentAdminTickets(dashboardState.tickets);
    renderAdminOpsFeed(dashboardState.lanDevices, dashboardState.notifications);
    updateAdminOpsFeedStatus(`Updated ${new Date().toLocaleTimeString()}`);
  } catch (error) {
    const summary = document.getElementById('summaryCards');
    if (summary) summary.innerHTML = `<div class='card'><p class='small'>${escapeHtml(error.message || 'Unable to load dashboard data')}</p></div>`;
    renderPriorityPie('priorityDistributionChart', { Critical: 0, High: 0, Medium: 0, Low: 0 });
    renderLineChart('ticketTrendChart', [], [], []);
    renderTrendDetails([]);
    renderOperationalReport();
    renderSystemPerformance();
    if (recentRows) renderTableErrorState(recentRows, 5, error.message || 'Unable to load recent tickets');
    renderAdminOpsFeed([], []);
    updateAdminOpsFeedStatus('Unable to refresh', true);
  } finally {
    if (recentRows) clearTableSkeleton(recentRows);
    setAnalyticsBuffering(false);
  }
}

async function refreshDashboardFeed() {
  if (dashboardFeedRefreshInFlight || document.hidden) return;
  dashboardFeedRefreshInFlight = true;
  updateAdminOpsFeedStatus('Refreshing…');
  try {
    const notifications = await fetchJsonOrThrow('/api/notifications?page=1&pageSize=8');
    dashboardState.notifications = Array.isArray(notifications?.items) ? notifications.items : [];
    renderAdminOpsFeed(dashboardState.lanDevices, dashboardState.notifications);
    updateAdminOpsFeedStatus(`Updated ${new Date().toLocaleTimeString()}`);
  } catch {
    updateAdminOpsFeedStatus('Refresh failed — retrying', true);
  } finally {
    dashboardFeedRefreshInFlight = false;
  }
}

async function refreshDashboardTelemetry() {
  if (dashboardTelemetryRefreshInFlight || document.hidden) return;
  dashboardTelemetryRefreshInFlight = true;
  try {
    const [monitorSummary, lanDevices] = await Promise.all([
      fetchJsonOrThrow('/api/monitoring/summary'),
      fetchJsonOrThrow('/api/monitoring/lan-devices')
    ]);
    dashboardState.monitoringSummary = monitorSummary || null;
    dashboardState.lanDevices = Array.isArray(lanDevices) ? lanDevices : [];
    renderMetricCards();
    renderSystemPerformance();
    renderAdminOpsFeed(dashboardState.lanDevices, dashboardState.notifications);
  } catch {
    // Keep the last successful snapshot visible during a temporary refresh failure.
  } finally {
    dashboardTelemetryRefreshInFlight = false;
  }
}

function startDashboardTelemetryRefresh() {
  if (dashboardTelemetryRefreshTimer) window.clearInterval(dashboardTelemetryRefreshTimer);
  dashboardTelemetryRefreshTimer = window.setInterval(refreshDashboardTelemetry, DASHBOARD_TELEMETRY_REFRESH_MS);
}

function startDashboardFeedRefresh() {
  if (dashboardFeedRefreshTimer) window.clearInterval(dashboardFeedRefreshTimer);
  dashboardFeedRefreshTimer = window.setInterval(refreshDashboardFeed, DASHBOARD_FEED_REFRESH_MS);
}

document.addEventListener('DOMContentLoaded', () => {
  wireTrendControls();
  loadAdminDashboard();
  startDashboardTelemetryRefresh();
  startDashboardFeedRefresh();
  document.getElementById('refreshAdminOpsFeedBtn')?.addEventListener('click', refreshDashboardFeed);
});

document.addEventListener('visibilitychange', () => {
  if (!document.hidden) {
    refreshDashboardTelemetry();
    refreshDashboardFeed();
  }
});

window.addEventListener('beforeunload', () => {
  if (dashboardTelemetryRefreshTimer) window.clearInterval(dashboardTelemetryRefreshTimer);
  if (dashboardFeedRefreshTimer) window.clearInterval(dashboardFeedRefreshTimer);
});
