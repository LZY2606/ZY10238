'use strict';
const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));

const state = {
  state: null, preview: null, design: null, observations: [],
  selectedPlant: null, selectedTrait: null, refExcluded: false
};

async function api(path, opts) {
  const res = await fetch(path, opts);
  if (!res.ok) {
    let msg = res.statusText;
    try { msg = (await res.json()).error || msg; } catch (e) {}
    throw new Error(msg);
  }
  return res.json();
}

async function refreshAll() {
  state.state = await api('/api/state');
  state.preview = await api('/api/analysis/preview');
  state.design = await api('/api/design');
  renderMeta();
  renderPlantSelector();
  await loadObservations();
  renderOffsets();
  renderEffects();
  renderCoverage();
  renderRuns();
  renderWarnings();
}

function renderMeta() {
  const s = state.state, v = s.versions;
  $('#meta').textContent =
    `${s.fixtureVersion} · 设计 ${v.designVersion} · fixture ${v.fixtureVersion} · ` +
    `预处理 ${v.preprocessVersion} · 植株 ${s.nPlants} · 观测 ${s.nObservations} · 修正 ${s.nCorrections} · 运行 ${s.nRuns}` +
    ` · 换机边界 第${s.boundary.switchDay}天 ${s.boundary.confirmed ? '（已确认）' : '（未确认）'}`;
}

function renderWarnings() {
  const box = $('#warnings');
  box.innerHTML = '';
  for (const w of state.preview.warnings || []) {
    const d = document.createElement('div');
    d.className = 'warning';
    d.textContent = '⚠ ' + w;
    box.appendChild(d);
  }
}

function renderPlantSelector() {
  const sel = $('#plantSel');
  const plants = state.design.cells.flatMap(c =>
    c.plants.map(p => ({ ...p, genotype: c.genotype, treatment: c.treatment })));
  sel.innerHTML = '';
  for (const p of plants) {
    const o = document.createElement('option');
    o.value = p.plantId;
    o.textContent = `${p.code} ${p.genotype}/${p.treatment}/${p.batch}${p.reference ? ' [参照]' : ''}`;
    sel.appendChild(o);
  }
  if (!state.selectedPlant || !plants.find(p => p.plantId === state.selectedPlant)) {
    state.selectedPlant = plants[0]?.plantId;
  }
  sel.value = state.selectedPlant;

  const ts = $('#traitSel');
  ts.innerHTML = '';
  for (const t of state.state.traits) {
    const o = document.createElement('option');
    o.value = t.id; o.textContent = `${t.label} (${t.unit})`;
    ts.appendChild(o);
  }
  state.selectedTrait = state.selectedTrait || state.state.traits[0].id;
  ts.value = state.selectedTrait;
}

async function loadObservations() {
  const q = new URLSearchParams({ plantId: state.selectedPlant });
  state.observations = (await api('/api/observations?' + q.toString())).observations;
  renderIndividual();
}

function renderIndividual() {
  const pid = state.selectedPlant, tid = state.selectedTrait;
  const plant = state.preview.plants.find(p => p.plantId === pid);
  const info = state.design.cells.flatMap(c =>
    c.plants.map(p => ({ ...p, genotype: c.genotype, treatment: c.treatment })))
    .find(p => p.plantId === pid);
  $('#plantInfo').textContent = info
    ? `个体 ${info.code}：基因型 ${info.genotype} × 处理 ${info.treatment} × 批次 ${info.batch}${info.reference ? '（重叠参照植株）' : ''}`
    : '';
  drawChart(plant, tid);
  renderObsTable(pid, tid);
}

function drawChart(plant, tid) {
  const trait = state.state.traits.find(t => t.id === tid);
  const fit = plant?.traits.find(t => t.traitId === tid);
  const obs = state.observations.filter(o => o.traitId === tid);
  const W = 900, H = 360, PAD = 46;
  const days = state.state.days;
  const xs = obs.map(o => o.day).concat([0, 32]);
  const ys = obs.map(o => o.rawValue);
  if (fit) for (const m of fit.models) for (const pt of m.curve) ys.push(pt.value);
  const xmin = 0, xmax = 32;
  const ymin = Math.max(0, Math.min(...ys) - 3);
  const ymax = Math.max(...ys) + 3;
  const X = d => PAD + (d - xmin) / (xmax - xmin) * (W - PAD - 14);
  const Y = v => H - PAD - (v - ymin) / (ymax - ymin) * (H - PAD * 2);

  let svg = `<svg width="${W}" height="${H}" xmlns="http://www.w3.org/2000/svg">`;
  svg += `<rect x="${PAD}" y="10" width="${X(28) - PAD}" height="${H - PAD - 10}" fill="#f3f8f4"/>`;
  svg += `<rect x="${X(28)}" y="10" width="${W - 14 - X(28)}" height="${H - PAD - 10}" fill="#eef0f5"/>`;
  svg += `<text x="${(X(28) + W - 14) / 2}" y="24" font-size="11" fill="#667">外推区间（28–32天）</text>`;
  for (let d = 0; d <= 32; d += 4) {
    svg += `<line x1="${X(d)}" y1="${H - PAD}" x2="${X(d)}" y2="12" stroke="#e4ebe5"/>`;
    svg += `<text x="${X(d)}" y="${H - PAD + 16}" font-size="10" text-anchor="middle">${d}</text>`;
  }
  for (let i = 0; i <= 4; i++) {
    const v = ymin + (ymax - ymin) * i / 4;
    svg += `<line x1="${PAD}" y1="${Y(v)}" x2="${W - 14}" y2="${Y(v)}" stroke="#e4ebe5"/>`;
    svg += `<text x="${PAD - 6}" y="${Y(v) + 3}" font-size="10" text-anchor="end">${v.toFixed(1)}</text>`;
  }
  const bd = state.state.boundary.switchDay;
  svg += `<line x1="${X(bd)}" y1="12" x2="${X(bd)}" y2="${H - PAD}" stroke="#c46" stroke-dasharray="4 3"/>`;
  svg += `<text x="${X(bd) + 4}" y="24" font-size="10" fill="#c46">相机更换 第${bd}天</text>`;

  const colors = { saturated: '#245b3b', piecewise: '#2b6cb0', monotone_spline: '#b07a16' };
  if (fit && fit.eligible) {
    for (const m of fit.models) {
      const pts = m.curve.map(pt => `${X(pt.day)},${Y(pt.value)}`).join(' ');
      svg += `<polyline points="${pts}" fill="none" stroke="${colors[m.model]}" stroke-width="${m.model === fit.recommended ? 2.4 : 1.2}" ${m.model !== fit.recommended ? 'opacity="0.55"' : ''}/>`;
    }
  }
  const qColor = { OK: '#245b3b', SUSPECT: '#d4940f', EXCLUDED: '#b03a2e' };
  for (const o of obs) {
    const r = o.quality === 'EXCLUDED' ? 3.5 : 5;
    svg += `<circle cx="${X(o.day)}" cy="${Y(o.rawValue)}" r="${r}" fill="${qColor[o.quality]}" fill-opacity="${o.quality === 'EXCLUDED' ? 0.25 : 0.85}" stroke="${qColor[o.quality]}"/>`;
    if (o.replicate > 0) {
      svg += `<text x="${X(o.day) + 7}" y="${Y(o.rawValue) - 6}" font-size="9" fill="#555">重</text>`;
    }
  }
  svg += `<text x="${W / 2}" y="${H - 4}" font-size="11" text-anchor="middle">成像时间（天）</text>`;
  svg += `<text x="14" y="16" font-size="11">${trait.label}（${trait.unit}）${fit && fit.eligible ? ' · 推荐模型：' + modelLabel(fit.recommended) : ' · ' + (fit?.reason || '无拟合')}</text>`;
  let lx = PAD, ly = 34;
  for (const m of fit?.models || []) {
    svg += `<rect x="${lx}" y="${ly - 9}" width="10" height="3" fill="${colors[m.model]}"/><text x="${lx + 14}" y="${ly - 5}" font-size="10">${m.label} BIC=${m.bic}</text>`;
    lx += 190;
  }
  svg += '</svg>';
  $('#chart').innerHTML = svg;
}

function modelLabel(m) {
  return { saturated: '饱和生长', piecewise: '分段线性', monotone_spline: '单调样条' }[m] || m;
}

function renderObsTable(pid, tid) {
  const tb = $('#obsTable tbody');
  tb.innerHTML = '';
  const rows = state.observations.filter(o => o.traitId === tid)
    .sort((a, b) => a.day - b.day || a.replicate - b.replicate);
  for (const o of rows) {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${o.observationId}</td><td>${o.day}</td><td>${o.rawValue.toFixed(2)}</td>
      <td>${o.camera}</td><td>${o.replicate}</td>
      <td class="q-${o.quality}">${o.quality}</td><td></td>`;
    const td = tr.lastElementChild;
    for (const q of ['OK', 'SUSPECT', 'EXCLUDED']) {
      if (q === o.quality) continue;
      const b = document.createElement('button');
      b.className = 'small'; b.textContent = q;
      b.onclick = async () => {
        const reason = prompt(`把观测 #${o.observationId} 标记为 ${q} 的依据？`, q === 'EXCLUDED' ? '人工复核剔除' : '人工复核恢复');
        if (reason === null) return;
        await api('/api/quality?observationId=' + o.observationId, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ quality: q, reason })
        });
        await refreshAll();
      };
      td.appendChild(b);
    }
    tb.appendChild(tr);
  }
}

function renderOffsets() {
  const p = state.preview;
  const traitName = Object.fromEntries(state.state.traits.map(t => [t.id, `${t.label} (${t.unit})`]));
  $('#cameraOffset').innerHTML = p.cameraOffsets.map(o => `
    <div class="card">
      <strong>${traitName[o.traitId]}</strong>
      <span class="badge ${o.identifiable ? 'ok' : 'na'}">${o.identifiable ? '可识别' : '不可识别'}</span>
      ${o.identifiable
        ? `新机−旧机偏移 = <b>${o.estimate.toFixed(2)}</b>（SE ${o.se?.toFixed(2) ?? '—'}，配对数 ${o.nPairs}）`
        : ''}
      <div style="font-size:12px;color:#556;margin-top:4px">${o.reason}</div>
      ${o.pairs.length ? `<table class="data"><thead><tr><th>参照</th><th>天</th><th>旧相机</th><th>新相机</th><th>差</th></tr></thead>
        <tbody>${o.pairs.map(x => `<tr><td>${x.code}</td><td>${x.day}</td><td>${x.oldValue.toFixed(2)}</td><td>${x.newValue.toFixed(2)}</td><td>${x.delta.toFixed(2)}</td></tr>`).join('')}</tbody></table>` : ''}
    </div>`).join('');
  $('#batchOffset').innerHTML = p.batchOffsets.map(o => `
    <div class="card">
      <strong>${traitName[o.traitId]}</strong>
      <span class="badge ${o.identifiable ? 'ok' : 'na'}">${o.identifiable ? '可识别' : '不可识别'}</span>
      ${o.identifiable
        ? `非锚点批次 − ${o.anchorBatch} = <b>${o.estimate.toFixed(2)}</b>（SE ${o.se?.toFixed(2) ?? '—'}，共有组合 ${o.nSharedCells}）`
        : ''}
      <div style="font-size:12px;color:#556;margin-top:4px">${o.reason}</div>
    </div>`).join('');
}

function renderEffects() {
  const genotypes = state.state.genotypes.map(g => g.id);
  const treatments = state.state.treatments.map(t => t.id);
  const cellIndex = {};
  for (const c of state.preview.cells) cellIndex[c.genotype + '|' + c.treatment] = c;
  const matrix = genotypes.map(g =>
    `<tr><th>${g}</th>` + treatments.map(t => {
      const c = cellIndex[g + '|' + t];
      if (!c) return `<td class="cell-box gap">设计空缺<br><small>${g} × ${t} 无观测</small></td>`;
      const cls = c.status === 'single_batch' ? 'cell-box single' : 'cell-box';
      return `<td><div class="${cls}">${c.batches.join('/')} · ${c.plants.length} 株<br>
        <small>${c.status === 'single_batch' ? '单批次（与批次混淆）' : '跨批次平衡'}</small></div></td>`;
    }).join('') + '</tr>').join('');
  $('#designMatrix').innerHTML = `
    <div class="legend"><span><span class="swatch" style="background:#dff0e2"></span>跨批次</span>
      <span><span class="swatch" style="background:#fdf6e0;border:1px solid #d9c27a"></span>仅单批次</span>
      <span><span class="swatch" style="background:#fbe4df"></span>设计空缺</span></div>
    <table class="data"><thead><tr><th>基因型＼处理</th>${treatments.map(t => `<th>${t}</th>`).join('')}</tr></thead>
      <tbody>${matrix}</tbody></table>`;

  const traitName = Object.fromEntries(state.state.traits.map(t => [t.id, t.label]));
  const grouped = {};
  for (const e of state.preview.effects) (grouped[e.traitId] ||= []).push(e);
  $('#effects').innerHTML = Object.entries(grouped).map(([tid, list]) => `
    <div class="card"><strong>${traitName[tid]}</strong>
      <table class="data"><thead><tr><th>对比</th><th>类型</th><th>估计（AUC差）</th><th>SE</th><th>状态</th><th>说明</th></tr></thead>
        <tbody>${list.map(e => `<tr>
          <td>${e.key}</td><td>${e.kind === 'interaction' ? '基因型×处理互作用' : '处理效应'}</td>
          <td>${e.estimate === null ? '—' : e.estimate.toFixed(2)}</td>
          <td>${e.se === null ? '—' : e.se.toFixed(2)}</td>
          <td><span class="badge ${e.status === 'ok' ? 'ok' : e.status === 'design_gap' ? 'na' : 'warn'}">${statusLabel(e.status)}</span></td>
          <td style="font-size:11px;color:#556">${e.reason}</td></tr>`).join('')}
        </tbody></table></div>`).join('');
}

function statusLabel(s) {
  return { ok: '可估计', design_gap: '设计空缺', insufficient_data: '数据不足', single_batch_confounded: '单批次混淆' }[s] || s;
}

function renderCoverage() {
  const tb = $('#coverageTable tbody');
  tb.innerHTML = '';
  for (const c of state.preview.coverage) {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${c.code}</td><td>${c.genotype}</td><td>${c.treatment}</td><td>${c.batch}</td>
      <td>${c.reference ? '是' : ''}</td><td>${c.traitId}</td>
      <td>${c.nTotal}</td><td>${c.nOk}</td><td>${c.nSuspect}</td><td>${c.nExcluded}</td>
      <td>${c.minDay}–${c.maxDay}天</td><td>${c.nDays}</td>`;
    tb.appendChild(tr);
  }
}

async function renderRuns() {
  const runs = (await api('/api/runs')).runs;
  const tb = $('#runsTable tbody');
  tb.innerHTML = '';
  for (const r of runs) {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${r.runId}</td><td>${r.label}</td><td>${r.createdAt.replace('T', ' ').slice(0, 19)}</td>
      <td>${r.designVersion}</td><td>${r.fixtureVersion}</td><td>${r.preprocessVersion}</td><td></td>`;
    const td = tr.lastElementChild;
    const a = document.createElement('a');
    a.href = '/api/runs/' + r.runId + '/export';
    a.textContent = '导出 CSV ';
    a.className = 'small';
    td.appendChild(a);
    const v = document.createElement('button');
    v.className = 'small'; v.textContent = '查看 JSON';
    v.onclick = () => window.open('/api/runs/' + r.runId, '_blank');
    td.appendChild(v);
    tb.appendChild(tr);
  }
}

// ---- events ----
$$('.tabs button').forEach(b => b.onclick = () => {
  $$('.tabs button').forEach(x => x.classList.remove('active'));
  $$('.tab').forEach(x => x.classList.remove('active'));
  b.classList.add('active');
  $('#tab-' + b.dataset.tab).classList.add('active');
});

$('#plantSel').onchange = async (e) => { state.selectedPlant = e.target.value; await loadObservations(); };
$('#traitSel').onchange = (e) => { state.selectedTrait = e.target.value; renderIndividual(); };

$('#btnBoundary').onclick = async () => {
  const s = state.state;
  const day = parseInt(prompt('确认相机更换发生在第几天？（必须是成像日）', s.boundary.switchDay), 10);
  if (!s.days.includes(day)) { alert('必须落在成像日内：' + s.days.join(',')); return; }
  await api('/api/boundary', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ switchDay: day, confirmed: true })
  });
  await refreshAll();
};

$('#btnToggleRef').onclick = async () => {
  state.refExcluded = !state.refExcluded;
  await api('/api/reference-toggle', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ excludeReferencePlants: state.refExcluded })
  });
  $('#btnToggleRef').textContent = state.refExcluded ? '恢复重叠参照' : '移除重叠参照';
  await refreshAll();
};

$('#btnReplay').onclick = () => window.location.href = '/api/replay';

$('#btnReseed').onclick = async () => {
  if (!confirm('将清空质量修正、运行记录与全部观测，并从固定 fixture 重新导入。继续？')) return;
  await api('/api/reseed', { method: 'POST' });
  state.refExcluded = false;
  $('#btnToggleRef').textContent = '移除重叠参照';
  state.selectedPlant = null;
  await refreshAll();
};

$('#btnRun').onclick = async () => {
  const label = $('#runLabel').value || '分析运行';
  const r = await api('/api/runs', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ label })
  });
  alert('已固化运行记录 #' + r.runId + '，可在“分析运行”中导出 CSV/JSON');
  await refreshAll();
};

refreshAll().catch(e => alert('加载失败：' + e.message));
