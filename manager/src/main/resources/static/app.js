// Dashboard client: starts scans, subscribes to the job's SSE stream, and
// renders live progress plus a per-pattern confirmed/rejected chart.
'use strict';

let currentJob = null;
let source = null;
let chart = null;

function el(id) {
    return document.getElementById(id);
}

function fmt(n) {
    return Number(n).toLocaleString();
}

function setBadge(status) {
    const b = el('badge');
    b.style.display = 'inline-block';
    b.textContent = status;
    const cls = status === 'DONE' ? 'b-done'
        : (status === 'FAILED' || status === 'STOPPED') ? 'b-fail' : 'b-run';
    b.className = 'badge ' + cls;
}

el('mode').addEventListener('change', function (e) {
    const csv = e.target.value === 'csv';
    el('csvRow').style.display = csv ? 'block' : 'none';
    el('syntheticRow').style.display = csv ? 'none' : 'block';
});

el('startBtn').addEventListener('click', startScan);
el('stopBtn').addEventListener('click', stopScan);

async function startScan() {
    el('err').textContent = '';
    el('summary').style.display = 'none';
    if (chart) {
        chart.destroy();
        chart = null;
    }
    const body = {
        mode: el('mode').value,
        syntheticRows: parseInt(el('rows').value, 10),
        inputCsv: el('csv').value,
        workers: parseInt(el('workers').value, 10),
        batchSize: parseInt(el('batch').value, 10)
    };
    const res = await fetch('/api/v1/scan/start', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(body)
    });
    const job = await res.json();
    currentJob = job.id;
    el('jobId').textContent = job.id;
    el('startBtn').disabled = true;
    el('stopBtn').disabled = false;
    setBadge('RUNNING');
    subscribe(job.id);
}

function subscribe(id) {
    if (source) {
        source.close();
    }
    source = new EventSource('/api/v1/scan/' + id + '/stream');
    source.addEventListener('status', function (e) {
        setBadge(JSON.parse(e.data).status);
    });
    source.addEventListener('progress', function (e) {
        const p = JSON.parse(e.data);
        el('mBatches').textContent = fmt(p.batches);
        el('mValues').textContent = fmt(p.values);
        el('mConfirmed').textContent = fmt(p.confirmed);
    });
    source.addEventListener('result', function (e) {
        render(JSON.parse(e.data));
    });
    source.addEventListener('done', function (e) {
        setBadge('DONE');
        if (e.data && e.data !== '{}') {
            render(JSON.parse(e.data));
        }
        finish();
    });
    source.addEventListener('error', function (e) {
        if (e.data) {
            try {
                el('err').textContent = JSON.parse(e.data).error;
            } catch (ignored) {
                // non-JSON error frame (e.g. connection closed); ignore
            }
        }
    });
}

function finish() {
    el('startBtn').disabled = false;
    el('stopBtn').disabled = true;
    if (source) {
        source.close();
        source = null;
    }
}

async function stopScan() {
    if (!currentJob) {
        return;
    }
    await fetch('/api/v1/scan/' + currentJob, {method: 'DELETE'});
    setBadge('STOPPED');
    finish();
}

function render(r) {
    el('mBatches').textContent = fmt(r.batches);
    el('mValues').textContent = fmt(r.valuesScanned);
    el('mConfirmed').textContent = fmt(r.confirmedRows);

    const labels = r.patterns.map(function (p) { return p.id; });
    const confirmed = r.patterns.map(function (p) { return p.confirmedRows; });
    const rejected = r.patterns.map(function (p) { return p.rejectedRows; });

    if (chart) {
        chart.destroy();
    }
    chart = new Chart(el('chart'), {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [
                {label: 'Confirmed', data: confirmed, backgroundColor: '#2ecc71'},
                {label: 'Rejected', data: rejected, backgroundColor: '#e74c3c'}
            ]
        },
        options: {
            responsive: true,
            scales: {
                x: {ticks: {color: '#9aa3b2'}},
                y: {ticks: {color: '#9aa3b2'}}
            },
            plugins: {legend: {labels: {color: '#e6e9ef'}}}
        }
    });

    const tbody = el('summaryBody');
    tbody.innerHTML = '';
    r.patterns.forEach(function (p) {
        const tr = document.createElement('tr');
        const cells = [p.id, p.name, fmt(p.confirmedRows), fmt(p.rejectedRows)];
        cells.forEach(function (value) {
            const td = document.createElement('td');
            td.textContent = value;
            tr.appendChild(td);
        });
        tbody.appendChild(tr);
    });
    el('summary').style.display = 'table';
}
