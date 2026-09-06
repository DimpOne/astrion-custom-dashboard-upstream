let dashboardData = { startPage: 0, pages: [ { name: "Home", cards: [], hotkeys: [], longHotkeys: [] } ], hotkeys: [], longHotkeys: [], irDevices: [], haDevices: [], harmonyAliases: {}, activities: [], theme: {} };
let currentActivePage = 0;
let editingCard = null;    // index of the card being edited within the current page, or null
let editingHotkey = null;  // { scope, listType, i } of the hotkey being edited, or null
let editingPage = null;    // index of the page being edited in the page dialog, or null (= adding a new one)

/**
 * Turns a display name into a stable, ASCII id — used by activities.js
 * (activities) here, and by devices-page.js's own copy for irDevices/
 * haDevices (a separate page, can't share this one) — kept in sync so both
 * slugify the same way. Diacritics are stripped via Unicode NFD decomposition rather than dropped
 * outright: "Série" -> "e" would silently swallow the accented letter if we
 * matched straight against [^a-z0-9], producing "s_rie" instead of "serie".
 * `normalize('NFD')` splits "é" into "e" + a separate combining accent
 * codepoint (U+0301), which \u0300-\u036f then strips, leaving the plain
 * letter behind.
 */
function slugify(name, fallbackPrefix) {
  const base = name
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  return base || (fallbackPrefix + '_' + Date.now());
}

function resetAll() {
  dashboardData = { startPage: 0, pages: [ { name: "Home", cards: [], hotkeys: [], longHotkeys: [] } ], hotkeys: [], longHotkeys: [], irDevices: [], haDevices: [], harmonyAliases: {}, activities: [], theme: {} };
  currentActivePage = 0;
  document.getElementById('importBox').value = '';
  initEditor();
}

function importJson() {
  const raw = document.getElementById('importBox').value.trim();
  if (!raw) return;
  try {
    const parsed = JSON.parse(raw);
    dashboardData = {
      startPage: parsed.startPage || 0,
      pages: (parsed.pages || []).map(p => ({
        name: p.name || 'Page',
        cards: p.cards || [],
        hotkeys: p.hotkeys || [],
        longHotkeys: p.longHotkeys || [],
        ...(p.parent ? { parent: p.parent } : {}),
        ...(p.parent && p.parentKey && p.parentKey.toUpperCase() !== 'BACK' ? { parentKey: p.parentKey.toUpperCase() } : {}),
      })),
      hotkeys: parsed.hotkeys || [],
      longHotkeys: parsed.longHotkeys || [],
      irDevices: parsed.irDevices || [],
      activities: parsed.activities || [],
      theme: parsed.theme || {},
    };
    if (dashboardData.pages.length === 0) dashboardData.pages.push({ name: "Home", cards: [], hotkeys: [], longHotkeys: [] });
    currentActivePage = 0;
    initEditor();
    alert('dashboard.json loaded — you can now edit it below.');
  } catch (e) {
    alert('Invalid JSON: ' + e.message);
  }
}

function initEditor() {
  editingCard = null;
  editingHotkey = null;
  editingPage = null;
  renderTabs();
  renderPreview();
  renderHotkeysList();
  renderActivitiesList();
  if (typeof renderThemeForm === 'function') renderThemeForm();
  if (typeof applyThemeToPreview === 'function') applyThemeToPreview();
  updateJsonOutput();
  updateHotkeyActionInputs();
}

// ---- Pages: add / rename / delete, all via the page dialog -----------------
// Mirrors Home Assistant's "views" editor: pages live as tabs above the
// preview; a "+" tab adds one, and each tab's own settings (rename, delete,
// set-as-start-page) open in a small dialog instead of a permanent form.

function onPageChange(index) {
  currentActivePage = index;
  cancelCardEdit();
  renderTabs();
  renderPreview();
  renderHotkeysList();
}

function openPageDialog(index) {
  editingPage = index; // null = adding a new page
  const isNew = index === null;
  document.getElementById('pageDialogTitle').textContent = isNew ? 'Add page' : 'Page settings';
  document.getElementById('pageDialogName').value = isNew ? '' : dashboardData.pages[index].name;
  populatePageParentSelect(index);
  document.getElementById('pageDialogParent').value = isNew ? '' : (dashboardData.pages[index].parent || '');
  document.getElementById('pageDialogParentKey').value = isNew ? 'BACK' : (dashboardData.pages[index].parentKey || 'BACK');
  onPageDialogParentChange();
  document.getElementById('pageDialogStart').checked = isNew ? false : (dashboardData.startPage === index);
  document.getElementById('pageDialogDeleteBtn').style.display = isNew ? 'none' : '';
  document.getElementById('pageDialogModal').classList.add('open');
  document.getElementById('pageDialogName').focus();
}

// Shows the "return-to-parent button" picker only once a parent page is
// actually selected — it has nothing to configure otherwise.
function onPageDialogParentChange() {
  const hasParent = !!document.getElementById('pageDialogParent').value;
  document.getElementById('pageDialogParentKeyRow').style.display = hasParent ? '' : 'none';
}

// Every page that would create a cycle if picked as `excludeIndex`'s
// parent: the page itself, plus every one of its own descendants
// (transitively) — picking a descendant as your own parent would make the
// tree loop back on itself. `excludeIndex === null` (adding a brand new
// page) has no descendants yet, so nothing to exclude beyond nothing.
function pagesUnavailableAsParentOf(excludeIndex) {
  if (excludeIndex === null) return new Set();
  const excludedNames = new Set([dashboardData.pages[excludeIndex].name]);
  let grew = true;
  while (grew) {
    grew = false;
    dashboardData.pages.forEach(p => {
      if (p.parent && excludedNames.has(p.parent) && !excludedNames.has(p.name)) {
        excludedNames.add(p.name);
        grew = true;
      }
    });
  }
  return excludedNames;
}

function populatePageParentSelect(excludeIndex) {
  const select = document.getElementById('pageDialogParent');
  const unavailable = pagesUnavailableAsParentOf(excludeIndex);
  select.innerHTML = '<option value="">— None (top-level page) —</option>' +
    dashboardData.pages
      .filter(p => !unavailable.has(p.name))
      .map(p => `<option value="${p.name.replace(/"/g, '&quot;')}">${p.name}</option>`)
      .join('');
}

function closePageDialog() {
  document.getElementById('pageDialogModal').classList.remove('open');
  editingPage = null;
}

function savePageDialog() {
  const name = document.getElementById('pageDialogName').value.trim();
  if (!name) { alert('Give the page a name.'); return; }
  const parent = document.getElementById('pageDialogParent').value || undefined;
  const parentKey = document.getElementById('pageDialogParentKey').value || 'BACK';
  const makeStart = document.getElementById('pageDialogStart').checked;

  if (editingPage === null) {
    const page = { name, cards: [], hotkeys: [], longHotkeys: [] };
    if (parent) {
      page.parent = parent;
      if (parentKey !== 'BACK') page.parentKey = parentKey;
    }
    dashboardData.pages.push(page);
    currentActivePage = dashboardData.pages.length - 1;
    if (makeStart) dashboardData.startPage = currentActivePage;
  } else {
    const page = dashboardData.pages[editingPage];
    const oldName = page.name;
    page.name = name;
    if (parent) {
      page.parent = parent;
      if (parentKey !== 'BACK') page.parentKey = parentKey; else delete page.parentKey;
    } else {
      delete page.parent;
      delete page.parentKey;
    }
    if (makeStart) dashboardData.startPage = editingPage;
    else if (dashboardData.startPage === editingPage) dashboardData.startPage = 0;

    // Renaming a page that others point to as their parent — keep the tree
    // intact instead of silently orphaning them to a name that no longer
    // exists (which the app would then just treat as "no parent found").
    if (oldName !== name) {
      dashboardData.pages.forEach(p => { if (p.parent === oldName) p.parent = name; });
    }
  }

  closePageDialog();
  renderTabs(); renderPreview(); renderHotkeysList(); updateJsonOutput();
}

function deletePageFromDialog() {
  const i = editingPage;
  if (i === null) return;
  if (dashboardData.pages.length <= 1) { alert('You need at least one page.'); return; }
  const deletedName = dashboardData.pages[i].name;
  const children = dashboardData.pages.filter(p => p.parent === deletedName);
  const childWarning = children.length
    ? ` ${children.length} child page(s) (${children.map(c => c.name).join(', ')}) will become top-level pages instead of being deleted.`
    : '';
  if (!confirm(`Delete page "${deletedName}" and everything on it (cards, page hotkeys)?${childWarning}`)) return;
  children.forEach(c => delete c.parent);
  dashboardData.pages.splice(i, 1);
  if (currentActivePage >= dashboardData.pages.length) currentActivePage = dashboardData.pages.length - 1;
  if (dashboardData.startPage >= dashboardData.pages.length) dashboardData.startPage = 0;
  editingCard = null; editingHotkey = null;
  closePageDialog();
  renderTabs(); renderPreview(); renderHotkeysList(); updateJsonOutput();
}

// Reorder a page from fromIdx to toIdx by dragging its tab. Mirrors
// reorderCard() but operates on dashboardData.pages and fixes the two
// positional indices that reference pages by number: startPage (the launch
// page) and currentActivePage (the tab being viewed in the editor). Both must
// follow the page they point to across the splice, otherwise a drag would
// silently change which page launches at boot or which one is shown.
function reorderPage(fromIdx, toIdx) {
  const pages = dashboardData.pages;
  if (fromIdx < 0 || fromIdx >= pages.length || toIdx < 0 || toIdx >= pages.length || fromIdx === toIdx) return;
  const [moved] = pages.splice(fromIdx, 1);
  pages.splice(toIdx, 0, moved);
  const fixIndex = (i) => {
    if (i === fromIdx) return toIdx;
    if (fromIdx < toIdx) { if (i > fromIdx && i <= toIdx) return i - 1; }
    else { if (i >= toIdx && i < fromIdx) return i + 1; }
    return i;
  };
  dashboardData.startPage = fixIndex(dashboardData.startPage);
  currentActivePage = fixIndex(currentActivePage);
  renderTabs(); renderPreview(); renderHotkeysList(); updateJsonOutput();
}

// previewModal/modalScreenSlot: scaffolded in index.html (a "✕ Close" button
// already referenced this) but nothing in this codebase currently opens it —
// no ReferenceError either way now, and this'll do the right thing the day
// something does.
function closePreviewModal() {
  document.getElementById('previewModal')?.classList.remove('open');
}

// ---- Modal backdrops: close only on a genuine click on the backdrop itself ---
//
// Every modal here is `<div class="preview-modal"><div class="preview-modal-inner">
// ...content...</div></div>` — clicking the OUTER div (not its content) should
// close it, same idea as `onclick="if (event.target === this) close()"` which
// this used to be, directly on each modal's HTML.
//
// That inline version had a real bug: picking an option from a native <select>
// inside the modal (a card type, a domain, a device...) sometimes closed the
// whole card being edited. Native <select> dropdowns are rendered by the OS/
// browser outside the normal DOM click flow — dismissing one after picking an
// option can dispatch a synthetic click that browsers/WebViews report as
// landing directly on the backdrop, even though the person's finger/mouse
// never touched it. A plain `click` listener can't tell that apart from a real
// backdrop tap.
//
// Fix: require the *press* (mousedown) to also have started on the backdrop
// itself, not just the click's target. A real backdrop click always starts
// and ends there; a synthetic post-select-dismiss click never had a real
// mousedown on the backdrop to begin with.
function wireModalBackdropClose(modalId, closeFn) {
  const el = document.getElementById(modalId);
  if (!el) return;
  let downOnBackdrop = false;
  el.addEventListener('mousedown', e => { downOnBackdrop = (e.target === el); });
  el.addEventListener('click', e => {
    if (downOnBackdrop && e.target === el) closeFn();
    downOnBackdrop = false;
  });
}

[
  ['previewModal', () => closePreviewModal()],
  ['cardEditorModal', () => cancelCardEdit()],
  ['pageDialogModal', () => closePageDialog()],
  ['activityWizardModal', () => closeActivityWizard()],
  ['iconPickerModal', () => closeIconPicker()],
].forEach(([id, fn]) => wireModalBackdropClose(id, fn));