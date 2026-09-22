const state = { bootstrap:null, result:null, selectedPlant:null, showModel:'all', excluded:new Set() };
const $ = id => document.getElementById(id);
const fmt = (v, digits=3) => v === null || v === undefined ? '—' : Number(v).toFixed(digits);
const modelName = {saturated:'饱和生长', segmented_linear:'分段线性', monotone_spline:'单调样条'};
async function api(path, options={}) {
  const response = await fetch(path, {headers:{'Content-Type':'application/json'}, ...options});
  if (!response.ok) throw new Error((await response.json().catch(() => ({error:'请求失败'}))).error || '请求失败');
  return response.json();
}
async function loadBootstrap() {
  state.bootstrap = await api('/api/bootstrap');
  state.selectedPlant = state.selectedPlant || state.bootstrap.plants[0].id;
  renderControls(); renderVersion(); renderRuns(); renderPlant();
  if (state.result) renderResult();
}
async function runAnalysis(persist=true) {
  const request = {
    trait: $('trait').value, targetDay: Number($('targetDay').value), persist,
    excludedPlantIds: [...state.excluded]
  };
  state.result = await api('/api/analysis', {method:'POST', body:JSON.stringify(request)});
  if (persist) await loadBootstrap(); else renderResult();
}
function renderControls() {
  const b = state.bootstrap;
  $('plantSelect').innerHTML = b.plants.map(p => `<option value="${p.id}">${p.id} ${p.genotype}/${p.treatment}/${p.batch}${p.reference?' 参照':''}</option>`).join('');
  $('plantSelect').value = state.selectedPlant;
  $('boundaryControls').innerHTML = b.boundaries.map(x => `<div class="card"><b>${x.batch}</b><div class="muted">${x.confirmed ? '已确认：第 '+x.boundaryDay+' 天' : '待确认；同日重叠在第 '+(x.boundaryDay-1)+' 天'}</div><div class="row" style="margin-top:6px"><input type="number" min="1" max="30" value="${x.boundaryDay}" id="boundary-${x.batch}"><button onclick="confirmBoundary('${x.batch}')">确认</button></div></div>`).join('');
  $('referenceControls').innerHTML = b.plants.filter(p => p.reference).map(p => `<label class="checkline"><input type="checkbox" onchange="toggleExclude('${p.id}')" ${state.excluded.has(p.id)?'':'checked'}> ${p.id}</label>`).join('');
}
function renderVersion() {
  const c = state.bootstrap.checksum;
  $('versionCards').innerHTML = [
    ['设计版本', state.bootstrap.designVersionId], ['预处理版本', state.bootstrap.preprocessingVersionId],
    ['植株', c.plantCount], ['原始观测行', c.rawObservationCount],
    ['设计 checksum', `<code>${c.designChecksum.slice(0,12)}</code>`], ['原始数据 checksum', `<code>${c.rawChecksum.slice(0,12)}</code>`]
  ].map(([k,v]) => `<div class="card"><div class="muted">${k}</div><div style="font-weight:700;word-break:break-all">${v}</div></div>`).join('');
}
function renderResult() {
  const r = state.result;
  $('notes').innerHTML = r.notes.map(n => `<div class="note ${n.includes('不可识别') || n.includes('空缺') ? 'warning':''}">${n}</div>`).join('');
  $('coverage').innerHTML = '<h3>数据覆盖</h3><table><tr><th>批次</th><th>原始行</th><th>纳入</th><th>bad</th><th>个体</th><th>天数</th></tr>' +
    r.coverage.map(c => `<tr><td>${c.batch}</td><td>${c.rawRows}</td><td>${c.acceptedRows}</td><td>${c.rejectedRows}</td><td>${c.plants}</td><td>${c.minDay ?? '—'}–${c.maxDay ?? '—'}</td></tr>`).join('') + '</table>';
  $('offsets').innerHTML = r.offsets.map(o => `<div class="card"><span class="pill ${o.status}">${o.status}</span><h3>${o.batch}</h3><div class="muted">${o.reason}</div><p>偏移 ${fmt(o.offset)} ± ${fmt(o.standardError)}</p><p class="muted">95% CI ${fmt(o.ci95Low)} 到 ${fmt(o.ci95High)}；参照 ${o.referencePlantCount} 株，配对 ${o.pairCount}</p></div>`).join('');
  $('cells').innerHTML = '<table><tr><th>基因型</th><th>处理</th><th>批次</th><th>植株</th><th>第 '+r.targetDay+' 天均值</th><th>SE</th><th>状态</th></tr>' +
    r.cells.map(c => `<tr><td>${c.genotype}</td><td>${c.treatment}</td><td>${c.batches.join(', ') || '—'}</td><td>${c.plantCount}</td><td>${fmt(c.mean)}</td><td>${fmt(c.standardError)}</td><td>${c.designGap ? '<span class="pill DESIGN_GAP">设计空缺</span>' : c.singleBatch ? '<span class="pill ESTIMATED_SINGLE_BATCH">单批次</span>' : '<span class="pill ESTIMATED">跨批</span>'}</td></tr>`).join('') + '</table>';
  $('interactions').innerHTML = r.interactions.map(i => `<div class="card"><b>${i.treatment} − ${i.control}</b> <span class="pill ${i.status}">${i.status}</span><p>${i.reason}</p><p>估计 ${i.estimate === null ? '—' : fmt(i.estimate)} ± ${fmt(i.standardError)}；95% CI ${fmt(i.ci95Low)} 到 ${fmt(i.ci95High)}</p></div>`).join('');
  renderPlant();
}
function renderPlant() {
  if (!state.bootstrap) return;
  const plant = state.bootstrap.plants.find(p => p.id === state.selectedPlant);
  $('plantMeta').textContent = plant ? `${plant.id}｜批次 ${plant.batch}｜盘位 ${plant.position}｜${plant.genotype} × ${plant.treatment}${plant.reference ? '｜重叠参照' : ''}` : '';
  const rows = state.bootstrap.effectiveObservations.filter(x => x.observation.plantId === state.selectedPlant);
  $('observationTable').innerHTML = '<tr><th>天</th><th>重复</th><th>相机</th><th>原始质量</th><th>有效质量</th><th>叶面积 原/校</th><th>株高 原/校</th><th>冠幅 原/校</th><th></th></tr>' + rows.map(x =>
    `<tr><td>${x.observation.day}</td><td>${x.observation.replicate}</td><td>${x.observation.cameraEra}</td><td><span class="pill ${x.observation.rawQuality}">${x.observation.rawQuality}</span></td><td><span class="pill ${x.quality}">${x.quality}</span></td><td>${fmt(x.observation.leafArea,2)} / ${fmt(x.adjustedLeafArea,2)}</td><td>${fmt(x.observation.height,2)} / ${fmt(x.adjustedHeight,2)}</td><td>${fmt(x.observation.canopyWidth,2)} / ${fmt(x.adjustedCanopyWidth,2)}</td><td><button class="secondary" onclick="openQuality('${x.observation.id}','${x.quality}')">修正</button></td></tr>`).join('');
  const curve = state.result?.curves.find(c => c.plantId === state.selectedPlant && c.trait === $('trait').value);
  $('modelTabs').innerHTML = ['all','saturated','segmented_linear','monotone_spline'].map(m => `<button class="${state.showModel===m?'active':''}" onclick="state.showModel='${m}';renderPlant()">${m==='all'?'全部候选':modelName[m]}</button>`).join('') + (curve ? ' <span class="muted">AIC 选择：'+modelName[curve.candidates.find(x=>x.selected).model]+'</span>' : '');
  const chartRows = state.result?.adjustedObservations.filter(x => x.observation.plantId === state.selectedPlant) || rows;
  drawChart(chartRows, curve);
}
function drawChart(rows, curve) {
  const width=900,height=360,left=58,right=24,top=24,bottom=44;
  const trait = $('trait').value;
  const raw = rows.filter(x => x.quality !== 'bad').map(x => ({day:x.observation.day, value:trait==='leaf_area'?x.observation.leafArea:trait==='height'?x.observation.height:x.observation.canopyWidth, quality:x.quality}));
  const allValues = raw.map(x=>x.value).concat(curve ? Object.values(curve.fittedValues) : []);
  if (!allValues.length) { $('chart').innerHTML='<text x="40" y="50">暂无可绘制数据</text>'; return; }
  const maxDay = Math.max(14, ...raw.map(x=>x.day)); const minY=0; const maxY=Math.max(...allValues)*1.08;
  const sx = day => left + day * (width-left-right)/maxDay;
  const sy = value => height-bottom - (value-minY)*(height-top-bottom)/(maxY-minY);
  let svg = `<line class="axis" x1="${left}" y1="${height-bottom}" x2="${width-right}" y2="${height-bottom}"/><line class="axis" x1="${left}" y1="${top}" x2="${left}" y2="${height-bottom}"/>`;
  for (let d=0; d<=maxDay; d+=2) svg += `<line x1="${sx(d)}" y1="${height-bottom}" x2="${sx(d)}" y2="${height-bottom+5}" stroke="#7b8a82"/><text x="${sx(d)-8}" y="${height-bottom+20}">${d}</text>`;
  for (let i=0;i<5;i++) { const v=maxY*i/4; svg += `<text x="10" y="${sy(v)+4}">${v.toFixed(0)}</text><line x1="${left}" y1="${sy(v)}" x2="${width-right}" y2="${sy(v)}" stroke="#eef2ef"/>`; }
  const boundary = state.bootstrap.boundaries.find(b => b.batch === state.bootstrap.plants.find(p=>p.id===state.selectedPlant)?.batch);
  if (boundary) svg += `<line class="boundaryline" x1="${sx(boundary.boundaryDay)}" y1="${top}" x2="${sx(boundary.boundaryDay)}" y2="${height-bottom}"/><text x="${sx(boundary.boundaryDay)+4}" y="${top+12}">换机边界</text>`;
  if (curve) {
    const names = state.showModel === 'all' ? curve.candidates.map(x=>x.model) : [state.showModel];
    const days = Object.keys(curve.fittedValues).map(Number).sort((a,b)=>a-b);
    names.forEach((name, idx) => {
      const selected = curve.candidates.find(c => c.model === name && c.selected);
      const points = days.map(d => { const candidate = curve.candidates.find(c=>c.model===name); return `${sx(d)},${sy(predictCandidate(candidate,d))}`; }).join(' ');
      svg += `<polyline class="${selected && state.showModel !== 'all' ? 'selected-curve' : 'candidate'}" points="${points}"/>`;
    });
    if ($('targetDay').value > curve.supportMaxDay) {
      const candidate = curve.candidates.find(c=>c.selected);
      const points = [];
      for(let d=curve.supportMaxDay;d<=$('targetDay').value;d++) points.push(`${sx(d)},${sy(predictCandidate(candidate,d))}`);
      svg += `<polyline class="extrapolation" points="${points.join(' ')}"/>`;
    }
  }
  raw.forEach(x => { svg += `<circle class="raw-dot ${x.quality}" cx="${sx(x.day)}" cy="${sy(x.value)}" r="4"><title>第${x.day}天 ${x.quality}</title></circle>`; });
  $('chart').innerHTML = svg;
}
function predictCandidate(candidate, day) {
  const p = candidate.parameters;
  if (candidate.model === 'saturated') return p.b + p.asymptote * (1 - Math.exp(-p.k * day));
  if (candidate.model === 'segmented_linear') return p.intercept + p.slope1 * Math.min(day,p.breakpoint) + p.slope2 * Math.max(0,day-p.breakpoint);
  const keys = Object.keys(p).filter(k=>k.startsWith('day_')).map(k=>[Number(k.slice(4)),p[k]]).sort((a,b)=>a[0]-b[0]);
  if (day <= keys[0][0]) return keys[0][1]; if (day >= keys.at(-1)[0]) return keys.at(-1)[1];
  const i = keys.findIndex(k => k[0] >= day); const [x0,y0]=keys[i-1],[x1,y1]=keys[i]; return y0+(y1-y0)*(day-x0)/(x1-x0);
}
function renderRuns() {
  $('runs').innerHTML = '<table><tr><th>ID</th><th>时间</th><th>版本</th><th>性状</th><th>目标日</th><th>导出</th></tr>' + state.bootstrap.runs.map(r => `<tr><td>${r.id}</td><td>${new Date(r.createdAt).toLocaleString()}</td><td>D${r.designVersionId}/P${r.preprocessingVersionId}</td><td>${r.trait}</td><td>${r.targetDay}</td><td><a href="/api/runs/${r.id}/export">JSON</a></td></tr>`).join('') + '</table>';
}
async function confirmBoundary(batch) { await api(`/api/batches/${batch}/boundary`, {method:'POST', body:JSON.stringify({boundaryDay:Number($('boundary-'+batch).value)})}); await loadBootstrap(); }
function toggleExclude(id) { if (state.excluded.has(id)) state.excluded.delete(id); else state.excluded.add(id); }
function openQuality(id, quality) { $('qualityObservation').value=id; $('qualityValue').value=quality; $('qualityReason').value=''; $('qualityModal').classList.add('open'); }
async function saveQuality() { await api(`/api/observations/${encodeURIComponent($('qualityObservation').value)}/quality`, {method:'POST', body:JSON.stringify({quality:$('qualityValue').value, reason:$('qualityReason').value})}); $('qualityModal').classList.remove('open'); await loadBootstrap(); }
$('plantSelect').onchange = e => { state.selectedPlant=e.target.value; renderPlant(); };
$('trait').onchange = () => renderPlant(); $('targetDay').onchange = () => renderPlant();
$('runBtn').onclick = () => runAnalysis(true).catch(alert); $('previewBtn').onclick = () => runAnalysis(false).catch(alert); $('refreshBtn').onclick = () => loadBootstrap().catch(alert);
$('closeQualityBtn').onclick = () => $('qualityModal').classList.remove('open'); $('saveQualityBtn').onclick = () => saveQuality().catch(alert);
$('reimportBtn').onclick = async () => { if(confirm('将清空运行、质量修正和边界确认，并重新导入固定 fixture。继续？')) { await api('/api/admin/reimport',{method:'POST'}); location.reload(); } };
$('exportLogsBtn').onclick = () => location.href='/api/logs/export';
loadBootstrap().catch(alert);
