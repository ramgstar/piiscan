// Dashboard client: triggers scans, subscribes to the manager's live SSE stream,
// and renders progress, a per-file log, and a per-pattern confirmed bar chart.
//
// There is only one scan at a time, so the page holds a single persistent
// EventSource to /api/v1/scan/stream. When a run ends the manager completes the
// stream; EventSource reconnects on its own and the manager replays the latest
// progress/summary to the fresh subscriber.
'use strict';

let chart = null;

function el(id) {
    return document.getElementById(id);
}

function fmt(n) {
    return Number(n || 0).toLocaleString();
}

function setBadge(state) {
    const b = el('badge');
    b.textContent = state;
    const cls = state === 'RUNNING' ? 'b-run' : state === 'DONE' ? 'b-done' : 'b-idle';
    b.className = 'badge ' + cls;
}

let toastTimer = null;
function toast(msg) {
    const t = el('toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.classList.remove('show'); }, 3500);
}

el('runBtn').addEventListener('click', runNow);

async function runNow() {
    let res;
    try {
        res = await fetch('/api/v1/scan/run', { method: 'POST' });
    } catch (e) {
        toast('요청 실패: ' + e.message);
        return;
    }
    if (res.status === 202) {
        const body = await res.json();
        el('runId').textContent = body.runId;
        setBadge('RUNNING');
    } else if (res.status === 409) {
        toast('이미 스캔이 실행 중입니다.');
    } else {
        toast('예상치 못한 응답: ' + res.status);
    }
}

// --- live progress -------------------------------------------------------

function applyProgress(p) {
    const total = Number(p.total || 0);
    const completed = Number(p.completed || 0);
    const pct = total > 0 ? Math.round((completed / total) * 100) : 0;
    el('barFill').style.width = pct + '%';
    el('completed').textContent = fmt(completed);
    el('total').textContent = fmt(total);
    el('pct').textContent = pct;
    el('inflight').textContent = fmt(p.inFlight);
    el('stage').textContent = p.stage || '—';
    el('mConfirmed').textContent = fmt(p.confirmed);
    el('mCompleted').textContent = fmt(completed);
    el('mTotal').textContent = fmt(total);
    setBadge('RUNNING');
}

function appendFile(f) {
    const tr = document.createElement('tr');
    const status = f.status || '';
    const cells = [
        { text: f.name || '', cls: '' },
        { text: status, cls: status === 'failed' ? 'st-failed' : 'st-processed' },
        { text: fmt(f.confirmed), cls: '' },
        { text: f.reason || '', cls: 'muted' }
    ];
    cells.forEach(function (c) {
        const td = document.createElement('td');
        td.textContent = c.text;
        if (c.cls) { td.className = c.cls; }
        tr.appendChild(td);
    });
    const body = el('fileBody');
    body.insertBefore(tr, body.firstChild);
}

// The scanner owns the summary JSON shape; read it defensively so a field rename
// on the scanner side degrades gracefully rather than throwing.
function renderSummary(s) {
    setBadge('DONE');
    el('barFill').style.width = '100%';
    el('pct').textContent = 100;

    const patterns = s.patterns || s.byPattern || [];
    const labels = patterns.map(function (p) { return p.id || p.pattern || p.name || '?'; });
    const confirmed = patterns.map(function (p) {
        return Number(p.confirmed != null ? p.confirmed : p.confirmedRows || 0);
    });

    if (patterns.length) {
        el('chart').style.display = 'block';
        if (chart) { chart.destroy(); }
        chart = new Chart(el('chart'), {
            type: 'bar',
            data: {
                labels: labels,
                datasets: [{ label: 'confirmed', data: confirmed, backgroundColor: '#2ecc71' }]
            },
            options: {
                responsive: true,
                scales: {
                    x: { ticks: { color: '#9aa3b2' } },
                    y: { ticks: { color: '#9aa3b2' }, beginAtZero: true }
                },
                plugins: { legend: { labels: { color: '#e6e9ef' } } }
            }
        });

        const tbody = el('summaryBody');
        tbody.innerHTML = '';
        patterns.forEach(function (p) {
            const tr = document.createElement('tr');
            const vals = [
                p.id || p.pattern || '',
                p.name || '',
                fmt(p.confirmed != null ? p.confirmed : p.confirmedRows)
            ];
            vals.forEach(function (v) {
                const td = document.createElement('td');
                td.textContent = v;
                tr.appendChild(td);
            });
            tbody.appendChild(tr);
        });
        el('summaryTable').style.display = 'table';
    }

    if (s.confirmed != null) { el('mConfirmed').textContent = fmt(s.confirmed); }
}

// --- stream --------------------------------------------------------------

function connect() {
    const source = new EventSource('/api/v1/scan/stream');
    source.addEventListener('progress', function (e) { applyProgress(JSON.parse(e.data)); });
    source.addEventListener('file', function (e) { appendFile(JSON.parse(e.data)); });
    source.addEventListener('summary', function (e) { renderSummary(JSON.parse(e.data)); });
    source.addEventListener('end', function () { setBadge('DONE'); });
    source.addEventListener('error', function () {
        // Connection dropped (run ended / server restart). EventSource auto-reconnects.
    });
}

// Seed initial state from /status so a fresh page load isn't blank.
async function init() {
    try {
        const res = await fetch('/api/v1/scan/status');
        const s = await res.json();
        if (s.runId) { el('runId').textContent = s.runId; }
        if (s.progress) {
            applyProgress(typeof s.progress === 'string' ? JSON.parse(s.progress) : s.progress);
        }
        setBadge(s.running ? 'RUNNING' : 'IDLE');
    } catch (ignored) {
        // status unavailable at load; the stream will populate the UI shortly
    }
    connect();
}

init();
