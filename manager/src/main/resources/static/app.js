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
    source.addEventListener('end', function () { setBadge('DONE'); loadHistory(); });
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

// =========================================================================
// 결과 이력 탭
// =========================================================================

const tabPanels = { live: el('tab-live'), history: el('tab-history') };
function showTab(name) {
    Object.keys(tabPanels).forEach(function (k) {
        tabPanels[k].style.display = (k === name) ? 'block' : 'none';
    });
    el('tabLiveBtn').classList.toggle('active', name === 'live');
    el('tabHistoryBtn').classList.toggle('active', name === 'history');
    if (name === 'history') { loadHistory(); }
}
el('tabLiveBtn').addEventListener('click', function () { showTab('live'); });
el('tabHistoryBtn').addEventListener('click', function () { showTab('history'); });
el('refreshHistory').addEventListener('click', loadHistory);
el('exportHtml').addEventListener('click', exportHtml);

let historyChart = null;
let currentDetail = null;

function fmtTime(iso) {
    if (!iso) { return '—'; }
    const d = new Date(iso);
    return isNaN(d.getTime()) ? iso : d.toLocaleString();
}

async function loadHistory() {
    let runs = [];
    try {
        const res = await fetch('/api/v1/results');
        runs = await res.json();
    } catch (e) {
        toast('이력 조회 실패: ' + e.message);
        return;
    }
    renderHistoryList(Array.isArray(runs) ? runs : []);
}

function renderHistoryList(runs) {
    const tbody = el('historyBody');
    tbody.innerHTML = '';
    if (!runs.length) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 4; td.className = 'muted'; td.textContent = '완료된 스캔 이력이 없습니다.';
        tr.appendChild(td); tbody.appendChild(tr);
        return;
    }
    runs.forEach(function (r) {
        const files = r.files || {};
        const tr = document.createElement('tr');
        [fmtTime(r.finishedAt || r.startedAt), r.runId || '',
            fmt(files.processed) + ' / ' + fmt(files.failed), fmt(r.confirmedTotal)]
            .forEach(function (v) {
                const td = document.createElement('td'); td.textContent = v; tr.appendChild(td);
            });
        tr.addEventListener('click', function () { openRun(r.runId); });
        tbody.appendChild(tr);
    });
}

async function openRun(runId) {
    let detail;
    try {
        const res = await fetch('/api/v1/results/' + encodeURIComponent(runId));
        if (!res.ok) { toast('리포트를 찾을 수 없습니다.'); return; }
        detail = await res.json();
    } catch (e) {
        toast('리포트 조회 실패: ' + e.message);
        return;
    }
    currentDetail = detail;
    renderDetail(detail);
}

function locText(locations) {
    if (!locations || !locations.length) { return ''; }
    const parts = locations.slice(0, 5).map(function (l) {
        if (l && l.path != null) { return l.path; }
        if (l && l.row != null) { return 'row ' + l.row + (l.col != null ? ' · ' + l.col : ''); }
        return JSON.stringify(l);
    });
    const more = locations.length > 5 ? ' …(+' + (locations.length - 5) + ')' : '';
    return parts.join(', ') + more;
}

function renderDetail(detail) {
    const summary = detail.summary || {};
    const reports = detail.reports || [];
    el('detailEmpty').style.display = 'none';
    el('detailContent').style.display = 'block';
    el('detailTitle').textContent = '리포트 · ' + (summary.runId || '');

    const files = summary.files || {};
    const metrics = [
        ['확정 총계', fmt(summary.confirmedTotal)],
        ['처리 파일', fmt(files.processed)],
        ['실패 파일', fmt(files.failed)],
        ['소요(ms)', fmt(summary.durationMs)]
    ];
    el('detailMetrics').innerHTML = metrics.map(function (m) {
        return '<div class="metric"><div class="n">' + m[1] + '</div><div class="l">' + m[0] + '</div></div>';
    }).join('');

    const byPattern = summary.byPattern || {};
    const labels = Object.keys(byPattern);
    const data = labels.map(function (k) { return Number(byPattern[k] || 0); });
    if (historyChart) { historyChart.destroy(); }
    if (labels.length) {
        historyChart = new Chart(el('historyChart'), {
            type: 'bar',
            data: { labels: labels, datasets: [{ label: 'confirmed', data: data, backgroundColor: '#2ecc71' }] },
            options: {
                responsive: true,
                scales: { x: { ticks: { color: '#9aa3b2' } }, y: { ticks: { color: '#9aa3b2' }, beginAtZero: true } },
                plugins: { legend: { labels: { color: '#e6e9ef' } } }
            }
        });
    }

    const tbody = el('detailFindings');
    tbody.innerHTML = '';
    reports.forEach(function (rep) {
        const fname = (rep.source && rep.source.name) || rep.scanId || '';
        (rep.findings || []).forEach(function (f) {
            const tr = document.createElement('tr');
            [fname, f.patternId || '', f.name || '', fmt(f.confirmedCount), f.maskedSample || '']
                .forEach(function (v) { const td = document.createElement('td'); td.textContent = v; tr.appendChild(td); });
            const locTd = document.createElement('td');
            locTd.className = 'loc'; locTd.textContent = locText(f.locations);
            tr.appendChild(locTd);
            tbody.appendChild(tr);
        });
    });
}

// --- 자체 완결 HTML 내보내기 (오프라인에서도 열림; 차트는 인라인 CSS 막대) ---

function exportHtml() {
    if (!currentDetail) { return; }
    const html = buildReportHtml(currentDetail);
    const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'piiscan-report-' + ((currentDetail.summary && currentDetail.summary.runId) || 'run') + '.html';
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
}

function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
}

function buildReportHtml(detail) {
    const summary = detail.summary || {};
    const reports = detail.reports || [];
    const files = summary.files || {};
    const byPattern = summary.byPattern || {};
    const values = Object.keys(byPattern).map(function (k) { return Number(byPattern[k] || 0); });
    const maxV = Math.max.apply(null, [1].concat(values));
    const bars = Object.keys(byPattern).map(function (k) {
        const v = Number(byPattern[k] || 0);
        const w = Math.round((v / maxV) * 100);
        return '<div class="barrow"><span class="lbl">' + esc(k) + '</span>'
            + '<span class="track"><span class="fill" style="width:' + w + '%"></span></span>'
            + '<span class="val">' + v.toLocaleString() + '</span></div>';
    }).join('');
    let rows = '';
    reports.forEach(function (rep) {
        const fname = (rep.source && rep.source.name) || rep.scanId || '';
        (rep.findings || []).forEach(function (f) {
            rows += '<tr><td>' + esc(fname) + '</td><td>' + esc(f.patternId) + '</td><td>' + esc(f.name)
                + '</td><td class="num">' + Number(f.confirmedCount || 0).toLocaleString() + '</td><td>' + esc(f.maskedSample)
                + '</td><td class="loc">' + esc(locText(f.locations)) + '</td></tr>';
        });
    });
    return '<!DOCTYPE html><html lang="ko"><head><meta charset="UTF-8"><title>piiscan report '
        + esc(summary.runId) + '</title><style>'
        + 'body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;margin:32px;color:#1a1a1a}'
        + 'h1{font-size:1.3rem}h2{font-size:1rem;margin-top:28px}.meta{color:#555;font-size:.85rem}'
        + '.kpis{display:flex;gap:24px;margin:16px 0}.kpi .n{font-size:1.5rem;font-weight:700}.kpi .l{font-size:.75rem;color:#666}'
        + '.barrow{display:flex;align-items:center;gap:10px;margin:4px 0;font-size:.82rem}.barrow .lbl{width:130px}'
        + '.barrow .track{flex:1;background:#eee;border-radius:6px;overflow:hidden;height:14px}'
        + '.barrow .fill{display:block;height:100%;background:#2ecc71}.barrow .val{width:80px;text-align:right}'
        + 'table{border-collapse:collapse;width:100%;font-size:.82rem;margin-top:8px}'
        + 'th,td{border-bottom:1px solid #ddd;text-align:left;padding:6px 8px;vertical-align:top}'
        + 'td.num{text-align:right}td.loc{color:#666;font-size:.76rem}.note{color:#888;font-size:.75rem;margin-top:24px}'
        + '</style></head><body>'
        + '<h1>piiscan 스캔 리포트</h1>'
        + '<div class="meta">run <b>' + esc(summary.runId) + '</b> · ' + esc(summary.finishedAt || summary.startedAt) + '</div>'
        + '<div class="kpis">'
        + '<div class="kpi"><div class="n">' + Number(summary.confirmedTotal || 0).toLocaleString() + '</div><div class="l">확정 총계</div></div>'
        + '<div class="kpi"><div class="n">' + Number(files.processed || 0).toLocaleString() + '</div><div class="l">처리 파일</div></div>'
        + '<div class="kpi"><div class="n">' + Number(files.failed || 0).toLocaleString() + '</div><div class="l">실패 파일</div></div>'
        + '</div>'
        + '<h2>패턴별 확정</h2>' + (bars || '<div class="meta">데이터 없음</div>')
        + '<h2>탐지 상세</h2><table><thead><tr><th>파일</th><th>패턴</th><th>이름</th><th>확정</th><th>마스킹 샘플</th><th>위치</th></tr></thead><tbody>'
        + (rows || '<tr><td colspan="6" class="meta">탐지 없음</td></tr>') + '</tbody></table>'
        + '<div class="note">본 리포트는 마스킹된 샘플·위치·건수만 포함하며 원본 개인정보를 담지 않습니다. — piiscan</div>'
        + '</body></html>';
}

