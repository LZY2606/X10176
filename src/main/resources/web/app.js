"use strict";

const state = {
  branch: "main",
  view: null,
  zoom: { mzMin: null, mzMax: null, dragging: false, dragStart: null },
  selectedPeak: null,
  showCandidates: true,
};

async function api(method, path, body) {
  const opts = { method, headers: { "Content-Type": "application/json" } };
  if (body !== undefined) opts.body = JSON.stringify(body);
  const resp = await fetch(path, opts);
  const text = await resp.text();
  const data = text ? JSON.parse(text) : null;
  if (!resp.ok) {
    const err = new Error((data && data.error) || resp.statusText);
    err.payload = data;
    err.status = resp.status;
    throw err;
  }
  return data;
}

function el(tag, attrs, ...children) {
  const e = document.createElement(tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else e[k] = v;
  }
  for (const c of children) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return e;
}

async function loadBranches() {
  const branches = await api("GET", "/api/branches");
  const sel = document.getElementById("branchSelect");
  sel.innerHTML = "";
  for (const b of branches) {
    const opt = el("option", { value: b.id }, `${b.name} (${b.id})`);
    if (b.id === state.branch) opt.selected = true;
    sel.append(opt);
  }
}

async function refresh() {
  state.view = await api("GET", `/api/branches/${state.branch}/view`);
  document.getElementById("headInfo").textContent =
    `头版本 seq=${state.view.seq} ${state.view.versionId} ｜ 规则配置 v${state.view.configVersion} ｜ ${state.view.peaks.length} 个峰`;
  document.getElementById("newPpm").placeholder = state.view.configVersion;
  renderPeaksAndSpectrum();
  renderCandidates();
  renderConflicts();
  renderHistory();
  renderVersions();
}

function filteredPeaks() {
  const pol = document.getElementById("polarityFilter").value;
  return state.view.peaks.filter(p => pol === "ALL" || p.polarity === pol);
}

function drawSpectrum() {
  const canvas = document.getElementById("spectrum");
  const ctx = canvas.getContext("2d");
  const W = canvas.width, H = canvas.height;
  ctx.clearRect(0, 0, W, H);
  const peaks = filteredPeaks();
  if (peaks.length === 0) {
    ctx.fillStyle = "#7f8da8";
    ctx.font = "14px sans-serif";
    ctx.fillText("暂无观测峰", 20, 30);
    return;
  }
  const mzVals = peaks.map(p => p.calibratedMz);
  let mzMin = state.zoom.mzMin ?? Math.min(...mzVals) - 1;
  let mzMax = state.zoom.mzMax ?? Math.max(...mzVals) + 1;
  const maxI = Math.max(...peaks.map(p => p.intensity));
  const padL = 55, padR = 15, padT = 18, padB = 34;
  const xOf = mz => padL + (mz - mzMin) / (mzMax - mzMin) * (W - padL - padR);
  const yOf = i => H - padB - i / maxI * (H - padT - padB);

  ctx.strokeStyle = "#28344f";
  ctx.lineWidth = 1;
  ctx.font = "11px sans-serif";
  for (let g = 0; g <= 5; g++) {
    const y = padT + g * (H - padT - padB) / 5;
    ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(W - padR, y); ctx.stroke();
    ctx.fillStyle = "#64748f";
    ctx.fillText(Math.round(maxI * (1 - g / 5)).toLocaleString(), 6, y + 4);
  }
  for (let g = 0; g <= 8; g++) {
    const mz = mzMin + g * (mzMax - mzMin) / 8;
    const x = xOf(mz);
    ctx.fillStyle = "#64748f";
    ctx.fillText(mz.toFixed(3), x - 18, H - 12);
  }

  for (const p of peaks) {
    const x = xOf(p.calibratedMz);
    const y = yOf(p.intensity);
    const isContam = p.status === "contaminant";
    const isSelected = state.selectedPeak === p.id;
    ctx.strokeStyle = isContam ? "#b07a3e" : (isSelected ? "#ffd25c" : "#7fb1ff");
    ctx.lineWidth = isSelected ? 3 : 2;
    ctx.beginPath(); ctx.moveTo(x, H - padB); ctx.lineTo(x, y); ctx.stroke();
    if (isContam) {
      ctx.fillStyle = "#e0c08f";
      ctx.fillText("污染", x + 3, y - 4);
    }
  }

  const colors = ["#42d392", "#e07ac8", "#ffb347", "#5cd2ff", "#c08fff", "#ff7a7a"];
  if (state.showCandidates) {
    let ci = 0;
    for (const run of state.view.runs) {
      for (const cand of run.candidates) {
        const e = cand.explanation;
        const color = colors[ci++ % colors.length];
        const drawPeak = (pid, order) => {
          const p = state.view.peaks.find(x => x.id === pid);
          if (!p) return;
          if (p.calibratedMz < mzMin || p.calibratedMz > mzMax) return;
          const x = xOf(p.calibratedMz);
          ctx.strokeStyle = color;
          ctx.lineWidth = 2;
          ctx.beginPath();
          ctx.moveTo(x - 6, 12 + order * 5);
          ctx.lineTo(x + 6, 12 + order * 5);
          ctx.stroke();
        };
        drawPeak(e.monoisotopicPeakId, 0);
        for (const iso of e.isotopes) {
          if (iso.present && iso.observedPeakId) drawPeak(iso.observedPeakId, iso.order);
          else if (iso.expectedMz >= mzMin && iso.expectedMz <= mzMax) {
            const x = xOf(iso.expectedMz);
            ctx.setLineDash([3, 3]);
            ctx.strokeStyle = color;
            ctx.beginPath();
            ctx.moveTo(x - 5, 12 + iso.order * 5);
            ctx.lineTo(x + 5, 12 + iso.order * 5);
            ctx.stroke();
            ctx.setLineDash([]);
          }
        }
      }
    }
  }

  canvas.onwheel = ev => {
    ev.preventDefault();
    const rect = canvas.getBoundingClientRect();
    const mx = (ev.clientX - rect.left) / rect.width * W;
    if (mx < padL || mx > W - padR) return;
    const mzAt = mzMin + (mx - padL) / (W - padL - padR) * (mzMax - mzMin);
    const factor = ev.deltaY > 0 ? 1.15 : 1 / 1.15;
    state.zoom.mzMin = mzAt - (mzAt - mzMin) * factor;
    state.zoom.mzMax = mzAt + (mzMax - mzAt) * factor;
    drawSpectrum();
  };
  canvas.onmousedown = ev => {
    state.zoom.dragging = true;
    state.zoom.dragStart = ev.clientX;
  };
  canvas.onmousemove = ev => {
    if (!state.zoom.dragging) return;
    const dx = ev.clientX - state.zoom.dragStart;
    state.zoom.dragStart = ev.clientX;
    const span = state.zoom.mzMax - state.zoom.mzMin;
    const shift = -dx / canvas.width * span;
    state.zoom.mzMin += shift; state.zoom.mzMax += shift;
    drawSpectrum();
  };
  canvas.onmouseup = () => { state.zoom.dragging = false; };
  canvas.onclick = ev => {
    const rect = canvas.getBoundingClientRect();
    const mx = (ev.clientX - rect.left) / rect.width * W;
    let best = null, bestDist = 8;
    for (const p of filteredPeaks()) {
      const x = xOf(p.calibratedMz);
      const d = Math.abs(x - mx);
      if (d < bestDist) { bestDist = d; best = p; }
    }
    if (best) {
      state.selectedPeak = best.id;
      document.getElementById("peakIdInput").value = best.id;
      drawSpectrum();
    }
  };
}

function renderPeaksAndSpectrum() {
  document.getElementById("spectrum");
  drawSpectrum();
}

function allCandidatesFlat() {
  const out = [];
  for (const run of state.view.runs) for (const c of run.candidates) out.push(c);
  return out;
}

function renderCandidates() {
  const tbody = document.querySelector("#candidateTable tbody");
  tbody.innerHTML = "";
  const decided = new Map(state.view.decisions.map(d => [d.candidateId, d]));
  for (const cand of allCandidatesFlat()) {
    const e = cand.explanation;
    const isoText = e.isotopes.map(i => {
      if (i.present) return `M${i.order}:Δ${(i.observedSpacingDa || 0).toFixed(4)}Da ${i.spacingPpmError.toFixed(1)}ppm`;
      return `M${i.order}:缺失(低强度)`;
    }).join("；");
    const rules = el("ul", { class: "muted", style: "margin:0;padding-left:14px" },
      ...e.rulesApplied.map(r => el("li", {}, r)),
      el("li", {}, `配置版本 v${e.configVersion}`),
      el("li", {}, `RT 窗 ±${e.rtWindowSeconds}s，实测 ${e.rtSeconds}s`));
    const peakLinks = el("span", {}, e.usedPeakIds.join(", "));
    const d = decided.get(cand.id);
    const actions = el("span", {});
    if (d) {
      actions.append(el("span", { class: "tag " + (d.status === "accepted" ? "pos" : "neg") }, d.status));
    } else {
      actions.append(
        el("button", { class: "good", onclick: () => adjudicate(cand.id, "accept") }, "接受"),
        el("button", { class: "danger", onclick: () => adjudicate(cand.id, "reject") }, "驳回")
      );
    }
    tbody.append(el("tr", {},
      el("td", {}, `${e.target} ${e.adductCode}`),
      el("td", {}, `${e.charge} ${el("span", { class: "tag " + (e.polarity === "POSITIVE" ? "pos" : "neg") }, e.polarity).outerHTML}`),
      el("td", {}, e.monoisotopicPeakId),
      el("td", {}, e.monoisotopicPpmError.toFixed(3)),
      el("td", {}, e.monoisotopicDaError.toFixed(5)),
      el("td", {}, isoText),
      el("td", {}, peakLinks),
      el("td", {}, rules),
      el("td", {}, e.score.toFixed(3)),
      el("td", {}, actions),
    ));
  }
}

async function adjudicate(candidateId, action) {
  const rationale = prompt(`${action === "accept" ? "接受" : "驳回"}候选的裁定理由：`, action === "accept" ? "同位素簇一致" : "存疑");
  if (rationale === null) return;
  try {
    await api("POST", "/api/decisions", {
      branchId: state.branch,
      candidateId, action, rationale,
      baseVersionId: state.view.versionId,
      editor: "researcher",
    });
    await refresh();
  } catch (err) {
    if (err.status === 409) {
      alert(`裁定冲突：${err.payload.error}\n最小共享峰集合：${(err.payload.sharedPeakIds || []).join(", ")}`);
    } else alert("裁定失败：" + err.message);
    await refresh();
  }
}

function renderConflicts() {
  const tbody = document.querySelector("#conflictTable tbody");
  tbody.innerHTML = "";
  const cands = new Map(allCandidatesFlat().map(c => [c.id, c]));
  for (const c of state.view.conflicts) {
    const cand = cands.get(c.candidateId);
    tbody.append(el("tr", {},
      el("td", {}, cand ? `${cand.explanation.target} ${cand.explanation.adductCode} (${c.candidateId})` : c.candidateId),
      el("td", {}, c.reason),
      el("td", {}, c.sharedPeakIds.join(", ") || "—"),
    ));
  }
  if (state.view.conflicts.length === 0) {
    tbody.append(el("tr", {}, el("td", { colspan: 3, class: "muted" }, "当前没有峰占用冲突")));
  }
}

function renderHistory() {
  const tbody = document.querySelector("#historyTable tbody");
  tbody.innerHTML = "";
  for (const d of state.view.decisions) {
    tbody.append(el("tr", {},
      el("td", {}, new Date(d.createdAt).toLocaleTimeString()),
      el("td", {}, d.editor),
      el("td", {}, d.candidateId),
      el("td", {}, el("span", { class: "tag " + (d.status === "accepted" ? "pos" : "neg") }, d.action)),
      el("td", {}, d.baseVersionId),
      el("td", {}, d.rationale || ""),
    ));
  }
}

async function renderVersions() {
  const versions = await api("GET", `/api/branches/${state.branch}/versions`);
  const box = document.getElementById("versionList");
  box.innerHTML = "";
  for (const v of versions) {
    const chip = el("span", {
      class: "version-chip" + (v.versionId === state.view.versionId ? " head" : ""),
      title: `${v.eventType} by ${v.editor}, config v${v.configVersion}, parent=${v.parentVersionId || "-"}`,
      onclick: () => viewVersion(v.versionId),
    }, `#${v.seq} ${v.eventType} v${v.configVersion}`);
    box.append(chip);
  }
  const from = document.getElementById("compareFrom");
  const to = document.getElementById("compareTo");
  from.innerHTML = ""; to.innerHTML = "";
  for (const v of versions) {
    from.append(el("option", { value: v.versionId }, `#${v.seq} ${v.eventType}`));
    to.append(el("option", { value: v.versionId }, `#${v.seq} ${v.eventType}`));
  }
  if (versions.length > 0) {
    from.value = versions[0].versionId;
    to.value = versions[versions.length - 1].versionId;
  }
}

async function viewVersion(versionId) {
  state.view = await api("GET", `/api/branches/${state.branch}/view?versionId=${versionId}`);
  state.zoom.mzMin = null; state.zoom.mzMax = null;
  drawSpectrum();
  renderCandidates();
  renderConflicts();
  renderHistory();
  document.getElementById("headInfo").textContent =
    `查看历史版本 seq=${state.view.seq} ${state.view.versionId}（只读，操作将基于该分支头）｜ 配置 v${state.view.configVersion}`;
}

async function loadCurrentConfigIntoForm() {
  const cfg = await api("GET", `/api/config/latest`);
  document.getElementById("newPpm").value = cfg.candidateRules.ppmTolerance;
  document.getElementById("newDa").value = cfg.candidateRules.absoluteToleranceDa;
}

async function doImport() {
  const content = document.getElementById("importText").value;
  if (!content.trim()) return;
  const result = await api("POST", "/api/import", { branchId: state.branch, content, editor: "researcher" });
  const box = document.getElementById("quarantineBox");
  box.innerHTML = "";
  const summary = `内容摘要 ${result.contentDigest.slice(0, 16)}… ｜ 接受 ${result.acceptedRows} 行，` +
    `新峰 ${result.newPeakCount}，合并 ${result.mergedPeakCount}，隔离 ${result.quarantinedRows.length} 行`;
  document.getElementById("importSummary").textContent = summary;
  for (const q of result.quarantinedRows) {
    box.append(el("div", { class: "err-row" }, `第 ${q.line} 行已隔离：${q.error} ｜ ${q.rawLine}`));
  }
  state.zoom.mzMin = null; state.zoom.mzMax = null;
  await refresh();
}

async function doRun() {
  const resp = await api("POST", "/api/runs", { branchId: state.branch, editor: "researcher" });
  alert(`生成候选 ${resp.run.candidates.length} 条，配置 v${resp.run.configVersion}`);
  await refresh();
}

function bind() {
  document.getElementById("branchSelect").addEventListener("change", async ev => {
    state.branch = ev.target.value;
    state.zoom.mzMin = null; state.zoom.mzMax = null;
    await refresh();
  });
  document.getElementById("refreshBtn").onclick = refresh;
  document.getElementById("importBtn").onclick = doImport;
  document.getElementById("runBtn").onclick = doRun;
  document.getElementById("zoomInBtn").onclick = () => zoomBy(0.7);
  document.getElementById("zoomOutBtn").onclick = () => zoomBy(1.4);
  document.getElementById("resetZoomBtn").onclick = () => {
    state.zoom.mzMin = null; state.zoom.mzMax = null; drawSpectrum();
  };
  document.getElementById("polarityFilter").onchange = drawSpectrum;
  document.getElementById("forkBtn").onclick = async () => {
    const name = prompt("新分支名称：", "experiment-" + Date.now() % 10000);
    if (!name) return;
    const b = await api("POST", "/api/branches", {
      name, fromVersionId: state.view.versionId, editor: "researcher"
    });
    state.branch = b.id;
    await loadBranches();
    document.getElementById("branchSelect").value = b.id;
    await refresh();
  };
  document.getElementById("contamBtn").onclick = async () => {
    const peakId = document.getElementById("peakIdInput").value.trim();
    await api("POST", "/api/peaks/contaminant", {
      branchId: state.branch, peakId,
      note: document.getElementById("contamNote").value, editor: "researcher"
    });
    await refresh();
  };
  document.getElementById("uncontamBtn").onclick = async () => {
    const peakId = document.getElementById("peakIdInput").value.trim();
    await api("POST", "/api/peaks/unmark-contaminant", { branchId: state.branch, peakId, editor: "researcher" });
    await refresh();
  };
  document.getElementById("splitBtn").onclick = async () => {
    const peakId = document.getElementById("peakIdInput").value.trim();
    const parts = document.getElementById("splitParts").value.split(",").map(s => {
      const [mz, rt, intensity] = s.trim().split(":").map(Number);
      return { rawMz: mz, rtSeconds: rt, intensity };
    });
    await api("POST", "/api/peaks/split", { branchId: state.branch, peakId, parts, editor: "researcher" });
    await refresh();
  };
  document.getElementById("calibBtn").onclick = async () => {
    const points = document.getElementById("calibPoints").value.split("\n").map(l => {
      const [m, t] = l.split(",").map(Number);
      return { measuredMz: m, theoreticalMz: t };
    }).filter(p => Number.isFinite(p.measuredMz));
    await api("POST", "/api/calibration", { branchId: state.branch, points, editor: "researcher" });
    await refresh();
  };
  document.getElementById("rollbackBtn").onclick = async () => {
    const v = parseInt(document.getElementById("rollbackVersion").value, 10);
    await api("POST", "/api/calibration/rollback", {
      branchId: state.branch, targetConfigVersion: v, editor: "researcher"
    });
    await refresh();
  };
  document.getElementById("upgradeBtn").onclick = async () => {
    const cfg = await api("GET", `/api/config/${state.view.configVersion}`);
    cfg.candidateRules.ppmTolerance = parseFloat(document.getElementById("newPpm").value);
    cfg.candidateRules.absoluteToleranceDa = parseFloat(document.getElementById("newDa").value);
    await api("POST", "/api/config", { branchId: state.branch, config: cfg, editor: "researcher" });
    alert("规则已升级为新版本，旧 run 仍可在旧版本查看");
    await refresh();
  };
  document.getElementById("compareBtn").onclick = async () => {
    const cmp = await api("POST", "/api/compare", {
      fromVersionId: document.getElementById("compareFrom").value,
      toVersionId: document.getElementById("compareTo").value,
    });
    document.getElementById("compareResult").textContent =
      `变化类别: ${cmp.changeClasses.join(", ")}\n` +
      `观测变化=${cmp.observationChanged} 校准变化=${cmp.calibrationChanged} 规则变化=${cmp.rulesChanged}\n` +
      cmp.details.join("\n") +
      `\n仅旧版本: ${cmp.onlyInFrom.join(" | ") || "无"}` +
      `\n仅新版本: ${cmp.onlyInTo.join(" | ") || "无"}` +
      `\n发生变化的候选: ${cmp.changedCandidates.join(" | ") || "无"}`;
  };
  document.getElementById("sampleBtn").onclick = () => {
    document.getElementById("importText").value =
`195.0871,120000,120.5,pos,101
196.0905,9600,120.5,pos,101
197.0899,520,120.6,pos,101
217.0690,88000,120.7,pos,102
218.0724,7000,120.7,pos,102
193.0510,70000,130.2,neg,201
194.0543,5400,130.2,neg,201
this,is,a,broken,row
207.1379,40000,140.0,pos,301
208.1412,3200,140.0,pos,301`;
  };
}

function zoomBy(factor) {
  const peaks = filteredPeaks();
  if (peaks.length === 0) return;
  const vals = peaks.map(p => p.calibratedMz);
  let lo = state.zoom.mzMin ?? Math.min(...vals);
  let hi = state.zoom.mzMax ?? Math.max(...vals);
  const c = (lo + hi) / 2;
  const half = (hi - lo) / 2 * factor;
  state.zoom.mzMin = c - half; state.zoom.mzMax = c + half;
  drawSpectrum();
}

(async function init() {
  bind();
  await loadBranches();
  await refresh();
  await loadCurrentConfigIntoForm();
})();
