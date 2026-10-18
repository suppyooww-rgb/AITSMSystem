const headers = window.headers || authHeaders;
async function loadNotifications(){
  return fetch('/api/notifications?page=1&pageSize=25',{headers:headers()})
    .then(r=>r.json())
    .then(page => Array.isArray(page?.items) ? page.items : []);
}
