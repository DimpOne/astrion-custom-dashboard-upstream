// ---- IR Devices ---------------------------------------------------------
//
// Builds entries for dashboardData.irDevices: a registry of named commands
// per physical device ("power", "hdmi1", "volume_up"...). Two ways a
// device's commands can be sourced — both end up as an entry in
// dashboardData.irDevices, and BOTH shapes are what actually gets written
// into dashboard.json's "irDevices" array on export (see updateJsonOutput
// in export.js, which serializes dashboardData as-is):
//
//  - Inline: { id, name, commands: { cmdId: {freq, pattern} } } — resolved
//    from a hand-pasted Pronto Hex code right here at build time (see
//    prontoToPattern below). For one-off buttons not in any curated
//    database, e.g. straight out of the sniffer's Learning Mode.
//  - Reference: { id, name, category, brand, model } — no "commands" key
//    at all. Points at a curated file the ir-database picker (a separate
//    static site) generates and you copy onto the device by hand, at
//    /sdcard/astrion/ir-database/<category>.json. The *app* resolves
//    Pronto from that file at runtime (IrDatabaseRuntime.kt) — this
//    builder never fetches or bundles ir-database/ itself anymore.
//
// Either way, a scene_grid/hotkey tile's own irDevice+irCommand fields are
// unaffected — they're just two ids (device id, command id) and don't
// care which shape the device they point to is.

let pendingIrCommands = {};        // commandId -> {freq, pattern, label} for the INLINE device currently being built/edited
let editingIrDevice = null;        // id of the device being edited, or null when creating a new one

// Client-side-only convenience: commandId hints per *reference*-mode
// device, so the tile form's IR-command field can still suggest ids via a
// <datalist> even though this builder has no way to know a referenced
// device's actual command list (that only lives in the sdcard file, on
// the device, at runtime). Never written into dashboardData — purely a
// local autocomplete aid, lost on reload, which is fine.
const irDeviceCommandHints = {};   // deviceId -> [commandId, ...]

// Matches the ir-database picker's own category ids/labels (see its
// index.json) — hardcoded here since this builder no longer fetches
// ir-database/index.json itself.
const IR_CATEGORIES = [
  { id: 'ac', label: 'AC' },
  { id: 'audio', label: 'Audio' },
  { id: 'camera', label: 'Camera' },
  { id: 'fan', label: 'Fan' },
  { id: 'lights', label: 'Lights' },
  { id: 'player', label: 'Player' },
  { id: 'plug', label: 'Plug' },
  { id: 'robot', label: 'Robot' },
  { id: 'set-top-box', label: 'Set-Top Box' },
  { id: 'tv', label: 'TV' }
];

/**
 * Decodes a "learned" Pronto Hex code (type 0000 — raw timing, not a
 * codebook lookup) into {freq, pattern} for ConsumerIrManager.transmit().
 * Falls back to the repeat section if there's no "once" section (some
 * codes, e.g. a few Sony buttons, only carry a repeat burst).
 *
 * Still used here for the "paste Pronto manually" (Inline) path. The
 * *reference* path doesn't need this at all — IrDatabaseRuntime.kt has
 * its own Kotlin port that runs on-device instead, kept in sync by hand.
 */
function prontoToPattern(pronto) {
  const words = pronto.trim().split(/\s+/).map(w => parseInt(w, 16));
  const [type, freqCode, onceLen, repeatLen] = words;
  if (type !== 0x0000) {
    throw new Error('Only "learned" Pronto codes (type 0000) are supported, got type ' + type.toString(16));
  }
  const carrierHz = Math.round(4145146 / freqCode);
  const periodUs = 1000000 / carrierHz;
  const rest = words.slice(4);
  const once = rest.slice(0, onceLen * 2);
  const repeat = rest.slice(onceLen * 2, onceLen * 2 + repeatLen * 2);
  const chosen = once.length ? once : repeat;
  if (!chosen.length) throw new Error('Pronto code has neither a "once" nor a "repeat" section');
  return { freq: carrierHz, pattern: chosen.map(c => Math.round(c * periodUs)) };
}

// ---- source-mode toggle (inline vs. ir-database reference) ----------------

function initIrCategorySelect() {
  const sel = document.getElementById('irRefCategory');
  if (!sel) return;
  sel.innerHTML = IR_CATEGORIES.map(c => `<option value="${c.id}">${c.label}</option>`).join('');
}

function onIrSourceModeChange() {
  const mode = document.querySelector('input[name="irSourceMode"]:checked')?.value || 'inline';
  document.getElementById('irInlineForm').style.display = mode === 'inline' ? '' : 'none';
  document.getElementById('irRefForm').style.display = mode === 'reference' ? '' : 'none';
}

// ---- "already on this remote" quick pick -----------------------------------
//
// GET /ir-database and GET /ir-database/<category>.json only exist when this
// builder is opened from the remote itself (/builder/) — ConfigServer.kt
// reads straight off /sdcard/astrion/ir-database/, same-origin, no CORS or
// mixed-content concerns since it's the same server serving this page.
// Opened any other way (e.g. a local file, or bundled somewhere without that
// backing server), the fetch below just fails quietly and the manual
// category/brand/model fields underneath are the only way in — unchanged
// from before this existed.

let irOnDeviceCache = {}; // categoryId -> parsed {category, brands:[...]} file, fetched once per category

async function tryLoadOnDeviceIrDatabase() {
  const quickPick = document.getElementById('irOnDeviceQuickPick');
  try {
    const res = await fetch('/ir-database');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const categoryIds = await res.json();
    if (!Array.isArray(categoryIds) || !categoryIds.length) return;

    const sel = document.getElementById('irOnDeviceCategory');
    sel.innerHTML = '<option value="">— category —</option>' +
      categoryIds.map(id => {
        const known = IR_CATEGORIES.find(c => c.id === id);
        return `<option value="${id}">${known ? known.label : id}</option>`;
      }).join('');
    quickPick.style.display = '';
  } catch (e) {
    quickPick.style.display = 'none';
    console.debug('No on-device ir-database reachable (expected unless opened via /builder/):', e.message);
  }
}

function resetIrOnDeviceSelect(sel, placeholder) {
  sel.innerHTML = `<option value="">${placeholder}</option>`;
  sel.disabled = true;
}

async function onIrOnDeviceCategoryChange() {
  const categoryId = document.getElementById('irOnDeviceCategory').value;
  const brandSel = document.getElementById('irOnDeviceBrand');
  const modelSel = document.getElementById('irOnDeviceModel');
  resetIrOnDeviceSelect(modelSel, '— select a brand first —');
  if (!categoryId) { resetIrOnDeviceSelect(brandSel, '— select a category first —'); return; }

  try {
    if (!irOnDeviceCache[categoryId]) {
      const res = await fetch(`/ir-database/${categoryId}.json`);
      if (!res.ok) throw new Error('HTTP ' + res.status);
      irOnDeviceCache[categoryId] = await res.json();
    }
    const brands = irOnDeviceCache[categoryId].brands || [];
    brandSel.innerHTML = '<option value="">— select a brand —</option>' +
      brands.map((b, i) => `<option value="${i}">${b.brand_name}</option>`).join('');
    brandSel.disabled = false;
  } catch (e) {
    brandSel.innerHTML = '<option value="">(failed to load)</option>';
    console.error('Failed to load on-device ir-database category', e);
  }
}

function onIrOnDeviceBrandChange() {
  const categoryId = document.getElementById('irOnDeviceCategory').value;
  const brandIdx = document.getElementById('irOnDeviceBrand').value;
  const modelSel = document.getElementById('irOnDeviceModel');
  if (brandIdx === '') { resetIrOnDeviceSelect(modelSel, '— select a brand first —'); return; }
  const models = irOnDeviceCache[categoryId].brands[brandIdx].models || [];
  modelSel.innerHTML = '<option value="">— select a model —</option>' +
    models.map((m, i) => `<option value="${i}">${m.model_name}</option>`).join('');
  modelSel.disabled = false;
}

/** Fills the manual category/brand/model/known-commands fields from the
 * quick-pick selection — same fields either way, so saveIrDevice() doesn't
 * need to know or care which path filled them in. Every command id in the
 * matched model gets listed as a hint, not just whatever was typed by
 * hand — the whole point of this being an on-device file we can actually
 * read, instead of a name typed blind. */
function onIrOnDeviceModelChange() {
  const categoryId = document.getElementById('irOnDeviceCategory').value;
  const brandIdx = document.getElementById('irOnDeviceBrand').value;
  const modelIdx = document.getElementById('irOnDeviceModel').value;
  if (modelIdx === '') return;

  const data = irOnDeviceCache[categoryId];
  const brand = data.brands[brandIdx];
  const model = brand.models[modelIdx];

  document.getElementById('irRefCategory').value = categoryId;
  document.getElementById('irRefBrand').value = brand.brand_name;
  document.getElementById('irRefModel').value = model.model_name;
  document.getElementById('irRefKnownCommands').value = Object.keys(model.commands || {}).join(', ');
}

// ---- adding one named command to the INLINE device being built ------------
//
// Only one way now: a hand-pasted Pronto Hex code, for a device/button not
// in any curated database, or a code you learned yourself with another
// tool (e.g. the sniffer's Learning Mode). The commandId (freeform, e.g.
// "power", "hdmi1", "volume_up") is what this command is addressed by
// from ActivityDeviceConfig / scene_grid's irCommand field — same as
// always, only how it gets resolved to freq+pattern changed.

let renamingCommandId = null; // commandId currently shown with an inline rename field, or null

function addIrCommand() {
  const commandId = document.getElementById('irCommandId').value.trim();
  if (!commandId) { alert('Give this command an id, e.g. "power", "hdmi1", "volume_up".'); return; }

  const manualPronto = document.getElementById('irManualPronto').value.trim();
  if (!manualPronto) { alert('Paste a Pronto Hex code.'); return; }

  let resolved;
  try {
    resolved = prontoToPattern(manualPronto);
  } catch (e) {
    alert('Couldn\'t decode this Pronto code: ' + e.message);
    return;
  }
  const label = document.getElementById('irManualLabel').value.trim() || commandId;

  pendingIrCommands[commandId] = { freq: resolved.freq, pattern: resolved.pattern, label };
  document.getElementById('irCommandId').value = '';
  document.getElementById('irManualPronto').value = '';
  document.getElementById('irManualLabel').value = '';
  renderIrCommandsList();
}

function removeIrCommand(commandId) {
  delete pendingIrCommands[commandId];
  if (renamingCommandId === commandId) renamingCommandId = null;
  renderIrCommandsList();
}

function startRenameIrCommand(commandId) {
  renamingCommandId = commandId;
  renderIrCommandsList();
}

function confirmRenameIrCommand(oldId) {
  const newId = document.getElementById('irRenameInput').value.trim();
  if (!newId) { alert('Command id can\'t be empty.'); return; }
  if (newId !== oldId && pendingIrCommands[newId]) { alert('That id is already used by another command on this device.'); return; }
  if (newId !== oldId) {
    pendingIrCommands[newId] = pendingIrCommands[oldId];
    delete pendingIrCommands[oldId];
  }
  renamingCommandId = null;
  renderIrCommandsList();
}

function renderIrCommandsList() {
  const list = document.getElementById('irCommandsList');
  if (!list) return;
  const ids = Object.keys(pendingIrCommands);
  if (!ids.length) {
    list.innerHTML = '<div class="hint">No commands yet — add one above, or import all of them from a picked model.</div>';
    return;
  }
  list.innerHTML = '';
  ids.forEach(commandId => {
    const cmd = pendingIrCommands[commandId];
    const el = document.createElement('div');
    el.className = 'list-item';
    if (renamingCommandId === commandId) {
      el.innerHTML = `<input type="text" id="irRenameInput" value="${commandId}" style="flex:1;margin-right:8px">
        <span class="remove" style="color:#00E5FF" onclick="confirmRenameIrCommand('${commandId}')">✓</span>
        <span class="remove" onclick="renamingCommandId=null;renderIrCommandsList()">✕</span>`;
    } else {
      el.innerHTML = `<span><code>${commandId}</code> — ${cmd.label}</span><span><span class="remove" style="color:#00E5FF" onclick="startRenameIrCommand('${commandId}')">✎</span> <span class="remove" onclick="removeIrCommand('${commandId}')">✕</span></span>`;
    }
    list.appendChild(el);
  });
  if (renamingCommandId) document.getElementById('irRenameInput')?.focus();
}

// ---- saving / editing / removing devices ------------------------------------

function renderIrDevicesList() {
  const list = document.getElementById('irDevicesList');
  if (!list) return;
  list.innerHTML = '';
  (dashboardData.irDevices || []).forEach(dev => {
    const summary = dev.commands
      ? `${Object.keys(dev.commands).length} command${Object.keys(dev.commands).length === 1 ? '' : 's'}, inline`
      : `${dev.brand} ${dev.model} (${dev.category}) — via ir-database`;
    const el = document.createElement('div');
    el.className = 'list-item';
    el.innerHTML = `<span>${dev.name} <span style="color:#888">(${summary})</span></span><span><span class="remove" style="color:#00E5FF" onclick="editIrDevice('${dev.id}')">✎</span> <span class="remove" onclick="removeIrDevice('${dev.id}')">✕</span></span>`;
    list.appendChild(el);
  });
}

function saveIrDevice() {
  const name = document.getElementById('irDevName').value.trim();
  if (!name) { alert('Give this device a name.'); return; }

  const mode = document.querySelector('input[name="irSourceMode"]:checked')?.value || 'inline';
  let deviceFields; // fields merged into the dashboardData.irDevices entry — shape depends on mode
  let hints = null; // commandId hints for this device, client-side-only (see irDeviceCommandHints)

  if (mode === 'inline') {
    if (!Object.keys(pendingIrCommands).length) { alert('Add at least one command.'); return; }
    deviceFields = { commands: pendingIrCommands, category: undefined, brand: undefined, model: undefined };
  } else {
    const category = document.getElementById('irRefCategory').value;
    const brand = document.getElementById('irRefBrand').value.trim();
    const model = document.getElementById('irRefModel').value.trim();
    if (!category || !brand || !model) { alert('Fill in category, brand, and model — matching exactly what you copied into /sdcard/astrion/ir-database/.'); return; }
    deviceFields = { category, brand, model, commands: undefined };
    const rawHints = document.getElementById('irRefKnownCommands').value.trim();
    hints = rawHints ? rawHints.split(',').map(s => s.trim()).filter(Boolean) : [];
  }

  dashboardData.irDevices = dashboardData.irDevices || [];
  let savedId;
  if (editingIrDevice !== null) {
    const idx = dashboardData.irDevices.findIndex(d => d.id === editingIrDevice);
    if (idx >= 0) {
      // Start from just {id, name} rather than spreading the old entry —
      // switching modes on an existing device (e.g. inline -> reference)
      // must not leave a stale "commands" key (or stale category/brand/
      // model) behind from whichever mode it used to be.
      dashboardData.irDevices[idx] = { id: dashboardData.irDevices[idx].id, name, ...deviceFields };
      savedId = dashboardData.irDevices[idx].id;
    }
  } else {
    const id = slugify(name, 'device');
    let uniqueId = id;
    let n = 2;
    while (dashboardData.irDevices.some(d => d.id === uniqueId)) uniqueId = `${id}_${n++}`;
    dashboardData.irDevices.push({ id: uniqueId, name, ...deviceFields });
    savedId = uniqueId;
  }
  // Strip the `undefined` placeholders used above to force-clear the other
  // mode's fields — JSON.stringify already drops `undefined` values, but
  // updateJsonOutput() isn't the only consumer of dashboardData (e.g.
  // renderIrDevicesList's `dev.commands` check above), so clean it here too.
  const saved = dashboardData.irDevices.find(d => d.id === savedId);
  Object.keys(saved).forEach(k => { if (saved[k] === undefined) delete saved[k]; });

  if (hints !== null) irDeviceCommandHints[savedId] = hints;
  else delete irDeviceCommandHints[savedId];

  cancelIrDeviceEdit();
  renderIrDevicesList();
  updateCardFormInputs(); // refreshes the IR-device picker inside the scene_grid form, if open
  if (typeof renderActDevSubForm === 'function') renderActDevSubForm(); // same, for the Activities section's device picker
  updateJsonOutput();
}

function editIrDevice(id) {
  const dev = (dashboardData.irDevices || []).find(d => d.id === id);
  if (!dev) return;
  editingIrDevice = id;
  document.getElementById('irDevName').value = dev.name;

  const isReference = !dev.commands;
  document.querySelector(`input[name="irSourceMode"][value="${isReference ? 'reference' : 'inline'}"]`).checked = true;
  onIrSourceModeChange();

  if (isReference) {
    document.getElementById('irRefCategory').value = dev.category || '';
    document.getElementById('irRefBrand').value = dev.brand || '';
    document.getElementById('irRefModel').value = dev.model || '';
    document.getElementById('irRefKnownCommands').value = (irDeviceCommandHints[id] || []).join(', ');
    pendingIrCommands = {};
  } else {
    pendingIrCommands = JSON.parse(JSON.stringify(dev.commands || {}));
  }
  renderIrCommandsList();
  document.getElementById('saveIrDeviceBtn').textContent = 'Save device';
  document.getElementById('cancelIrDeviceEditBtn').style.display = '';
}

function cancelIrDeviceEdit() {
  editingIrDevice = null;
  pendingIrCommands = {};
  document.getElementById('irDevName').value = '';
  document.getElementById('irRefBrand').value = '';
  document.getElementById('irRefModel').value = '';
  document.getElementById('irRefKnownCommands').value = '';
  document.querySelector('input[name="irSourceMode"][value="inline"]').checked = true;
  onIrSourceModeChange();
  document.getElementById('saveIrDeviceBtn').textContent = 'Save device';
  document.getElementById('cancelIrDeviceEditBtn').style.display = 'none';
  renderIrCommandsList();
}

function removeIrDevice(id) {
  dashboardData.irDevices = (dashboardData.irDevices || []).filter(d => d.id !== id);
  delete irDeviceCommandHints[id];
  if (editingIrDevice === id) cancelIrDeviceEdit();
  renderIrDevicesList();
  updateCardFormInputs();
  if (typeof renderActDevSubForm === 'function') renderActDevSubForm();
  updateJsonOutput();
}

initIrCategorySelect();
onIrSourceModeChange();
renderIrCommandsList();
renderIrDevicesList();
tryLoadOnDeviceIrDatabase();
