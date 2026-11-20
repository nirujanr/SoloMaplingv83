// "Leveling" tab: EXP calculator, best monsters for your level, the server's leveling rules and
// the full EXP table. Data: data/leveling.json (build-leveling.ps1) + monsters from mobmaps.json.
// The EXP-per-kill math follows the server code (server/life/Monster.java):
//   solo:  monster EXP x your damage share x EXP rate
//   party: share = 0.8 x (your level / party level total) + 0.2 if you did the most damage,
//          plus a party bonus of 5% per member on the map (when 2+), x PARTY_BONUS_EXP_RATE.
(() => {
  const $ = id => document.getElementById(id);
  const fmt = n => Math.round(n).toLocaleString('en-US');
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const short = n => n >= 1e9 ? (n / 1e9).toFixed(2).replace(/\.?0+$/, '') + 'b' : n >= 1e6 ? (n / 1e6).toFixed(2).replace(/\.?0+$/, '') + 'm'
                  : n >= 1e3 ? (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'k' : String(Math.round(n));

  let L = null, cum = [], ready = false;

  async function load() {
    if (L) return true;
    try {
      const r = await fetch(`data/leveling.json?t=${Date.now()}`, { cache: 'no-store' });
      if (!r.ok) throw new Error(r.status);
      L = await r.json();
    } catch (e) {
      $('view-leveling').innerHTML = '<div class="empty"><strong>No leveling.json yet</strong>Run fm-dashboard\\build-leveling.ps1, then reload this page.</div>';
      return false;
    }
    cum = [0, 0];   // cum[lv] = total EXP to reach level lv
    for (let lv = 1; lv < 200; lv++) cum[lv + 1] = cum[lv] + L.exp[lv];
    return true;
  }

  const rate = () => L.rates.exp || 1;
  const cap = () => $('l-cyg').checked ? 120 : 200;
  const clampLv = v => Math.min(cap() - 1, Math.max(1, v || 1));

  // EXP you get per kill for the current solo/party settings
  function perKill(mobExp) {
    const n = +$('l-party').value, mvp = $('l-mvp').checked;
    if (n <= 1) return mobExp * rate();
    const N = L.numbers;
    const share = (N.EXP_SPLIT_COMMON_MOD || 0.8) / n + (mvp ? (N.EXP_SPLIT_MVP_MOD || 0.2) : 0);
    const bonus = 0.05 * n * (N.PARTY_BONUS_EXP_RATE || 1);
    return mobExp * share * (1 + bonus) * rate();
  }

  function needed() {
    const lv = clampLv(parseInt($('l-lv').value, 10));
    const pct = Math.min(99.999, Math.max(0, parseFloat($('l-pct').value) || 0));
    let to = parseInt($('l-to').value, 10);
    if (isNaN(to) || to <= lv) to = lv + 1;
    to = Math.min(cap(), to);
    const have = cum[lv] + L.exp[lv] * pct / 100;
    return { lv, pct, to, need: Math.max(0, cum[to] - have), have };
  }

  function pickedMob() {
    const v = $('l-mob').value, m = v.match(/#(\d+)/);
    return m && window.mobsApi ? window.mobsApi.mob(+m[1]) : null;
  }

  async function render() {
    if (!await load()) return;
    const { lv, pct, to, need } = needed();
    const stat = (k, v) => `<div><div class="k">${k}</div><div class="v">${v}</div></div>`;
    const mob = pickedMob();
    const per = mob ? perKill(mob.exp) : 0;
    $('l-result').innerHTML =
      stat('EXP needed', `${fmt(need)}<div class="sub">${short(need)}</div>`) +
      stat('Levels', `${lv} → ${to}`) +
      stat(`To next level`, `${fmt(L.exp[lv] * (1 - pct / 100))}`) +
      stat('Done of level 200', `${(100 * (cum[lv] + L.exp[lv] * pct / 100) / cum[200]).toFixed(3)}%`) +
      (mob ? stat(`EXP / ${esc(mob.name)}`, `${fmt(per)}<div class="sub">base ${fmt(mob.exp)} × ${rate()}${+$('l-party').value > 1 ? ' · party split' : ''}</div>`) +
             stat('Kills needed', `${fmt(Math.ceil(need / Math.max(1, per)))}`) : '');
    $('l-note').textContent = `EXP rate ×${rate()}${+$('l-party').value > 1 ? `. Party math assumes everyone is your level and the party does all the damage (each member gets 80% ÷ members, +20% for the top damager, +${5 * +$('l-party').value}% party bonus).` : '. Solo, assuming you do all the damage.'}`;
    renderBest(lv, to, need);
    renderTable(lv);
  }

  async function renderBest(lv, to, need) {
    if (!window.mobsApi || !await window.mobsApi.load()) { $('l-best').innerHTML = '<tr><td colspan="7" class="sub">Monster data not available (run build-mobmaps.ps1).</td></tr>'; return; }
    // look-alike copies (same name and stats, different IDs) are merged: keep the one that spawns the most
    const spawnTotal = m => m.spawns.reduce((s, x) => s + (x.t >= 0 ? x.n : 0), 0);
    const best = new Map();
    for (const m of window.mobsApi.all()) {
      if (m.boss || !(m.exp > 0) || !(m.hp > 0) || Math.abs(m.lv - lv) > 5 || !m.spawns.some(s => s.t >= 0)) continue;
      const key = `${m.name.replace(/\s*\d+$/, '')}|${m.lv}|${m.hp}|${m.exp}`;
      const cur = best.get(key);
      if (!cur || spawnTotal(m) > spawnTotal(cur)) best.set(key, m);
    }
    const list = [...best.values()].sort((a, b) => b.exp / b.hp - a.exp / a.hp || spawnTotal(b) - spawnTotal(a)).slice(0, 15);
    $('l-best').innerHTML = list.map(m => {
      const best = m.spawns.filter(s => s.t >= 0).sort((a, b) => b.n - a.n)[0];
      const per = perKill(m.exp);
      return `<tr>
        <td><div class="withicon">${window.icon('mob', m.id)}<div><a class="xref" data-mob="${m.id}">${esc(m.name)}</a>
          <div class="sub"><a class="xref" data-lpick="${m.id}">use in calculator</a></div></div></div></td>
        <td class="num">${m.lv}</td><td class="num">${fmt(m.hp)}</td>
        <td class="num">${fmt(per)}<div class="sub">base ${fmt(m.exp)}</div></td>
        <td class="num">${(m.exp / m.hp).toFixed(3)}</td>
        <td class="num">${fmt(Math.ceil(need / Math.max(1, per)))}</td>
        <td>${best ? `<a class="xref" data-map="${best.map}">${esc(window.mobsApi.mapLabel(best.map))}</a><div class="sub">${best.n} spawns · ${esc(window.mobsApi.region(best.map))}</div>` : '–'}</td>
      </tr>`;
    }).join('') || '<tr><td colspan="7" class="sub">No regular monsters within 5 levels.</td></tr>';
  }

  let tableLv = null;
  function renderTable(lv) {
    if (tableLv === null) {
      const rows = [];
      for (let l = 1; l < 200; l++) {
        rows.push(`<tr data-lv="${l}"><td class="num">${l}</td><td class="num">${fmt(L.exp[l])}</td><td class="num">${fmt(cum[l])}</td>
          <td class="num">${(100 * cum[l] / cum[200]).toFixed(l < 100 ? 3 : 1)}%</td></tr>`);
      }
      rows.push(`<tr data-lv="200"><td class="num">200</td><td class="num">–</td><td class="num">${fmt(cum[200])}</td><td class="num">100%</td></tr>`);
      $('l-table').innerHTML = rows.join('');
    }
    if (tableLv !== lv) {
      $('l-table').querySelector('tr.me')?.classList.remove('me');
      $('l-table').querySelector(`tr[data-lv="${lv}"]`)?.classList.add('me');
      tableLv = lv;
    }
  }

  function renderRules() {
    const f = L.flags, N = L.numbers, r = L.rates;
    const on = (k, yes, no) => f[k] ? yes : no;
    const card = (title, body) => `<article class="card"><h3>${title}</h3>${body}</article>`;
    $('l-rules').innerHTML = [
      card('Rates', `<ul>
        <li>EXP <b>×${r.exp}</b> · Meso ×${r.meso} · Drop ×${r.drop} · Boss drop ×${r.boss_drop}</li>
        <li>Quest EXP: ${on('USE_QUEST_RATE', `×${r.quest} quest rate`, `normal ×${r.exp} (the ×${r.quest} quest rate is off)`)}</li>
        <li>Beginners (Lv ≤ 10): ${on('USE_ENFORCE_NOVICE_EXPRATE', 'fixed at ×1', `also ×${r.exp}`)}</li>
        <li>Rates ${on('USE_ADD_RATES_BY_LEVEL', 'go up every 20 levels', "don't change with level")}</li>
        <li>EXP coupons multiply on top · ${on('USE_STACK_COUPON_RATES', 'several stack', 'only one counts at a time')}</li>
      </ul>`),
      card('How monster EXP is shared', `<ul>
        <li>Solo: you get EXP for <b>your share of the damage</b> (60% of its HP = 60% of its EXP) × rate.</li>
        <li>Party: the party's share is split <b>${Math.round((N.EXP_SPLIT_COMMON_MOD || 0.8) * 100)}% by level</b>, +<b>${Math.round((N.EXP_SPLIT_MVP_MOD || 0.2) * 100)}%</b> to the top damager.</li>
        <li>Party bonus: <b>+5% per member</b> on the map (2+ members) × ${N.PARTY_BONUS_EXP_RATE}.</li>
        <li>Leeching: ${on('USE_ENFORCE_MOB_LEVEL_RANGE', `members must be within ${N.EXP_SPLIT_LEVEL_INTERVAL} levels of the monster or a damager`, 'party members on the map get EXP <b>at any level</b>, even without hitting')}.</li>
        <li>Holy Symbol: ${on('USE_FULL_HOLY_SYMBOL', 'full bonus always', 'only 1/5 of its bonus solo, full bonus in a party')}.</li>
        <li>Curse halves EXP. Monsters respawn every ${(N.RESPAWN_INTERVAL || 10000) / 1000}s.</li>
      </ul>`),
      card('Each level up', `<ul>
        <li><b>5 AP</b> (Cygnus: +2 extra at Lv 11–18, +1 extra at Lv 19–77).</li>
        <li>${on('USE_AUTOASSIGN_STARTERS_AP', 'Beginners up to Lv 10 get AP put in automatically (STR, then some DEX).', 'Beginners assign their own AP.')}</li>
        <li><b>3 SP</b> once you have a job (beginners get none).</li>
        <li>Level cap <b>200</b> (Cygnus Knights <b>120</b>)${on('USE_ENFORCE_JOB_LEVEL_RANGE', ', and you must job advance to keep leveling', '; no job-advance level lock')}.</li>
        <li>${on('USE_RANDOMIZE_HPMP_GAIN', 'HP/MP gains are random within the range below; INT adds extra MP.', 'HP/MP gains are fixed.')}</li>
      </ul>
      <table><thead><tr><th>Class</th><th>HP</th><th>MP</th></tr></thead><tbody>
        <tr><td>Beginner</td><td>12–16</td><td>10–12</td></tr>
        <tr><td>Warrior</td><td>24–28 + skill</td><td>4–6</td></tr>
        <tr><td>Magician</td><td>10–14</td><td>22–24 + skill</td></tr>
        <tr><td>Bowman / Thief</td><td>20–24</td><td>14–16</td></tr>
        <tr><td>Pirate</td><td>22–28 + skill</td><td>18–23</td></tr>
        <tr><td>Aran</td><td>44–48</td><td>4–8</td></tr>
      </tbody></table>`),
      card('Dying', `<table><tbody>
        <tr><td>Normal map, LUK under 50</td><td><b>10%</b> of the level's EXP</td></tr>
        <tr><td>Normal map, LUK 50+</td><td><b>5%</b></td></tr>
        <tr><td>Town</td><td><b>1%</b></td></tr>
        <tr><td>Beginner or Safety Charm</td><td>nothing</td></tr>
      </tbody></table>
      <p class="sub">Some maps (e.g. Mu Lung Dojo) never take EXP.</p>`),
      card('Off on this server', `<ul>
        ${[!f.USE_ADD_SLOTS_BY_LEVEL && 'Extra inventory slots every 20 levels', !f.USE_ADD_RATES_BY_LEVEL && 'Rate boosts every 20 levels',
           !f.USE_PERFECT_PITCH && 'Perfect Pitch rewards from Lv 30', !f.USE_ENFORCE_JOB_LEVEL_RANGE && 'Level lock until you job advance',
           !f.USE_SERVER_AUTOASSIGNER && 'Server AP auto-assigner (beyond beginners)'].filter(Boolean).map(x => `<li>${x}</li>`).join('')}
      </ul>`),
    ].join('');
  }

  async function init() {
    if (ready) return render();
    if (!await load()) return;
    ready = true;
    renderRules();
    if (window.mobsApi && await window.mobsApi.load()) {
      $('l-mobs').innerHTML = window.mobsApi.all().filter(m => m.exp > 0 && m.spawns.length)
        .sort((a, b) => a.lv - b.lv).map(m => `<option value="${esc(m.name)} (Lv ${m.lv}) #${m.id}"></option>`).join('');
    }
    render();
  }

  document.addEventListener('tab:show', e => { if (e.detail === 'leveling') init(); });
  let t;
  ['l-lv', 'l-pct', 'l-to', 'l-mob'].forEach(id => $(id).addEventListener('input', () => { clearTimeout(t); t = setTimeout(render, 200); }));
  ['l-party', 'l-mvp', 'l-cyg'].forEach(id => $(id).addEventListener('change', render));
  document.addEventListener('click', e => {
    const a = e.target.closest('[data-lpick]');
    if (!a) return;
    e.preventDefault();
    const m = window.mobsApi.mob(+a.dataset.lpick);
    $('l-mob').value = `${m.name} (Lv ${m.lv}) #${m.id}`;
    render();
    window.scrollTo(0, 0);
  });
})();
