'use strict';
const state = { head: null, selected: null, view: null, versions: [], overlays: new Set(),
  xmin: null, xmax: null, sharedPeakSet: new Set() };

async function api(path, opts) {
  const res = await fetch(path, Object.assign({ headers: { 'Content-Type': 'application/json' } }, opts));
  const text = await res.text();
  const body = text ? JSON.parse(text) : {};
  if (!res.ok) throw Object.assign(new Error(body.message || JSON.stringify(body)), { status: res.status, body });
  return body;
}
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const fmt3 = x => x == null ? '-' : Number(x).toFixed(4);
const fmt6 = x => x == null ? '-' : Number(x).toFixed(6);

async function refresh() {
  state.versions = await api('/api/versions');
  const h = await api('/api/head');
  state.head = h.head;
  document.getElementById('headTag').textContent = 'v' + state.head;
  document.getElementById('cmpNew').value = state.head;
  renderTree();
  await loadView(state.head);
  await loadQuarantine();
}

function renderTree() {
  const ul = document.getElementById('versionTree');
  ul.innerHTML = '';
  for (const v of state.versions) {
    const li = document.createElement('li');
    if (v.id === state.selected) li.className = 'active';
    li.innerHTML = `<span class="tag ${v.type}">v${v.id}</span><span class="tag ${v.type}">${v.type}</span>
      <span title="${esc(v.label)}" style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(v.label)}</span>`;
    li.onclick = () => { state.selected = v.id; loadView(v.id); renderTree(); };
    ul.appendChild(li);
  }
}

async function loadView(version) {
  state.selected = version;
  const v = await api('/api/view/' + version);
  state.view = v;
  state.sharedPeakSet = new Set((v.conflicts || []).flatMap(c => c.sharedPeakKeys));
  if (state.xmin == null) resetZoom();
  renderCandidates();
  renderConflicts();
  renderHistory();
  renderOverlaySelect();
  draw();
  document.getElementById('conflictCount').style.display = v.conflicts.length ? 'inline-block' : 'none';
  document.getElementById('conflictCount').textContent = v.conflicts.length;
}

async function loadQuarantine() {
  const el = document.getElementById('quarantine');
  const ingestVs = state.versions.filter(x => x.type === 'INGEST' && x.importId != null);
  if (!ingestVs.length) { el.innerHTML = '<span class="muted">暂无导入</span>'; return; }
  let html = '';
  for (const v of ingestVs) {
    const errors = await api('/api/imports/' + v.importId + '/errors');
    html += `<div class="muted">v${v.id} · ${esc(v.label)}</div>`;
    if (!errors.length) html += '<div class="ok">无损坏行</div>';
    for (const e of errors) {
      html += `<div class="card"><div>第 ${e.lineNo} 行：<span class="err">${esc(e.error)}</span></div>
        <div class="mono muted">${esc(e.raw)}</div></div>`;
    }
  }
  el.innerHTML = html;
}

function showTab(name) {
  for (const t of ['cand','conflict','history','compare','evidence']) {
    document.getElementById('pane-' + t).style.display = t === name ? 'block' : 'none';
    document.getElementById('tab-' + t).classList.toggle('on', t === name);
  }
  if (name === 'evidence') renderEvidence();
  if (name === 'history') renderHistory();
  if (name === 'conflict') renderConflicts();
}

function candidateOf(id) { return state.view.candidates.find(c => c.candidateId === id); }

function renderCandidates() {
  const pane = document.getElementById('pane-cand');
  const cs = state.view.candidates || [];
  if (!cs.length) { pane.innerHTML = '<div class="muted">该版本无候选</div>'; return; }
  pane.innerHTML = cs.map(c => {
    const e = c.explanation;
    const miss = e.missingIsotopes.length ? `缺失同位素 M+${[...e.missingIsotopes].join(' M+')}（非冲突）` : '同位素完整';
    return `<div class="card ${c.status}" id="card-${cid(c)}">
      <div><b>${esc(c.compoundName)}</b> <span class="mono muted">${esc(c.adduct)} z=${c.charge} ${c.polarity}</span></div>
      <div>理论 m/z <span class="mono">${fmt6(c.mzTheoretical)}</span> · 评分 <b>${c.score}</b>
        <span class="pill ${c.status}">${statusLabel(c.status)}</span></div>
      <div class="muted mono">锚峰 ${esc(c.monoisotopicPeak || '-')} · Δ ${e.monoisotopicErrorPpm?.toFixed(2)} ppm / ${e.monoisotopicErrorDa?.toFixed(5)} Da</div>
      <div class="muted">${miss} · 规则 v${e.ruleVersion} · 校准 v${e.calibrationVersion}</div>
      <div class="row" style="margin-top:5px">
        <button onclick="toggleOverlay('${esc(c.candidateId)}')">叠加</button>
        <button onclick="showEvidenceFor('${esc(c.candidateId)}')">证据链</button>
        <button class="primary" onclick="adjud('${esc(c.candidateId)}','accept')" ${c.status==='accepted'?'disabled':''}>接受</button>
        <button class="danger" onclick="adjud('${esc(c.candidateId)}','reject')" ${c.status==='rejected'?'disabled':''}>驳回</button>
      </div></div>`;
  }).join('');
}

function cid(id) { return id.replace(/[^A-Za-z0-9]/g, '_'); }
function statusLabel(s) { return {proposed:'待裁定', accepted:'已接受', rejected:'已驳回', conflict:'争用'}[s] || s; }

function renderEvidence() { showEvidenceFor(state._evidenceId); }
function showEvidenceFor(candidateId) {
  state._evidenceId = candidateId;
  showTab('evidence');
  const c = candidateOf(candidateId);
  const pane = document.getElementById('pane-evidence');
  if (!c) { pane.innerHTML = '<div class="muted">无候选</div>'; return; }
  const e = c.explanation;
  const rows = e.isotopes.map(iso => `<tr>
    <td>M+${iso.index}</td><td class="mono">${fmt6(iso.expectedMz)}</td>
    <td class="mono">${iso.peakKey ? esc(iso.peakKey) : '<span class="muted">未观测（低强度缺失）</span>'}</td>
    <td>${iso.errorPpm == null ? '-' : iso.errorPpm.toFixed(2)}</td>
    <td>${iso.errorDa == null ? '-' : iso.errorDa.toFixed(5)}</td>
    <td>${iso.spacingDa == null ? '-' : iso.spacingDa.toFixed(4)}</td>
    <td>${iso.observedRatio == null ? '-' : (iso.observedRatio*100).toFixed(1)+'%'}</td>
    <td>${(iso.expectedRatio*100).toFixed(1)}%</td></tr>`).join('');
  pane.innerHTML = `<div class="card">
    <b>${esc(c.compoundName)} · ${esc(c.adduct)}</b>
    <div class="evidence">
      <div>使用观测峰：${e.usedPeakKeys.map(k => `<code>${esc(k)}</code>`).join(' ') || '<span class="muted">无</span>'}</div>
      <div>单同位素质量误差：<code>${e.monoisotopicErrorPpm?.toFixed(3)} ppm</code> / <code>${e.monoisotopicErrorDa?.toFixed(6)} Da</code></div>
      <div>保留时间：观测 <code>${e.observedRt}</code>，窗口 <code>[${e.rtWindow.join(', ')}]</code>；电荷 z=<code>${e.charge}</code>；极性 <code>${e.polarity}</code></div>
      <div>依据规则版本 <code>v${e.ruleVersion}</code> 与校准版本 <code>v${e.calibrationVersion}</code></div>
      <div>${e.scoreNotes.map(n => '• ' + esc(n)).join('<br>')}</div>
    </div>
    <table><thead><tr><th>同位素</th><th>理论m/z</th><th>观测峰</th><th>ppm</th><th>Da</th><th>间距×z</th><th>观测比</th><th>期望比</th></tr></thead>
    <tbody>${rows}</tbody></table>
    <div class="muted">缺失同位素位置：${e.missingIsotopes.length ? e.missingIsotopes.map(i=>'M+'+i).join('、') : '无'}；缺失低强度同位素不与峰争用冲突混同。</div>
  </div>`;
}

function renderConflicts() {
  const pane = document.getElementById('pane-conflict');
  const cs = state.view.conflicts || [];
  if (!cs.length) { pane.innerHTML = '<div class="ok">当前版本无共享峰冲突。</div>'; return; }
  pane.innerHTML = cs.map(c => `<div class="card">
    <div><b>${esc(c.compoundA)}</b> ⇄ <b>${esc(c.compoundB)}</b></div>
    <div class="muted">最小共享峰集合（峰占用）：</div>
    <div>${c.sharedPeakKeys.map(k => `<code>${esc(k)}</code>`).join(' ')}</div>
    <div class="muted mono">${esc(c.candidateA)}<br>${esc(c.candidateB)}</div>
  </div>`).join('');
}

async function renderHistory() {
  const pane = document.getElementById('pane-history');
  const hist = await api('/api/history/' + state.selected);
  if (!hist.length) { pane.innerHTML = '<div class="muted">该分支尚无裁定。</div>'; return; }
  pane.innerHTML = hist.map(h => `<div class="card">
    <div><span class="pill ${h.decision==='accept'?'accepted':'rejected'}">${h.decision==='accept'?'接受':'驳回'}</span>
      v${h.versionId} · ${esc(h.editor)}</div>
    <div class="mono muted">${esc(h.candidateId)}</div>
    <div class="muted">hash ${esc(h.candidateHash)} · ${esc(h.createdAt)}</div>
    <div>${esc(h.reason)}</div></div>`).join('');
}

function renderOverlaySelect() {
  const sel = document.getElementById('overlaySelect');
  sel.innerHTML = (state.view.candidates || []).map(c =>
    `<option value="${esc(c.candidateId)}" ${state.overlays.has(c.candidateId)?'selected':''}>${esc(c.compoundName)} ${esc(c.adduct)}</option>`).join('');
}
function toggleOverlay(id) {
  if (state.overlays.has(id)) state.overlays.delete(id); else state.overlays.add(id);
  renderOverlaySelect(); draw();
}
function clearOverlay() { state.overlays.clear(); renderOverlaySelect(); draw(); }

async function adjud(candidateId, decision) {
  const reason = prompt('裁定理由（' + (decision === 'accept' ? '接受' : '驳回') + '）', decision === 'accept' ? '证据充分' : '证据不足');
  if (reason === null) return;
  try {
    const r = await api('/api/adjudicate', { method: 'POST', body: JSON.stringify({
      baseVersion: state.selected, candidateId, decision, editor: currentEditor(), reason }) });
    await refresh(); state.selected = r.versionId; await loadView(r.versionId); renderTree();
  } catch (err) {
    if (err.status === 409) alert('并发版本冲突：' + (err.body.message || '') + '\n请刷新后基于最新分支头裁定。');
    else if (err.status === 422) alert('裁定被规则拒绝：\n' + (err.body.reasons || []).join('\n'));
    else alert('失败：' + err.message);
  }
}
function currentEditor() { return document.getElementById('ingestEditor').value || 'analyst'; }

const canvas = document.getElementById('spec');
const ctx = canvas.getContext('2d');
function resetZoom() {
  const peaks = (state.view?.peaks || []);
  if (!peaks.length) { state.xmin = 100; state.xmax = 250; return; }
  const mzs = peaks.map(p => p.mz);
  state.xmin = Math.min(...mzs) - 2; state.xmax = Math.max(...mzs) + 2;
}
function peakColor(p) {
  if (p.contaminant) return '#555f76';
  if (state.sharedPeakSet.has(p.peakKey)) return '#ff6b6b';
  return '#7fb0ff';
}
function draw() {
  const W = canvas.width, H = canvas.height, padL = 58, padB = 34, padT = 10, padR = 10;
  ctx.clearRect(0,0,W,H);
  const peaks = (state.view?.peaks || []).filter(p => p.mz >= state.xmin && p.mz <= state.xmax);
  const maxI = Math.max(1, ...peaks.map(p => p.intensity));
  const x = mz => padL + (mz - state.xmin) / (state.xmax - state.xmin) * (W - padL - padR);
  const y = i => H - padB - (i / maxI) * (H - padB - padT);

  ctx.strokeStyle = '#222d47'; ctx.lineWidth = 1; ctx.beginPath();
  for (let i=0;i<=5;i++) { const yy = padT + i*(H-padB-padT)/5; ctx.moveTo(padL,yy); ctx.lineTo(W-padR,yy); }
  ctx.stroke();
  ctx.fillStyle = '#8fa0c0'; ctx.font = '10px monospace';
  for (let i=0;i<=5;i++) { const v = maxI*(1-i/5); ctx.fillText((v>=1000?(v/1000).toFixed(0)+'k':v.toFixed(0)), 6, padT+i*(H-padB-padT)/5+4); }
  for (let i=0;i<=8;i++) { const m = state.xmin + (state.xmax-state.xmin)*i/8; ctx.fillText(m.toFixed(1), x(m)-10, H-12); }
  ctx.strokeStyle = '#3a4a6e'; ctx.beginPath(); ctx.moveTo(padL,padT); ctx.lineTo(padL,H-padB); ctx.lineTo(W-padR,H-padB); ctx.stroke();

  ctx.lineWidth = 1.6;
  for (const p of peaks) {
    ctx.strokeStyle = peakColor(p); ctx.beginPath(); ctx.moveTo(x(p.mz), H-padB); ctx.lineTo(x(p.mz), y(p.intensity)); ctx.stroke();
  }

  const acceptedKeys = new Set((state.view?.candidates||[]).filter(c=>c.status==='accepted').flatMap(c=>c.usedPeakKeys));
  for (const p of peaks) {
    if (acceptedKeys.has(p.peakKey)) { ctx.strokeStyle='#3ddc84'; ctx.lineWidth=2.4; ctx.beginPath(); ctx.moveTo(x(p.mz),H-padB); ctx.lineTo(x(p.mz),y(p.intensity)); ctx.stroke(); }
  }

  const overlayColors = ['#ffd166','#b58cff','#7fe0c8','#ff9e7a','#7fd4ff'];
  let ci = 0;
  for (const id of state.overlays) {
    const c = candidateOf(id); if (!c) continue;
    const col = overlayColors[ci++ % overlayColors.length];
    for (const iso of c.explanation.isotopes) {
      const xx = x(iso.expectedMz);
      ctx.strokeStyle = col; ctx.setLineDash([4,3]); ctx.lineWidth = 1.4;
      ctx.beginPath(); ctx.moveTo(xx, padT); ctx.lineTo(xx, H-padB); ctx.stroke(); ctx.setLineDash([]);
      ctx.fillStyle = col; ctx.font = '9px monospace'; ctx.fillText('M+'+iso.index+(iso.missing?'(缺)':''), xx+2, padT+11+ci*11);
    }
  }
  ctx.fillStyle = '#e6ecf8'; ctx.font = '11px sans-serif';
  ctx.fillText(`m/z ${state.xmin.toFixed(2)} – ${state.xmax.toFixed(2)} · v${state.selected} · ${peaks.length} 峰`, padL+6, padT+12);
}

canvas.addEventListener('wheel', e => {
  e.preventDefault();
  const rect = canvas.getBoundingClientRect();
  const mx = state.xmin + (e.clientX-rect.left)/rect.width*(state.xmax-state.xmin) * (canvas.width/canvas.width) - 0;
  const W = canvas.width, padL = 58, padR = 10;
  const fx = (e.clientX - rect.left)/rect.width;
  const frac = Math.max(0,Math.min(1,(fx*W-padL)/(W-padL-padR)));
  const mAt = state.xmin + frac*(state.xmax-state.xmin);
  const f = e.deltaY > 0 ? 1.15 : 0.87;
  state.xmin = mAt - (mAt-state.xmin)*f; state.xmax = mAt + (state.xmax-mAt)*f;
  draw();
}, { passive:false });
let drag = null;
canvas.addEventListener('mousedown', e => { drag = { x: e.clientX, xmin: state.xmin, xmax: state.xmax }; });
canvas.addEventListener('mousemove', e => {
  if (!drag) return;
  const rect = canvas.getBoundingClientRect();
  const dx = (e.clientX-drag.x)/rect.width*(drag.xmax-drag.xmin);
  state.xmin = drag.xmin - dx; state.xmax = drag.xmax - dx; draw();
});
window.addEventListener('mouseup', () => drag = null);
canvas.addEventListener('dblclick', () => { resetZoom(); draw(); });
canvas.addEventListener('click', e => {
  if (drag && Math.abs(e.clientX-drag.x)>3) return;
  const rect = canvas.getBoundingClientRect();
  const W = canvas.width, padL = 58, padR = 10;
  const frac = ((e.clientX-rect.left)/rect.width*W-padL)/(W-padL-padR);
  const mAt = state.xmin + frac*(state.xmax-state.xmin);
  const p = (state.view?.peaks||[]).slice().sort((a,b)=>Math.abs(a.mz-mAt)-Math.abs(b.mz-mAt))[0];
  if (!p || Math.abs(p.mz-mAt) > (state.xmax-state.xmin)*0.01) return;
  const usedBy = (state.view.candidates||[]).filter(c=>c.usedPeakKeys.includes(p.peakKey)).map(c=>c.compoundName+' '+c.adduct);
  document.getElementById('peakInfo').style.display='block';
  document.getElementById('annPeak').value = p.peakKey;
  document.getElementById('peakInfo').innerHTML =
    `<b class="mono">${esc(p.peakKey)}</b><br>
     校准后 m/z ${fmt6(p.mz)}（原始 ${fmt6(p.rawMz)}）<br>
     强度 ${p.intensity} · RT ${p.scan} · ${p.polarity}${p.contaminant?' · <span class="err">污染</span>':''}<br>
     参与候选：${usedBy.length?usedBy.map(esc).join('、'):'<span class="muted">无</span>'}`;
});
document.getElementById('annType').addEventListener('change', e => {
  document.getElementById('splitFields').style.display = e.target.value === 'SPLIT' ? 'block' : 'none';
});

async function doIngest() {
  const content = document.getElementById('ingestText').value.trim();
  if (!content) return alert('请粘贴峰表 CSV（表头含 m/z,intensity,scan,polarity）');
  try {
    const r = await api('/api/ingest', { method:'POST', body: JSON.stringify({
      filename: document.getElementById('ingestName').value, content,
      parent: state.selected, editor: currentEditor() }) });
    document.getElementById('ingestResult').innerHTML =
      `<div class="ok">新版本 v${r.versionId}：接受 ${r.accepted}（新峰 ${r.newPeaks}），隔离 ${r.quarantined.length} 行</div>` +
      r.quarantined.map(q=>`<div class="err">行${q.lineNo}: ${esc(q.error)}</div>`).join('');
    await refresh(); state.selected = r.versionId; await loadView(r.versionId); renderTree();
  } catch (err) { document.getElementById('ingestResult').innerHTML = `<div class="err">${esc(err.message)}</div>`; }
}

async function doAnnotation() {
  const peakKey = document.getElementById('annPeak').value.trim();
  if (!peakKey) return alert('请填写峰键（可点击谱图峰自动填充）');
  const type = document.getElementById('annType').value;
  const body = { peakKey, type, reason: document.getElementById('annReason').value, parent: state.selected, editor: currentEditor() };
  if (type === 'SPLIT') {
    body.replacementMz = parseFloat(document.getElementById('annMz').value);
    body.replacementIntensity = parseFloat(document.getElementById('annInt').value);
    if (!isFinite(body.replacementMz)) return alert('请填写替代 m/z');
  }
  try {
    const r = await api('/api/annotations', { method:'POST', body: JSON.stringify(body) });
    await refresh(); state.selected = r.versionId; await loadView(r.versionId); renderTree();
  } catch (err) { alert('失败：' + err.message); }
}

function parseCalPoints(text) {
  return text.trim().split('\n').map(line => {
    const [m, ref, label] = line.split(',').map(s=>s.trim());
    return { measuredMz: parseFloat(m), referenceMz: parseFloat(ref), label: label || '' };
  });
}
async function doCalibrate() {
  try {
    const points = parseCalPoints(document.getElementById('calPoints').value);
    const r = await api('/api/calibration', { method:'POST', body: JSON.stringify({ points, parent: state.selected, editor: currentEditor() }) });
    await refresh(); state.selected = r.versionId; await loadView(r.versionId); renderTree();
  } catch (err) { alert('失败：' + err.message); }
}
async function doRollback() {
  const src = parseInt(prompt('回滚到哪个校准版本？', '2'), 10);
  if (!src) return;
  const r = await api('/api/calibration/rollback', { method:'POST', body: JSON.stringify({ sourceVersion: src, parent: state.selected, editor: currentEditor() }) });
  await refresh(); state.selected = r.versionId; await loadView(r.versionId); renderTree();
}
async function doBaseline() {
  const r = await api('/api/baseline', { method:'POST', body: JSON.stringify({
    noiseFloor: parseFloat(document.getElementById('noiseFloor').value),
    relativeThreshold: parseFloat(document.getElementById('relThr').value),
    parent: state.selected, editor: currentReaderSafe() }) });
  await refresh(); state.selected = r.versionId; await loadView(r.versionId); renderTree();
}
function currentReaderSafe() { return document.getElementById('ingestEditor').value || 'analyst'; }

async function doRules() {
  const rules = (await api('/api/rules/' + state.selected));
  rules.ppmTolerance = parseFloat(document.getElementById('rulePpm').value);
  rules.absToleranceDa = parseFloat(document.getElementById('ruleDa').value);
  rules.version += 1;
  const r = await api('/api/rules', { method:'POST', body: JSON.stringify({ rules, parent: state.selected, editor: currentEditor() }) });
  await refresh(); state.selected = r.versionId; await loadView(r.versionId); renderTree();
}

async function doCompare() {
  const oldV = parseInt(document.getElementById('cmpOld').value,10);
  const newV = parseInt(document.getElementById('cmpNew').value,10);
  const rep = await api('/api/compare', { method:'POST', body: JSON.stringify({ oldVersion: oldV, newVersion: newV }) });
  showTab('compare');
  const causeLabel = { OBSERVATION:'观测变化', CALIBRATION:'校准变化', RULES:'规则变化' };
  const kindLabel = { APPEARED:'新增候选', DISAPPEARED:'候选消失', SCORE_CHANGED:'评分变化', PEAKS_CHANGED:'使用峰变化', SAME:'一致' };
  const causes = [rep.observationChanged&&'观测变化', rep.calibrationChanged&&'校准变化', rep.rulesChanged&&'规则变化'].filter(Boolean);
  document.getElementById('pane-compare').innerHTML =
    `<div class="card"><b>v${oldV} → v${newV}</b><br>
      检测到：${causes.length?causes.map(x=>'<span class="pill conflict">'+x+'</span>').join(' '):'<span class="ok">无变化</span>'}</div>` +
    rep.diffs.map(d => `<div class="card">
      <b>${esc(d.compoundName)}</b> ${esc(d.adduct)} <span class="pill proposed">${kindLabel[d.kind]}</span>
      <div>评分 ${d.scoreOld??'-'} → ${d.scoreNew??'-'}</div>
      <div class="muted">归因：${d.causes.map(c=>causeLabel[c]||c).join('、')||'—'}</div>
      <div class="mono muted" style="word-break:break-all">旧峰 ${esc((d.usedPeaksOld||[]).join(' '))}<br>新峰 ${esc((d.usedPeaksNew||[]).join(' '))}</div>
    </div>`).join('') || '<div class="muted">候选集合完全一致</div>';
}

refresh().catch(err => alert('初始化失败：' + err.message));
