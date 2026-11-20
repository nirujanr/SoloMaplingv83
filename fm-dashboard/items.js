// "Items" tab: item dictionary with stats/effects and an item page in the style of MeowDB
// (stats block, description, dropped by, sold by NPC, FM price, quests, same-slot items, scrolls).
// Data: data/items.json, built by build-items.ps1.
(() => {
  const $ = id => document.getElementById(id);
  const fmt = n => Number(n).toLocaleString('en-US');
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const icon = (id, cls = '') => window.icon('item', id).replace('class="ico', `class="ico ${cls}`);
  const PAGE = 150;

  const SLOT = { 100: 'Hat', 101: 'Face accessory', 102: 'Eye accessory', 103: 'Earrings', 104: 'Top', 105: 'Overall',
    106: 'Bottom', 107: 'Shoes', 108: 'Gloves', 109: 'Shield', 110: 'Cape', 111: 'Ring', 112: 'Pendant', 113: 'Belt', 114: 'Medal',
    130: 'One-handed sword', 131: 'One-handed axe', 132: 'One-handed blunt weapon', 133: 'Dagger', 137: 'Wand', 138: 'Staff',
    140: 'Two-handed sword', 141: 'Two-handed axe', 142: 'Two-handed blunt weapon', 143: 'Spear', 144: 'Polearm', 145: 'Bow',
    146: 'Crossbow', 147: 'Claw', 148: 'Knuckle', 149: 'Gun', 170: 'Cash weapon', 180: 'Pet equip', 190: 'Mount', 191: 'Saddle' };
  const USE_TYPE = { 200: 'Potion', 201: 'Potion', 202: 'Food / buff', 203: 'Return scroll', 204: 'Equip scroll', 205: 'Cure',
    206: 'Arrows', 207: 'Throwing stars', 210: 'Summoning sack', 212: 'Pet food', 221: 'Transformation', 228: 'Skill book',
    229: 'Mastery book', 233: 'Bullets', 238: 'Monster card' };
  const STAT = { incSTR: 'STR', incDEX: 'DEX', incINT: 'INT', incLUK: 'LUK', incMHP: 'Max HP', incMMP: 'Max MP',
    incPAD: 'Weapon attack', incMAD: 'Magic attack', incPDD: 'Weapon defense', incMDD: 'Magic defense',
    incACC: 'Accuracy', incEVA: 'Avoidability', incSpeed: 'Speed', incJump: 'Jump', incCraft: 'Hands' };
  const SHORT = { incSTR: 'STR', incDEX: 'DEX', incINT: 'INT', incLUK: 'LUK', incMHP: 'HP', incMMP: 'MP', incPAD: 'ATT',
    incMAD: 'M.ATT', incPDD: 'DEF', incMDD: 'M.DEF', incACC: 'ACC', incEVA: 'AVOID', incSpeed: 'Speed', incJump: 'Jump' };
  const JOBS = [[1, 'Warrior'], [2, 'Magician'], [4, 'Bowman'], [8, 'Thief'], [16, 'Pirate']];
  const SPEED = s => s <= 3 ? 'Faster' : s <= 5 ? 'Fast' : s === 6 ? 'Normal' : s <= 8 ? 'Slow' : 'Slower';

  let items = null, byId = new Map(), shops = [], shopNpcs = {};
  let view = [], shown = PAGE, sort = { key: 'lv', dir: 1 };

  async function load() {
    if (items) return true;
    try {
      const res = await fetch(`data/items.json?t=${Date.now()}`, { cache: 'no-store' });
      if (!res.ok) throw new Error(res.status);
      const d = await res.json();
      items = d.items; shops = d.shops || []; shopNpcs = d.shopNpcs || {};
    } catch (e) {
      $('i-results').hidden = true; $('i-detail').hidden = false;
      $('i-detail').innerHTML = '<div class="empty"><strong>No items.json yet</strong>Run fm-dashboard\\build-items.ps1, then reload this page.</div>';
      return false;
    }
    for (const it of items) {
      it.i = it.i || {}; it.s = it.s || {};
      it.type = Math.floor(it.id / 10000);
      it.slot = it.c === 'Equip' || it.c === 'Cosmetic' ? (SLOT[it.type] || 'Equip') : it.c === 'Use' ? (USE_TYPE[it.type] || 'Use') : '';
      it.lv = it.i.reqLevel || 0;
      it.price = it.i.price || 0;
      it.hay = `${it.n} ${it.slot}`.toLowerCase();
      byId.set(it.id, it);
    }
    const slots = [...new Set(items.filter(i => i.c === 'Equip').map(i => i.slot))].sort();
    $('i-slot').insertAdjacentHTML('beforeend', slots.map(s => `<option>${esc(s)}</option>`).join(''));
    return true;
  }

  // ---------------------------------------------------------------- stat / effect text
  const jobText = r => !r ? 'All jobs' : r === -1 ? 'Beginner only' : JOBS.filter(([b]) => r & b).map(([, n]) => n).join(', ') || `Job ${r}`;
  const signed = v => `${v > 0 ? '+' : ''}${fmt(v)}`;

  function effects(it) {   // human-readable list for use items / scrolls / stars / etc.
    const s = it.s, i = it.i, out = [];
    if (s.hp) out.push(`Recovers ${fmt(s.hp)} HP`);
    if (s.mp) out.push(`Recovers ${fmt(s.mp)} MP`);
    if (s.hpR) out.push(`Recovers ${s.hpR}% of max HP`);
    if (s.mpR) out.push(`Recovers ${s.mpR}% of max MP`);
    const buffs = [['pad', 'Weapon attack'], ['mad', 'Magic attack'], ['pdd', 'Weapon defense'], ['mdd', 'Magic defense'],
      ['acc', 'Accuracy'], ['eva', 'Avoidability'], ['speed', 'Speed'], ['jump', 'Jump']]
      .filter(([k]) => s[k]).map(([k, n]) => `${n} ${signed(s[k])}`);
    if (buffs.length) out.push(`${buffs.join(', ')}${s.time ? ` for ${fmt(Math.round(s.time / 1000))} sec` : ''}`);
    else if (s.time && (s.morph || s.exp)) out.push(`Lasts ${fmt(Math.round(s.time / 1000))} sec`);
    const cures = ['poison', 'seal', 'darkness', 'weakness', 'curse'].filter(k => s[k]);
    if (cures.length) out.push(`Cures ${cures.join(', ')}`);
    if (s.moveTo) out.push(s.moveTo === 999999999 ? 'Returns you to the nearest town'
      : `Teleports you to ${window.mobsApi ? esc(window.mobsApi.mapLabel(s.moveTo)) : s.moveTo}`);
    if (s.morph) out.push('Transforms your character');
    if (s.inc && it.type === 212) out.push(`Fills pet fullness by ${s.inc}`);
    if (s.expBuff || s.expinc) out.push(`EXP ${signed(s.expBuff || s.expinc)}%`);
    // scrolls
    if (i.success != null) {
      out.push(`Success rate ${i.success}%`);
      const st = Object.entries(STAT).filter(([k]) => i[k]).map(([k, n]) => `${n} ${signed(i[k])}`);
      if (st.length) out.push(`On success: ${st.join(', ')}`);
      if (i.cursed) out.push(`${i.cursed}% chance to destroy the item if it fails`);
      if (i.randstat) out.push('Randomly changes each stat of the item (Chaos)');
      if (i.recover) out.push(`Restores ${i.recover} failed upgrade slot${i.recover > 1 ? 's' : ''}`);
      if (i.preventslip) out.push('Prevents slipping on ice');
      if (i.warmsupport) out.push('Protects from cold');
    } else if (it.c !== 'Equip' && it.c !== 'Cosmetic') {
      const st = Object.entries(STAT).filter(([k]) => i[k]).map(([k, n]) => `${n} ${signed(i[k])}`);
      if (st.length) out.push(st.join(', ') + (it.type === 207 || it.type === 233 || it.type === 206 ? ' (added to your attacks)' : ''));
    }
    if (i.unitPrice) out.push(`Recharge cost ${i.unitPrice} mesos each`);
    if (it.c === 'Pet') { if (i.life) out.push(`Lives ${i.life} days`); }
    return out;
  }

  function statChips(it) {
    if (it.c === 'Equip' || it.c === 'Cosmetic') {
      return Object.entries(SHORT).filter(([k]) => it.i[k]).map(([k, n]) => `<span class="chip">${n} ${signed(it.i[k])}</span>`).join('')
        + (it.i.tuc ? `<span class="chip">${it.i.tuc} slots</span>` : '');
    }
    const e = effects(it);
    return e.length ? `<span class="sub">${esc(e.slice(0, 2).join(' · '))}</span>` : '';
  }

  // ---------------------------------------------------------------- list
  async function apply(reset = true) {
    if (!await load()) return;
    const terms = $('i-q').value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const cat = $('i-cat').value, slot = $('i-slot').value, job = $('i-job').value;
    const min = parseInt($('i-min').value, 10), max = parseInt($('i-max').value, 10);
    view = items.filter(it =>
      terms.every(t => /^\d+$/.test(t) ? (it.id === +t || it.hay.includes(t)) : it.hay.includes(t)) &&
      (cat ? it.c === cat : it.c !== 'Cosmetic') &&
      (!slot || it.slot === slot) &&
      (job === '' || (job === '0' ? !it.i.reqJob : (it.i.reqJob & +job) || (!it.i.reqJob && (it.c === 'Equip')))) &&
      (isNaN(min) || it.lv >= min) && (isNaN(max) || it.lv <= max));
    const k = sort.key, d = sort.dir;
    view.sort((a, b) => k === 'name' ? a.n.localeCompare(b.n) * d
      : k === 'price' ? (a.price - b.price) * d || a.n.localeCompare(b.n)
      : (a.lv - b.lv) * d || a.type - b.type || a.n.localeCompare(b.n));
    if (reset) shown = PAGE;
    render();
  }

  function render() {
    $('i-rows').innerHTML = view.slice(0, shown).map(it => {
      const fm = window.dropsApi ? window.dropsApi.fm(it.id) : null;
      return `<tr>
        <td><div class="withicon">${icon(it.id)}<div><div class="name"><a class="xref" data-item="${it.id}">${esc(it.n)}</a></div>
          <div class="sub">#${it.id} · ${esc(it.slot || it.c)}${it.i.reqJob ? ` · ${esc(jobText(it.i.reqJob))}` : ''}</div></div></div></td>
        <td class="num">${it.lv || '–'}</td>
        <td>${statChips(it)}</td>
        <td class="num">${it.price ? fmt(it.price) : '–'}</td>
        <td class="num">${fm ? `<a class="xref" data-fm="${it.id}">${fmt(fm.unit)}</a><div class="sub">${fm.n} listing${fm.n === 1 ? '' : 's'}</div>` : '<span class="sub">–</span>'}</td>
      </tr>`;
    }).join('');
    $('i-count').textContent = `${fmt(view.length)} item${view.length === 1 ? '' : 's'}${view.length > shown ? ` · showing ${fmt(shown)}` : ''}`;
    $('i-more').hidden = view.length <= shown;
    if (!view.length) { $('i-empty').innerHTML = '<strong>No matches</strong>Try a shorter search or clear some filters.'; $('i-empty').hidden = false; }
    else $('i-empty').hidden = true;
    document.querySelectorAll('th[data-isort]').forEach(th => {
      const on = th.dataset.isort === sort.key;
      th.innerHTML = th.textContent.replace(/[▲▼]/g, '').trim() + (on ? ` <span class="arrow">${sort.dir > 0 ? '▲' : '▼'}</span>` : '');
    });
  }

  // ---------------------------------------------------------------- item page
  function hideDetail() { $('i-detail').hidden = true; $('i-results').hidden = false; }

  async function openItem(id) {
    window.showTab('items');
    if (!await load()) return;
    const it = byId.get(id);
    $('i-results').hidden = true; $('i-detail').hidden = false; window.scrollTo(0, 0);
    if (!it) { $('i-detail').innerHTML = `<button class="link back" id="i-back">← Back to the list</button><div class="detail"><div class="empty"><strong>Unknown item #${id}</strong></div></div>`; return; }
    const i = it.i, isEquip = it.c === 'Equip' || it.c === 'Cosmetic';
    const stat = (k, v) => `<div><div class="k">${k}</div><div class="v">${v}</div></div>`;
    const blocks = [];
    if (i.reqLevel) blocks.push(stat('Req level', i.reqLevel));
    for (const s of ['STR', 'DEX', 'INT', 'LUK']) if (i['req' + s]) blocks.push(stat('Req ' + s, i['req' + s]));
    if (isEquip) blocks.push(stat('Job', esc(jobText(i.reqJob))));
    if (isEquip) for (const [k, n] of Object.entries(STAT)) if (i[k]) blocks.push(stat(n, signed(i[k])));
    if (i.attackSpeed) blocks.push(stat('Attack speed', `${SPEED(i.attackSpeed)} (${i.attackSpeed})`));
    if (i.tuc) blocks.push(stat('Upgrade slots', i.tuc));
    if (i.slotMax) blocks.push(stat('Max per slot', fmt(i.slotMax)));
    blocks.push(stat('NPC sell price', i.price ? `${fmt(i.price)} mesos` : '–'));
    const flags = [
      i.cash ? 'Cash item' : '', i.tradeBlock ? 'Untradeable' : 'Tradeable', i.only ? 'One-of-a-kind' : '',
      i.equipTradeBlock ? 'Untradeable once equipped' : '', i.quest ? 'Quest item' : '', i.notSale ? "Can't sell to NPC" : '',
      i.expireOnLogout ? 'Disappears on logout' : '', i.timeLimited ? 'Time limited' : '',
    ].filter(Boolean);
    const eff = isEquip ? [] : effects(it);

    $('i-detail').innerHTML = `<button class="link back" id="i-back">← Back to the list</button>
      <div class="detail">
        <div class="head">${icon(it.id).replace('class="ico ', 'class="bigicon ')}
          <div><h2>${esc(it.n)}</h2><div class="sub">${esc(it.c)}${it.slot ? ` · ${esc(it.slot)}` : ''} · #${it.id}</div></div></div>
        <div class="stats">${blocks.join('')}</div>
        ${eff.length ? `<h3>Effect</h3><ul class="effects">${eff.map(e => `<li>${e}</li>`).join('')}</ul>` : ''}
        <div class="flags">${flags.map(f => `<span class="chip">${esc(f)}</span>`).join('')}</div>
        ${it.d ? `<div class="desc-box">${esc(it.d)}</div>` : ''}
        <h3>Free Market</h3><div id="id-fm" class="sub">Loading…</div>
        <h3>Dropped by</h3><div id="id-drops" class="sub">Loading…</div>
        <h3>Sold by NPCs</h3><div id="id-shops" class="sub">Loading…</div>
        <h3>Quests</h3><div id="id-quests" class="sub">Loading…</div>
        ${isEquip && it.c === 'Equip' ? `<h3>Other ${esc(it.slot)} items</h3><div id="id-compare"></div>
          <h3>Scrolls for this item</h3><div id="id-scrolls"></div>` : ''}
      </div>`;

    // FM
    const fmList = (window.fmApi ? window.fmApi.listings() : []).filter(l => l.itemId === id).sort((a, b) => a.unit - b.unit);
    $('id-fm').outerHTML = fmList.length
      ? `<p>${fmList.length} listing${fmList.length === 1 ? '' : 's'} right now · cheapest <b>${fmt(fmList[0].unit)}</b> mesos${fmList.length > 1 ? ` · median ${fmt(fmList[Math.floor(fmList.length / 2)].unit)}` : ''}
         · <a class="xref" data-fm="${id}">see all listings</a></p>`
      : '<p class="sub">Not for sale in the Free Market right now.</p>';

    // dropped by (+ best map: where the mob has the most spawns)
    Promise.all([window.dropsApi.load(), window.mobsApi.load()]).then(() => {
      // monsters that actually spawn somewhere first (event copies share names but never spawn)
      const spawns = r => { const m = r.global ? null : window.mobsApi.mob(r.mobId); return m && m.spawns.length ? 1 : 0; };
      const ds = window.dropsApi.forItem(id).sort((a, b) => spawns(b) - spawns(a) || b.eff - a.eff);
      const { pct } = window.dropsApi;
      $('id-drops').outerHTML = ds.length ? `<div class="tablewrap"><table><thead><tr><th>Monster</th><th class="num">Chance</th><th>Best map to farm</th></tr></thead><tbody>
        ${ds.map(r => {
          const m = r.global ? null : window.mobsApi.mob(r.mobId);
          const best = m && m.spawns.length ? m.spawns.slice().sort((a, b) => b.n - a.n)[0] : null;
          return `<tr><td>${r.global ? esc(r.mobName) : `<div class="withicon">${window.icon('mob', r.mobId)}<div><a class="xref" data-mob="${r.mobId}">${esc(r.mobName)}</a>
              ${r.boss ? '<span class="badge boss">Boss</span>' : ''}<div class="sub">#${r.mobId} · Lv ${r.lv || '–'}</div></div></div>`}</td>
            <td class="num"><span class="chance">${pct(r.eff)}</span><div class="sub">${r.eff < 1 ? `1 in ${fmt(Math.max(1, Math.round(1 / r.eff)))}` : ''}</div></td>
            <td>${best ? `<a class="xref" data-map="${best.map}">${esc(window.mobsApi.mapLabel(best.map))}</a><div class="sub">${best.t === -3 ? 'area boss' : `${best.n} spawns`} · ${esc(window.mobsApi.region(best.map))}</div>`
              : '<span class="sub">not on a map (event/summon)</span>'}</td></tr>`;
        }).join('')}</tbody></table></div>` : '<p class="sub">No monster drops this item.</p>';
    }).catch(() => { $('id-drops').textContent = 'Drop data not available.'; });

    // NPC shops (+ where the NPC stands)
    window.mobsApi.load().then(() => {
      const sh = shops.filter(([, item]) => item === id);
      $('id-shops').outerHTML = sh.length ? `<div>${sh.map(([npc, , price]) => {
        const where = window.mobsApi.npcMaps(npc)[0];
        return `<span class="qitem">${window.icon('npc', npc).replace('class="ico', 'class="ico sm')}${esc(shopNpcs[npc] || `NPC ${npc}`)}&nbsp;<b>${fmt(price)}</b>&nbsp;mesos
          ${where ? `&nbsp;· <a class="xref" data-map="${where}">${esc(window.mobsApi.mapLabel(where))}</a>` : ''}</span>`;
      }).join('')}</div>` : '<p class="sub">No NPC shop sells this item.</p>';
    });

    // quests
    window.questsApi.load().then(ok => {
      const need = ok ? window.questsApi.needItem(id) : [], give = ok ? window.questsApi.rewardItem(id) : [];
      const list = (qs, label) => qs.length ? `<p class="sub">${label}</p><div>${qs.slice(0, 30).map(q => `<span class="qitem"><a class="xref" data-questid="${q.id}">${esc(q.name)}</a>${q.lvmin ? `&nbsp;<span class="sub">Lv ${q.lvmin}+</span>` : ''}</span>`).join('')}${qs.length > 30 ? ` <span class="sub">+${qs.length - 30} more</span>` : ''}</div>` : '';
      $('id-quests').outerHTML = (need.length || give.length)
        ? `<div>${list(give, 'Reward from:')}${list(need, 'Needed for (keep it before selling!):')}</div>`
        : '<p class="sub">Not used in any quest.</p>';
    });

    // same-slot equips + scrolls
    if (it.c === 'Equip') {
      const same = items.filter(x => x.c === 'Equip' && x.type === it.type && x.id !== id && (!it.i.reqJob || !x.i.reqJob || (x.i.reqJob & it.i.reqJob)))
        .sort((a, b) => Math.abs(a.lv - it.lv) - Math.abs(b.lv - it.lv)).slice(0, 10).sort((a, b) => a.lv - b.lv);
      $('id-compare').innerHTML = same.length ? `<div>${same.map(x => `<span class="qitem">${icon(x.id, 'sm')}<a class="xref" data-item="${x.id}">${esc(x.n)}</a>&nbsp;<span class="sub">Lv ${x.lv || '–'}${x.i.incPAD ? ` · ATT ${x.i.incPAD}` : x.i.incMAD ? ` · M.ATT ${x.i.incMAD}` : x.i.incPDD ? ` · DEF ${x.i.incPDD}` : ''}</span></span>`).join('')}</div>` : '<p class="sub">None.</p>';
      // scroll ids: armor 2040000 + (type-100)*100, weapons 2043000 + (type-130)*100; 2049xxx work on everything
      const prefix = it.type >= 130 ? 20430 + (it.type - 130) : it.type >= 100 && it.type <= 111 ? 20400 + (it.type - 100) : null;
      const scrolls = items.filter(x => x.c === 'Use' && x.i.success != null && (Math.floor(x.id / 100) === prefix || Math.floor(x.id / 1000) === 2049))
        .sort((a, b) => Math.floor(a.id / 1000) - Math.floor(b.id / 1000) || b.i.success - a.i.success);
      $('id-scrolls').innerHTML = it.i.tuc && scrolls.length ? `<div class="tablewrap"><table><thead><tr><th>Scroll</th><th class="num">Success</th><th>On success</th><th class="num">FM price</th></tr></thead><tbody>
        ${scrolls.map(x => { const fm = window.dropsApi.fm(x.id); return `<tr><td><div class="withicon">${icon(x.id, 'sm')}<a class="xref" data-item="${x.id}">${esc(x.n)}</a></div></td>
          <td class="num">${x.i.success}%${x.i.cursed ? `<div class="sub">${x.i.cursed}% boom</div>` : ''}</td>
          <td>${Object.entries(SHORT).filter(([k]) => x.i[k]).map(([k, n]) => `<span class="chip">${n} ${signed(x.i[k])}</span>`).join('') || `<span class="sub">${esc(effects(x).slice(1).join(' · '))}</span>`}</td>
          <td class="num">${fm ? `<a class="xref" data-fm="${x.id}">${fmt(fm.unit)}</a>` : '<span class="sub">–</span>'}</td></tr>`; }).join('')}
        </tbody></table></div>` : '<p class="sub">This item has no upgrade slots.</p>';
    }
  }
  window.openItem = openItem;

  // ---------------------------------------------------------------- wiring
  document.addEventListener('click', e => {
    const a = e.target.closest('[data-item],#i-back');
    if (!a) return;
    e.preventDefault();
    if (a.id === 'i-back') hideDetail();
    else openItem(+a.dataset.item);
  });
  document.addEventListener('tab:show', e => { if (e.detail === 'items') { apply(false); if ($('i-detail').hidden) $('i-q').focus(); } });
  document.addEventListener('fm:loaded', () => { if (items && !$('view-items').hidden && $('i-detail').hidden) render(); });

  let t;
  ['i-q', 'i-min', 'i-max'].forEach(id => $(id).addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => { hideDetail(); apply(); }, 200); }));
  ['i-cat', 'i-slot', 'i-job'].forEach(id => $(id).addEventListener('change', () => { hideDetail(); apply(); }));
  $('i-reset').addEventListener('click', () => { ['i-q', 'i-min', 'i-max', 'i-cat', 'i-slot', 'i-job'].forEach(id => $(id).value = ''); hideDetail(); apply(); });
  $('i-more-btn').addEventListener('click', () => { shown += PAGE; render(); });
  document.querySelectorAll('th[data-isort]').forEach(th => th.addEventListener('click', () => {
    const key = th.dataset.isort;
    sort = sort.key === key ? { key, dir: -sort.dir } : { key, dir: 1 };
    apply(false);
  }));
})();
