// "Mobs & Maps" tab + server rates in the header.
// Data: data/mobmaps.json and data/rates.json, built by build-mobmaps.ps1.
(() => {
  const $ = id => document.getElementById(id);
  const fmt = n => Number(n).toLocaleString('en-US');
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const icon = (kind, id, cls = '') => window.icon(kind, id).replace('class="ico', `class="ico ${cls}`);
  const PAGE = 150;
  const REGIONS = { maple: 'Maple Island', victoria: 'Victoria Island', ossyria: 'Ossyria', elin: 'Ellin Forest',
    weddingGL: 'Amoria', MasteriaGL: 'Masteria', HalloweenGL: 'Halloween', jp: 'Zipangu', etc: 'Other',
    singapore: 'Singapore', event: 'Event', Episode1GL: 'Episode 1' };
  const ELEM = { F: 'Fire', I: 'Ice', L: 'Lightning', S: 'Poison', H: 'Holy', D: 'Dark', P: 'Physical' };
  const ELEM_LVL = { 1: 'immune to', 2: 'resists', 3: 'weak to' };

  // ---------------------------------------------------------------- rates (header, all tabs)
  window.rates = null;
  fetch(`data/rates.json?t=${Date.now()}`, { cache: 'no-store' }).then(r => r.ok ? r.json() : null).then(r => {
    if (!r) return;
    window.rates = r;
    const chips = [['EXP', r.exp], ['Meso', r.meso], ['Drop', r.drop], ['Boss drop', r.boss_drop], ['Quest', r.quest]]
      .filter(([, v]) => v != null).map(([k, v]) => `<span class="chip">${k} ×${v}</span>`).join('');
    $('rates').innerHTML = chips;
    $('rates').title = `Server rates for ${r.world} (from config.yaml)`;
    document.dispatchEvent(new Event('rates:loaded'));
  }).catch(() => {});
  const expRate = () => (window.rates && window.rates.exp) || 1;

  // ---------------------------------------------------------------- data
  let data = null;
  const mobs = new Map(), maps = new Map();
  async function load() {
    if (data) return true;
    try {
      const res = await fetch(`data/mobmaps.json?t=${Date.now()}`, { cache: 'no-store' });
      if (!res.ok) throw new Error(res.status);
      data = await res.json();
    } catch (e) {
      $('m-results').hidden = true;
      $('m-detail').hidden = false;
      $('m-detail').innerHTML = '<div class="empty"><strong>No mobmaps.json yet</strong>Run fm-dashboard\\build-mobmaps.ps1, then reload this page.</div>';
      return false;
    }
    for (const [id, a] of Object.entries(data.mobs)) {
      const [name, lv, hp, mp, exp, pad, pdd, mad, mdd, acc, eva, boss, undead, elem] = a;
      mobs.set(+id, { id: +id, name, lv, hp, mp, exp, pad, pdd, mad, mdd, acc, eva, boss: !!boss, undead: !!undead, elem, spawns: [],
        hay: `${name}`.toLowerCase() });
    }
    for (const [id, [street, name, region, town, link]] of Object.entries(data.maps)) {
      maps.set(+id, { id: +id, street, name, region, town: !!town, link, mobs: [], npcs: [],
        hay: `${name} ${street} ${REGIONS[region] || region}`.toLowerCase() });
    }
    for (const [mapId, mobId, n, t] of data.spawns) {
      const m = mobs.get(mobId), mp = maps.get(mapId);
      if (m && mp) { m.spawns.push({ map: mapId, n, t }); mp.mobs.push({ mob: mobId, n, t }); }
    }
    for (const [mapId, npcId] of data.npcs) maps.get(mapId)?.npcs.push(npcId);
    for (const m of mobs.values()) m.regions = new Set(m.spawns.map(s => maps.get(s.map).region));
    for (const mp of maps.values()) {
      const lvls = mp.mobs.map(s => mobs.get(s.mob).lv).filter(Boolean);
      mp.minLv = lvls.length ? Math.min(...lvls) : 0; mp.maxLv = lvls.length ? Math.max(...lvls) : 0;
      mp.mobCount = mp.mobs.reduce((s, x) => s + (x.t >= 0 ? x.n : 0), 0);
    }
    const regions = [...new Set([...maps.values()].map(m => m.region))].filter(Boolean).sort();
    $('m-region').insertAdjacentHTML('beforeend', regions.map(r => `<option value="${esc(r)}">${esc(REGIONS[r] || r)}</option>`).join(''));
    return true;
  }

  const mapLabel = id => { const m = maps.get(id); return m ? (m.name || `Map ${id}`) : `Map ${id}`; };
  const respawn = t => t === -3 ? '<span class="badge boss" title="Spawned by a server script, checked every 3 hours">Area boss · ~3h</span>'
                   : t > 0 ? `<span class="badge quest" title="Respawns this long after it dies">every ${t >= 60 ? Math.round(t / 60) + ' min' : t + 's'}</span>`
                   : t < 0 ? '<span class="badge quest">special</span>' : '';
  const expTxt = m => `${fmt(m.exp * expRate())}${expRate() > 1 ? `<div class="sub">base ${fmt(m.exp)}</div>` : ''}`;
  const elemTxt = e => (e || '').match(/[A-Z]\d/g)?.map(x => `${ELEM_LVL[x[1]] || '?'} ${ELEM[x[0]] || x[0]}`).join(', ') || 'Neutral';

  // ---------------------------------------------------------------- list
  let view = [], shown = PAGE;
  async function apply(reset = true) {
    if (!await load()) return;
    const kind = $('m-kind').value;
    const terms = $('m-q').value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const min = parseInt($('m-min').value, 10), max = parseInt($('m-max').value, 10);
    const region = $('m-region').value, onlySpawn = $('m-spawn').checked;
    const match = (o, t) => /^\d+$/.test(t) ? (o.id === +t || o.hay.includes(t)) : o.hay.includes(t);

    if (kind === 'mob') {
      view = [...mobs.values()].filter(m =>
        terms.every(t => match(m, t)) &&
        (isNaN(min) || m.lv >= min) && (isNaN(max) || m.lv <= max) &&
        (!region || m.regions.has(region)) && (!onlySpawn || m.spawns.length));
      view.sort((a, b) => a.lv - b.lv || a.name.localeCompare(b.name));
    } else {
      // with a level range, rank maps as training spots: most monsters within the range first
      const ranged = !isNaN(min) || !isNaN(max);
      const inRange = lv => (isNaN(min) || lv >= min) && (isNaN(max) || lv <= max);
      for (const mp of maps.values()) {
        mp.inRange = ranged ? mp.mobs.reduce((s, x) => s + (inRange(mobs.get(x.mob).lv) && x.t >= 0 ? x.n : 0), 0) : 0;
      }
      view = [...maps.values()].filter(mp =>
        terms.every(t => match(mp, t) || mp.mobs.some(s => mobs.get(s.mob).hay.includes(t))) &&
        (!ranged || mp.inRange > 0) &&
        (!region || mp.region === region) && (!onlySpawn || mp.mobs.length));
      view.sort(ranged ? (a, b) => b.inRange - a.inRange || a.minLv - b.minLv
                       : (a, b) => a.minLv - b.minLv || b.mobCount - a.mobCount);
    }
    if (reset) shown = PAGE;
    render();
  }

  function render() {
    const kind = $('m-kind').value;
    $('m-head').innerHTML = kind === 'mob'
      ? '<tr><th>Monster</th><th class="num">Level</th><th class="num">HP</th><th class="num">EXP</th><th>Found in</th></tr>'
      : '<tr><th>Map</th><th>Region</th><th class="num">Mob level</th><th>Monsters</th><th class="num">NPCs</th></tr>';
    $('m-rows').innerHTML = view.slice(0, shown).map(o => kind === 'mob' ? `<tr>
        <td><div class="withicon">${icon('mob', o.id)}<div><div class="name"><a class="xref" data-mob="${o.id}">${esc(o.name)}</a>
          ${o.boss ? '<span class="badge boss">Boss</span>' : ''}</div><div class="sub">#${o.id}</div></div></div></td>
        <td class="num">${o.lv || '–'}</td>
        <td class="num">${fmt(o.hp)}</td>
        <td class="num">${expTxt(o)}</td>
        <td>${o.spawns.length ? `${o.spawns.slice(0, 3).map(s => `<a class="xref" data-map="${s.map}">${esc(mapLabel(s.map))}</a>`).join(', ')}${o.spawns.length > 3 ? ` <span class="sub">+${o.spawns.length - 3} more</span>` : ''}`
              : '<span class="sub">not on a map</span>'}</td>
      </tr>` : `<tr>
        <td><div class="name"><a class="xref" data-map="${o.id}">${esc(o.name || `Map ${o.id}`)}</a>${o.town ? ' <span class="badge player">Town</span>' : ''}</div>
          <div class="sub">${esc(o.street)} · #${o.id}</div></td>
        <td>${esc(REGIONS[o.region] || o.region || '–')}</td>
        <td class="num">${o.minLv ? (o.minLv === o.maxLv ? o.minLv : `${o.minLv}–${o.maxLv}`) : '–'}${o.inRange ? `<div class="sub">${o.inRange} in your range</div>` : ''}</td>
        <td>${o.mobs.slice(0, 4).map(s => `<span class="qitem">${icon('mob', s.mob, 'sm')}<a class="xref" data-mob="${s.mob}">${esc(mobs.get(s.mob).name)}</a>${s.n > 1 ? `&nbsp;<b>×${s.n}</b>` : ''}</span>`).join('')}
            ${o.mobs.length > 4 ? `<span class="sub">+${o.mobs.length - 4} more</span>` : ''}</td>
        <td class="num">${o.npcs.length || '–'}</td>
      </tr>`).join('');
    $('m-count').textContent = `${fmt(view.length)} ${kind === 'mob' ? 'monster' : 'map'}${view.length === 1 ? '' : 's'}${view.length > shown ? ` · showing ${fmt(shown)}` : ''}`;
    $('m-more').hidden = view.length <= shown;
    if (!view.length) { $('m-empty').innerHTML = '<strong>No matches</strong>Try a shorter search or clear some filters.'; $('m-empty').hidden = false; }
    else $('m-empty').hidden = true;
  }

  // ---------------------------------------------------------------- detail pages
  function showDetail(html) {
    $('m-detail').innerHTML = `<button class="link back" id="m-back">← Back to the list</button>${html}`;
    $('m-detail').hidden = false;
    $('m-results').hidden = true;
    window.scrollTo(0, 0);
  }
  function hideDetail() { $('m-detail').hidden = true; $('m-results').hidden = false; }

  async function openMob(id) {
    window.showTab('mobs');
    if (!await load()) return;
    const m = mobs.get(id);
    if (!m) return showDetail(`<div class="detail"><div class="empty"><strong>Unknown monster #${id}</strong>It isn't in Mob.wz.</div></div>`);
    const stat = (k, v) => `<div><div class="k">${k}</div><div class="v">${v}</div></div>`;
    const spawnRows = m.spawns.slice().sort((a, b) => b.n - a.n).map(s => {
      const mp = maps.get(s.map);
      return `<tr><td><a class="xref" data-map="${s.map}">${esc(mp.name || `Map ${s.map}`)}</a> ${respawn(s.t)}<div class="sub">${esc(mp.street)} · #${s.map}</div></td>
        <td>${esc(REGIONS[mp.region] || mp.region || '–')}</td><td class="num">${s.t === -3 ? 1 : fmt(s.n)}</td></tr>`;
    }).join('');

    showDetail(`<div class="detail">
      <div class="head">${window.icon('mob', m.id).replace('class="ico mob"', 'class=""')}
        <div><h2>${esc(m.name)} ${m.boss ? '<span class="badge boss">Boss</span>' : ''} ${m.undead ? '<span class="badge quest">Undead</span>' : ''}</h2>
        <div class="sub">#${m.id} · Level ${m.lv || '–'}</div></div></div>
      <div class="stats">
        ${stat('Level', m.lv || '–')}${stat('HP', fmt(m.hp))}${stat('MP', fmt(m.mp))}
        ${stat(`EXP${expRate() > 1 ? ` (×${expRate()})` : ''}`, fmt(m.exp * expRate()))}
        ${stat('Attack', fmt(m.pad))}${stat('Magic attack', fmt(m.mad))}${stat('Defense', fmt(m.pdd))}${stat('Magic def.', fmt(m.mdd))}
        ${stat('Accuracy', fmt(m.acc))}${stat('Avoid', fmt(m.eva))}${stat('Elements', esc(elemTxt(m.elem)))}
      </div>
      <h3>Where it spawns (${m.spawns.length} map${m.spawns.length === 1 ? '' : 's'})</h3>
      ${m.spawns.length ? `<div class="tablewrap"><table><thead><tr><th>Map</th><th>Region</th><th class="num">On map</th></tr></thead><tbody>${spawnRows}</tbody></table></div>`
        : '<p class="sub">Not placed on any map. It is spawned by an event, a boss fight (e.g. an expedition), a quest or another monster.</p>'}
      <h3>Drops</h3><div id="md-drops" class="sub">Loading…</div>
      <h3>Quests that hunt it</h3><div id="md-quests" class="sub">Loading…</div>
    </div>`);

    // drops
    window.dropsApi.load().then(() => {
      const ds = window.dropsApi.forMob(id).sort((a, b) => b.eff - a.eff);
      const { pct, short } = window.dropsApi;
      const mr = (window.rates && window.rates.meso) || 1;
      $('md-drops').outerHTML = ds.length ? `<div class="tablewrap"><table><thead><tr><th>Item</th><th class="num">Chance</th><th class="num">Qty</th><th class="num">FM price</th></tr></thead><tbody>
        ${ds.map(r => { const fm = window.dropsApi.fm(r.itemId); return `<tr>
          <td><div class="withicon">${r.cat === 0 ? '' : icon('item', r.itemId, 'sm')}<div>${r.cat === 0 ? 'Mesos' : `<a class="xref" data-item="${r.itemId}" title="Item details: stats, every monster that drops it, shops">${esc(r.itemName)}</a>`}
            ${r.quest ? '<span class="badge quest">Quest</span>' : ''}<div class="sub">${r.cat === 0 ? '' : `#${r.itemId}`}</div></div></div></td>
          <td class="num"><span class="chance">${pct(r.eff)}</span><div class="sub">base ${pct(r.base)}</div></td>
          <td class="num">${r.cat === 0 ? `${fmt(r.min * mr)}–${fmt(r.max * mr)}` : (r.min === r.max ? r.min : `${r.min}–${r.max}`)}</td>
          <td class="num">${fm ? `<a class="xref" data-fm="${r.itemId}">${fmt(fm.unit)}</a><div class="sub">${short(fm.unit)}</div>` : '<span class="sub">–</span>'}</td></tr>`; }).join('')}
        </tbody></table></div>` : '<p class="sub">No drops in drop_data.</p>';
    }).catch(() => { $('md-drops').textContent = 'Drop data not available (run build-drops.ps1).'; });

    // quests
    window.questsApi.load().then(ok => {
      const qs = ok ? window.questsApi.forMob(id) : [];
      $('md-quests').outerHTML = qs.length
        ? `<div>${qs.slice(0, 30).map(q => `<span class="qitem"><a class="xref" data-questid="${q.id}">${esc(q.name)}</a>&nbsp;<span class="sub">${q.lvmin ? `Lv ${q.lvmin}+` : ''} · ${fmt((q.mobs.find(([x]) => x === id) || [0, 0])[1])}×</span></span>`).join('')}</div>`
        : '<p class="sub">No quests ask you to hunt it.</p>';
    });
  }

  async function openMap(id) {
    window.showTab('maps');
    if (!await load()) return;
    const mp = maps.get(id);
    if (!mp) return showDetail(`<div class="detail"><div class="empty"><strong>Map #${id}</strong>No monsters or NPCs are placed on this map.</div></div>`);
    const rows = mp.mobs.slice().sort((a, b) => mobs.get(a.mob).lv - mobs.get(b.mob).lv).map(s => {
      const m = mobs.get(s.mob);
      return `<tr><td><div class="withicon">${icon('mob', m.id)}<div><a class="xref" data-mob="${m.id}">${esc(m.name)}</a> ${m.boss ? '<span class="badge boss">Boss</span>' : ''} ${respawn(s.t)}
          <div class="sub">#${m.id}</div></div></div></td>
        <td class="num">${m.lv || '–'}</td><td class="num">${fmt(m.hp)}</td><td class="num">${expTxt(m)}</td><td class="num">${s.t === -3 ? 1 : fmt(s.n)}</td></tr>`;
    }).join('');
    showDetail(`<div class="detail">
      <h2>${esc(mp.name || `Map ${id}`)} ${mp.town ? '<span class="badge player">Town</span>' : ''}</h2>
      <div class="sub">${esc(mp.street)} · ${esc(REGIONS[mp.region] || mp.region || '')} · #${id}${mp.link ? ` · same layout as #${mp.link}` : ''}</div>
      <h3>Map</h3><div id="md-map"><p class="sub">Loading map…</p></div>
      <h3>Monsters (${mp.mobs.length})</h3>
      ${mp.mobs.length ? `<div class="tablewrap"><table><thead><tr><th>Monster</th><th class="num">Level</th><th class="num">HP</th><th class="num">EXP</th><th class="num">On map</th></tr></thead><tbody>${rows}</tbody></table></div>`
        : '<p class="sub">No monsters spawn here.</p>'}
      <h3>NPCs (${mp.npcs.length})</h3>
      ${mp.npcs.length ? `<div>${mp.npcs.map(n => `<span class="qitem">${icon('npc', n, 'sm')}<a class="xref" data-npcq="${n}" title="Quests this NPC starts">${esc(data.npcNames[n] || `NPC ${n}`)}</a></span>`).join('')}</div>`
        : '<p class="sub">No NPCs here.</p>'}
    </div>`);
    renderMapPicture(id);
  }

  // ---------------------------------------------------------------- map picture (minimap + markers, or full scene)
  const COLORS = ['#ff6b6b', '#ffd43b', '#69db7c', '#4dabf7', '#da77f2', '#ff922b', '#38d9a9', '#f783ac', '#a9e34b', '#748ffc', '#e599f7', '#ffa94d'];
  const mapState = { view: 'mini', mobs: true, npcs: true, portals: true, floors: false, only: null };

  async function renderMapPicture(id) {
    const box = $('md-map');
    let L;
    try {
      const r = await fetch(`data/maps/${id}.json`);
      if (!r.ok) throw new Error(r.status);
      L = await r.json();
    } catch (e) { box.innerHTML = '<p class="sub">No layout for this map (run build-mobmaps.ps1).</p>'; return; }
    const imgId = L.imageMap || id;

    // world box: the minimap covers exactly [-centerX, -centerY, width, height]
    let x0, y0, w, h;
    if (L.mini) { const [, , ww, wh, cx, cy] = L.mini; x0 = -cx; y0 = -cy; w = ww; h = wh; }
    else {
      const xs = [], ys = [];
      for (let i = 0; i < L.fh.length; i += 4) { xs.push(L.fh[i], L.fh[i + 2]); ys.push(L.fh[i + 1], L.fh[i + 3]); }
      for (const p of L.life) { xs.push(p[2]); ys.push(p[3]); }
      if (!xs.length) { box.innerHTML = '<p class="sub">This map has no layout data.</p>'; return; }
      const pad = 80;
      x0 = Math.min(...xs) - pad; y0 = Math.min(...ys) - pad * 3; w = Math.max(...xs) - x0 + pad; h = Math.max(...ys) - y0 + pad;
    }
    const R = Math.max(w, h) / 110;          // marker radius in world units
    const stroke = Math.max(w, h) / 500;

    // colour per monster on this map
    const mobIds = [...new Set(L.life.filter(p => p[0] === 'm').map(p => p[1]))];
    const color = new Map(mobIds.map((m, i) => [m, COLORS[i % COLORS.length]]));
    const counts = new Map(); L.life.filter(p => p[0] === 'm').forEach(p => counts.set(p[1], (counts.get(p[1]) || 0) + 1));

    const draw = () => {
      const s = mapState;
      const tools = `<div class="maptools">
          <button class="tog" data-mv="view:mini" aria-pressed="${s.view === 'mini'}">Minimap</button>
          <button class="tog" data-mv="view:scene" aria-pressed="${s.view === 'scene'}">Scene</button>
          ${s.view === 'mini' ? `<span class="sub" style="align-self:center">·</span>
          <button class="tog" data-mv="mobs" aria-pressed="${s.mobs}">Mob spawns</button>
          <button class="tog" data-mv="npcs" aria-pressed="${s.npcs}">NPCs</button>
          <button class="tog" data-mv="portals" aria-pressed="${s.portals}">Portals</button>
          <button class="tog" data-mv="floors" aria-pressed="${s.floors}">Floor lines</button>` : ''}
        </div>`;
      if (s.view === 'scene') {
        box.innerHTML = tools + `<div class="mapbox scene"><img alt="Full map picture"
            src="data/icons/scene/${imgId}.jpg"
            onerror="if(!this.dataset.r){this.dataset.r=1;this.src='https://maplestory.io/api/GMS/83/map/${imgId}/render'}else{this.replaceWith(Object.assign(document.createElement('p'),{className:'sub',textContent:'No scene picture available for this map.'}))}"></div>
          <p class="sub">Full map picture (saved locally by fetch-icons.ps1 -Scenes; otherwise loaded from maplestory.io). Scroll to see all of it.</p>`;
        return;
      }
      const parts = [];
      // local minimap first (fetch-icons.ps1), then maplestory.io
      if (L.mini) parts.push(`<image href="data/icons/minimap/${imgId}.png" x="${x0}" y="${y0}" width="${w}" height="${h}" preserveAspectRatio="none" style="image-rendering:pixelated"
        onerror="if(!this.dataset.r){this.dataset.r=1;this.setAttribute('href','https://maplestory.io/api/GMS/83/map/${imgId}/minimap')}else{this.remove()}"/>`);
      if (s.floors || !L.mini) {
        const segs = [];
        for (let i = 0; i < L.fh.length; i += 4) segs.push(`M${L.fh[i]} ${L.fh[i + 1]}L${L.fh[i + 2]} ${L.fh[i + 3]}`);
        parts.push(`<path d="${segs.join('')}" stroke="#9aa4b2" stroke-width="${stroke * 2}" fill="none" opacity=".8"/>`);
        parts.push(L.ropes.map(([x, y1, y2, lad]) => `<line x1="${x}" y1="${y1}" x2="${x}" y2="${y2}" stroke="${lad ? '#c9a66b' : '#8c7a5b'}" stroke-width="${stroke * 2.5}" stroke-dasharray="${lad ? stroke * 4 : 0}"/>`).join(''));
      }
      if (s.portals) {
        for (const [x, y, pt, tm, pn] of L.portals) {
          if (tm === 999999999) continue;              // spawn points, not exits
          const same = tm === id || tm === imgId;
          const dest = same ? 'warp within this map' : `to ${mapLabel(tm)}`;
          parts.push(`<g class="mk" ${same ? '' : `data-map="${tm}"`}><title>Portal ${esc(pn)}: ${esc(dest)}</title>
            <circle cx="${x}" cy="${y - R}" r="${R * 1.3}" fill="${same ? '#cc5de8' : '#339af0'}" fill-opacity=".35" stroke="${same ? '#cc5de8' : '#74c0fc'}" stroke-width="${stroke * 2}"/></g>`);
        }
      }
      if (s.npcs) {
        for (const p of L.life.filter(p => p[0] === 'n')) {
          const nm = data.npcNames[p[1]] || `NPC ${p[1]}`;
          parts.push(`<g class="mk" data-npcq="${p[1]}"><title>${esc(nm)} (NPC) · click for its quests</title>
            <rect x="${p[2] - R}" y="${p[3] - R * 2.4}" width="${R * 2}" height="${R * 2}" rx="${R * .4}" fill="#51cf66" stroke="#0e1016" stroke-width="${stroke}"/></g>`);
        }
      }
      if (s.mobs) {
        for (const p of L.life.filter(p => p[0] === 'm')) {
          if (s.only && s.only !== p[1]) continue;
          const m = mobs.get(p[1]);
          const timed = p[4] > 0;
          parts.push(`<g class="mk" data-mob="${p[1]}"><title>${esc(m ? m.name : p[1])}${m ? ` (Lv ${m.lv})` : ''}${timed ? ` · respawns every ${Math.round(p[4] / 60)} min` : ''}</title>
            <circle cx="${p[2]}" cy="${p[3] - R}" r="${timed ? R * 1.5 : R}" fill="${color.get(p[1])}" stroke="${timed ? '#fff' : '#0e1016'}" stroke-width="${stroke * (timed ? 2 : 1)}"/></g>`);
        }
      }
      const legend = mobIds.length ? `<div class="legend">
          <button class="tog" data-mv="only:" aria-pressed="${!s.only}">All (${L.life.filter(p => p[0] === 'm').length})</button>
          ${mobIds.map(m => `<button class="tog" data-mv="only:${m}" aria-pressed="${s.only === m}"><span class="dot" style="background:${color.get(m)}"></span>${esc(mobs.get(m)?.name || m)} (${counts.get(m)})</button>`).join('')}
        </div>` : '';
      box.innerHTML = tools + `<div class="mapbox"><svg viewBox="${x0} ${y0} ${w} ${h}" preserveAspectRatio="xMidYMid meet">${parts.join('')}</svg></div>` + legend +
        `<p class="sub">Dots are spawn points (bigger, white-ringed = timed respawn) · green squares are NPCs · blue circles are portals (click to go there), purple ones warp within the map.</p>`;
    };
    box.onclick = e => {
      const b = e.target.closest('[data-mv]');
      if (!b) return;
      e.stopPropagation();
      const [k, v] = b.dataset.mv.split(':');
      if (k === 'view') mapState.view = v;
      else if (k === 'only') mapState.only = v ? +v : null;
      else mapState[k] = !mapState[k];
      draw();
    };
    mapState.only = null;
    draw();
  }
  window.openMob = openMob;
  window.openMap = openMap;
  // used by the Items tab
  window.mobsApi = {
    load,
    mob: id => mobs.get(id),
    all: () => [...mobs.values()],
    mapLabel,
    region: id => { const m = maps.get(id); return m ? (REGIONS[m.region] || m.region) : ''; },
    npcMaps: npcId => [...maps.values()].filter(m => m.npcs.includes(npcId)).map(m => m.id),
  };

  // ---------------------------------------------------------------- wiring
  document.addEventListener('click', e => {
    const a = e.target.closest('[data-mob],[data-map],[data-npcq],[data-questid],#m-back');
    if (!a) return;
    e.preventDefault();
    if (a.id === 'm-back') hideDetail();
    else if (a.dataset.mob) openMob(+a.dataset.mob);
    else if (a.dataset.map) openMap(+a.dataset.map);
    else if (a.dataset.npcq) window.questsApi.search(a.dataset.npcq);
    else if (a.dataset.questid) window.questsApi.search(a.dataset.questid);
  });
  // Mobs and Maps are separate tabs sharing this view; each keeps its own search and filters.
  const FILTERS = ['m-q', 'm-min', 'm-max', 'm-region'];
  const saved = { mob: {}, map: {} };
  const UI = {
    mob: { ph: 'Search monster: name or ID (e.g. mano, 2220000)…', lv: 'Level', spawn: 'Only ones that spawn' },
    map: { ph: 'Search map, area or a monster in it (e.g. ludibrium, 104000400, wraith)…', lv: 'Training level', spawn: 'Only maps with monsters' },
  };
  function switchKind(kind) {
    const cur = $('m-kind').value;
    if (cur === kind) return false;
    saved[cur] = Object.fromEntries(FILTERS.map(id => [id, $(id).value]));
    saved[cur].spawn = $('m-spawn').checked;
    FILTERS.forEach(id => $(id).value = saved[kind][id] || '');
    $('m-spawn').checked = saved[kind].spawn ?? true;
    $('m-kind').value = kind;
    $('m-q').placeholder = UI[kind].ph;
    $('m-lvlabel').textContent = UI[kind].lv;
    $('m-spawn').parentElement.lastChild.textContent = ' ' + UI[kind].spawn;
    hideDetail();
    return true;
  }
  document.addEventListener('tab:show', e => {
    if (e.detail !== 'mobs' && e.detail !== 'maps') return;
    switchKind(e.detail === 'maps' ? 'map' : 'mob');
    apply(false);
    if ($('m-detail').hidden) $('m-q').focus();
  });
  document.addEventListener('rates:loaded', () => { if (data && !$('view-mobs').hidden && $('m-detail').hidden) render(); });

  let t;
  ['m-q', 'm-min', 'm-max'].forEach(id => $(id).addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => { hideDetail(); apply(); }, 200); }));
  ['m-region', 'm-spawn'].forEach(id => $(id).addEventListener('change', () => { hideDetail(); apply(); }));
  $('m-reset').addEventListener('click', () => {
    FILTERS.forEach(id => $(id).value = '');
    $('m-spawn').checked = true; hideDetail(); apply();
  });
  $('m-more-btn').addEventListener('click', () => { shown += PAGE; render(); });
})();
