// Jobs, Skills and Quests tabs. Data: data/skills.json and data/quests.json, built from the
// WZ files by build-gamedata.ps1. Skill icons come from maplestory.io's per-job skillbook.
(() => {
  const $ = id => document.getElementById(id);
  const fmt = n => Number(n).toLocaleString('en-US');
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const icon = (kind, id, cls = '') => window.icon(kind, id).replace('class="ico', `class="ico ${cls}`);
  const TIER = ['Beginner', '1st job', '2nd job', '3rd job', '4th job'];

  let skills = null, quests = null;
  let jobs = [], jobById = new Map(), skillById = new Map(), questById = new Map();

  async function getJson(url) {
    const res = await fetch(`${url}?t=${Date.now()}`, { cache: 'no-store' });
    if (!res.ok) throw new Error(`${url}: ${res.status}`);
    return res.json();
  }
  const missing = (el, file) => { el.innerHTML = `<div class="empty"><strong>No ${file} yet</strong>Run fm-dashboard\\build-gamedata.ps1, then reload this page.</div>`; };

  async function loadSkills() {
    if (skills) return true;
    try { skills = await getJson('data/skills.json'); } catch (e) { return false; }
    jobs = skills.jobs.filter(j => j.skills.length);
    for (const j of jobs) { jobById.set(j.id, j); for (const s of j.skills) skillById.set(s.id, { ...s, job: j.id }); }
    return true;
  }
  async function loadQuests() {
    if (quests) return true;
    try { quests = await getJson('data/quests.json'); } catch (e) { return false; }
    for (const q of quests.quests) {
      questById.set(q.id, q);
      const names = [q.name, q.parent, npcName(q.startNpc), npcName(q.endNpc),
        ...(q.items || []).map(([i]) => itemName(i)), ...(q.mobs || []).map(([m]) => mobName(m)),
        ...(q.reward || []).map(([i]) => itemName(i))];
      q.hay = names.filter(Boolean).join(' ').toLowerCase();
    }
    return true;
  }
  const itemName = id => quests.items[id] || `Item ${id}`;
  const mobName = id => quests.mobs[id] || `Mob ${id}`;
  const npcName = id => id ? (quests.npcs[id] || `NPC ${id}`) : '';

  // job tree helpers
  // 'Beginner', 1..5 explorers, 8/9 GM, 10..15 Cygnus, 20/21 Aran
  const groupOf = id => id === 0 ? 'Beginner' : Math.floor(id / 100);
  const GROUP_NAMES = { Beginner: 'Explorer beginner', 1: 'Warrior', 2: 'Magician', 3: 'Bowman', 4: 'Thief', 5: 'Pirate',
    10: 'Cygnus beginner', 11: 'Dawn Warrior', 12: 'Blaze Wizard', 13: 'Wind Archer', 14: 'Night Walker', 15: 'Thunder Breaker',
    20: 'Aran beginner', 21: 'Aran', 8: 'GM', 9: 'GM' };
  const SECTION = g => g === 'Beginner' || (g >= 1 && g <= 5) ? 'Explorers' : (g >= 10 && g <= 15) ? 'Cygnus Knights'
                    : (g === 20 || g === 21) ? 'Aran' : 'GM / other';

  // ------------------------------------------------------------------------------ Jobs
  async function renderJobs() {
    const el = $('j-tree');
    if (!await loadSkills()) return missing(el, 'skills.json');
    await loadQuests();
    const qCount = new Map();
    if (quests) for (const q of quests.quests) for (const j of (q.jobs || [])) qCount.set(j, (qCount.get(j) || 0) + 1);

    const card = j => `<button class="jcard" data-job="${j.id}">
        <div><span class="tierchip">${TIER[j.tier] || ''}</span><span class="sub">Lv ${j.level}+</span></div>
        <div class="jn">${esc(j.name)}</div>
        <div class="sub">${j.skills.length} skill${j.skills.length === 1 ? '' : 's'}${qCount.get(j.id) ? ` · <a class="xref" data-jobq="${j.id}">${qCount.get(j.id)} quests</a>` : ''}</div>
      </button>`;
    const arrow = '<span class="jarrow">→</span>';

    // group jobs: section -> class group -> branches
    const sections = new Map();
    for (const j of jobs) {
      const g = groupOf(j.id), sec = SECTION(g);
      if (!sections.has(sec)) sections.set(sec, new Map());
      const groups = sections.get(sec);
      if (!groups.has(g)) groups.set(g, []);
      groups.get(g).push(j);
    }
    let html = '';
    for (const [sec, groups] of sections) {
      html += `<section class="jclass"><h2>${esc(sec)}</h2>`;
      for (const [g, list] of groups) {
        const first = list.filter(j => j.tier <= 1);
        const branches = new Map();
        for (const j of list.filter(j => j.tier >= 2)) {
          const key = Math.floor(j.id / 10);
          if (!branches.has(key)) branches.set(key, []);
          branches.get(key).push(j);
        }
        if (!branches.size) { html += `<div class="jrow">${first.map(card).join(arrow)}</div>`; continue; }
        for (const [, br] of branches) {
          br.sort((a, b) => a.tier - b.tier);
          html += `<div class="jrow">${[...first, ...br].map(card).join(arrow)}</div>`;
        }
      }
      html += '</section>';
    }
    el.innerHTML = html;
  }

  // ------------------------------------------------------------------------------ Skills
  const iconCache = new Map(); // jobId -> Promise<Map(skillId -> dataUrl)>
  function skillIcons(jobId) {
    if (!iconCache.has(jobId)) {
      iconCache.set(jobId, fetch(`https://maplestory.io/api/GMS/83/job/${jobId}/skillbook`)
        .then(r => r.ok ? r.json() : { skills: [] })
        .then(d => new Map((d.skills || []).filter(s => s.icon).map(s => [s.id, `data:image/png;base64,${s.icon}`])))
        .catch(() => new Map()));
    }
    return iconCache.get(jobId);
  }

  let skillsReady = false;
  async function initSkills() {
    if (skillsReady) return true;
    if (!await loadSkills()) { missing($('s-list'), 'skills.json'); return false; }
    const opts = ['<option value="">All jobs</option>'];
    let lastSec = '';
    for (const j of jobs) {
      const sec = SECTION(groupOf(j.id));
      if (sec !== lastSec) { if (lastSec) opts.push('</optgroup>'); opts.push(`<optgroup label="${esc(sec)}">`); lastSec = sec; }
      opts.push(`<option value="${j.id}">${esc(j.name)} · ${TIER[j.tier]}</option>`);
    }
    opts.push('</optgroup>');
    $('s-job').innerHTML = opts.join('');
    $('s-job').value = '112';
    skillsReady = true;
    return true;
  }

  function levelLine(s, lv) {
    return `<b>Lv ${lv}/${s.max}</b> ${esc(s.lv[lv - 1] || '')}`;
  }

  async function renderSkills() {
    if (!await initSkills()) return;
    const jobId = $('s-job').value;
    const terms = $('s-q').value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const showAll = $('s-all').checked;
    let list = [];
    for (const j of jobs) {
      if (jobId && String(j.id) !== jobId && !terms.length) continue;
      for (const s of j.skills) {
        const hay = `${s.name} ${s.desc} ${s.id}`.toLowerCase();
        if (terms.length && !terms.every(t => hay.includes(t))) continue;
        list.push({ s, j });
      }
    }
    if (terms.length && jobId) list.sort((a, b) => (String(b.j.id) === jobId) - (String(a.j.id) === jobId));
    const LIMIT = 120;
    $('s-count').textContent = `${fmt(list.length)} skill${list.length === 1 ? '' : 's'}${terms.length ? ' across all jobs' : ''}${list.length > LIMIT ? ` · showing ${LIMIT}` : ''}`;
    $('s-list').innerHTML = list.slice(0, LIMIT).map(({ s, j }) => {
      const req = (s.req || []).map(([id, lv]) => {
        const r = skillById.get(id);
        return `<a class="xref" data-skill="${id}">${esc(r ? r.name : id)}</a> Lv ${lv}`;
      }).join(', ');
      return `<article class="card">
        <div class="withicon"><img class="ico" data-skillicon="${s.id}" data-job="${j.id}" alt="" style="visibility:hidden">
          <div><div class="name">${esc(s.name)}
            ${s.hidden ? '<span class="badge quest" title="Hidden until learned from a Mastery Book or quest">Needs book</span>' : ''}</div>
          <div class="sub">#${s.id} · <a class="xref" data-sjob="${j.id}">${esc(j.name)}</a> · max Lv ${s.max}${s.master && s.master !== s.max ? ` (master ${s.master})` : ''}</div></div></div>
        ${s.desc ? `<div class="desc">${esc(s.desc)}</div>` : ''}
        ${req ? `<div class="sub">Requires: ${req}</div>` : ''}
        ${s.max ? (showAll
          ? `<div class="lvls">${s.lv.map((_, i) => `<div>${levelLine(s, i + 1)}</div>`).join('')}</div>`
          : `<input type="range" min="1" max="${s.max}" value="${s.max}" data-lvfor="${s.id}" aria-label="Skill level">
             <div class="lvl" id="lv-${s.id}">${levelLine(s, s.max)}</div>`) : ''}
      </article>`;
    }).join('') || '<div class="empty"><strong>No skills match</strong>Try another word.</div>';

    // icons: local copy first (data/icons/skill, saved by fetch-icons.ps1); if missing, the job's skillbook online
    document.querySelectorAll('#s-list img[data-skillicon]').forEach(img => {
      img.onload = () => { img.style.visibility = ''; };
      img.onerror = () => {
        img.onerror = null;
        skillIcons(+img.dataset.job).then(m => {
          const src = m.get(+img.dataset.skillicon);
          if (src) { img.src = src; }
        });
      };
      img.src = `data/icons/skill/${img.dataset.skillicon}.png`;
    });
  }

  // ------------------------------------------------------------------------------ Quests
  const QPAGE = 100;
  const HANGUL = /[가-힣]/; // some event quests were never translated in v83
  let qView = [], qShown = QPAGE, questsReady = false;

  async function initQuests() {
    if (questsReady) return true;
    if (!await loadQuests()) { missing($('q-empty'), 'quests.json'); $('q-empty').hidden = false; return false; }
    await loadSkills();
    const used = new Set();
    for (const q of quests.quests) for (const j of (q.jobs || [])) used.add(j);
    const opts = [...used].sort((a, b) => a - b).map(id => {
      const j = jobById.get(id);
      return `<option value="${id}">${esc(j ? j.name : `Job ${id}`)}</option>`;
    });
    $('q-job').insertAdjacentHTML('beforeend', opts.join(''));
    questsReady = true;
    return true;
  }

  async function applyQuests(reset = true) {
    if (!await initQuests()) return;
    const terms = $('qq').value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const lv = parseInt($('q-lv').value, 10);
    const job = $('q-job').value, reward = $('q-reward').value;
    const hideKr = $('q-kr').checked;
    qView = quests.quests.filter(q =>
      (!hideKr || !HANGUL.test(q.name)) &&
      (!terms.length || terms.every(t => /^\d+$/.test(t)
        ? (q.id === +t || q.startNpc === +t || q.endNpc === +t || (q.items || []).some(([i]) => i === +t)
           || (q.reward || []).some(([i]) => i === +t) || (q.mobs || []).some(([m]) => m === +t) || q.hay.includes(t))
        : q.hay.includes(t))) &&
      (isNaN(lv) || ((q.lvmin || 0) <= lv && (!q.lvmax || q.lvmax >= lv))) &&
      (!job || (job === 'none' ? !(q.jobs && q.jobs.length) : (q.jobs || []).includes(+job))) &&
      (!reward || (reward === 'item' ? q.reward : reward === 'exp' ? q.exp : reward === 'meso' ? q.meso : q.skills)));
    qView.sort((a, b) => (a.lvmin || 0) - (b.lvmin || 0) || a.name.localeCompare(b.name));
    if (reset) qShown = QPAGE;
    renderQuests();
  }

  // mob chips open the Mobs & Maps page; item chips show who drops the item
  const chip = (kind, id, label, extra = '', cls = '') =>
    `<span class="qitem ${cls}">${icon(kind, id, 'sm')}${extra}<a class="xref" ${kind === 'mob' ? `data-mob="${id}"` : `data-item="${id}"`}>${esc(label)}</a></span>`;

  function renderQuests() {
    $('q-rows').innerHTML = qView.slice(0, qShown).map(q => {
      const pre = (q.pre || []).filter(([, st]) => st === 2).map(([id]) => {
        const p = questById.get(id);
        return `<a class="xref" data-quest="${id}">${esc(p ? p.name : id)}</a>`;
      });
      // one job: its name; several: the class lines they belong to (e.g. "Bowman, Wind Archer")
      const qj = q.jobs || [];
      const groups = [...new Set(qj.map(j => GROUP_NAMES[groupOf(j)] || `Job ${j}`))];
      const jobsTxt = qj.length === 1 ? [esc(jobById.get(qj[0])?.name || `Job ${qj[0]}`)]
        : groups.length > 3 ? [`<span title="${esc(groups.join(', '))}">${groups.length} job classes</span>`]
        : groups.map(n => `${esc(n)} jobs`);
      const needs = [
        ...(q.mobs || []).map(([m, c]) => chip('mob', m, mobName(m), `<b>${fmt(c)}×</b>&nbsp;`)),
        ...(q.items || []).filter(([, c]) => c > 0).map(([i, c]) => chip('item', i, itemName(i), `<b>${fmt(c)}×</b>&nbsp;`)),
      ].join('');
      const rand = (q.reward || []).filter(([, , p]) => p > 0);
      const rewards = [
        q.exp ? `<span class="chip">EXP ${fmt(q.exp)}</span>` : '',
        q.meso ? `<span class="chip">${q.meso > 0 ? '' : '−'}${fmt(Math.abs(q.meso))} mesos</span>` : '',
        q.fame ? `<span class="chip">Fame ${q.fame > 0 ? '+' : ''}${q.fame}</span>` : '',
        ...(q.reward || []).filter(([, , p]) => p <= 0).map(([i, c]) => chip('item', i, itemName(i), c > 1 ? `<b>${fmt(c)}×</b>&nbsp;` : '')),
        rand.length ? `<div class="sub">One at random:</div>${rand.map(([i, c]) => chip('item', i, itemName(i), c > 1 ? `<b>${fmt(c)}×</b>&nbsp;` : '', 'rand')).join('')}` : '',
        ...(q.skills || []).map(id => `<span class="chip up">Skill: ${esc(skillById.get(id)?.name || id)}</span>`),
      ].join('');
      const story = [q.summary, q.desc, q.done ? `When finished: ${q.done}` : ''].filter(Boolean).join('\n\n');
      return `<tr>
        <td><div class="name">${esc(q.name)}</div>
            <div class="sub">#${q.id}${q.parent ? ` · ${esc(q.parent)}` : ''}${jobsTxt.length ? ` · ${jobsTxt.join(', ')}` : ''}</div>
            ${pre.length ? `<div class="sub">After: ${pre.join(', ')}</div>` : ''}
            ${story ? `<details class="story"><summary>Story</summary><p>${esc(story)}</p></details>` : ''}</td>
        <td class="num">${q.lvmin || q.lvmax ? `${q.lvmin || 1}${q.lvmax ? `–${q.lvmax}` : '+'}` : '<span class="sub">any</span>'}</td>
        <td>${q.startNpc ? `<div class="withicon">${icon('npc', q.startNpc, 'sm')}<div><div>${esc(npcName(q.startNpc))}</div>
              ${q.endNpc && q.endNpc !== q.startNpc ? `<div class="sub">ends at ${esc(npcName(q.endNpc))}</div>` : ''}</div></div>` : '<span class="sub">–</span>'}</td>
        <td>${needs || '<span class="sub">–</span>'}</td>
        <td>${rewards || '<span class="sub">–</span>'}</td>
      </tr>`;
    }).join('');
    $('q-count').textContent = `${fmt(qView.length)} quest${qView.length === 1 ? '' : 's'}${qView.length > qShown ? ` · showing ${fmt(qShown)}` : ''}`;
    $('q-more').hidden = qView.length <= qShown;
    if (!qView.length) { $('q-empty').innerHTML = '<strong>No matches</strong>Try a shorter search or clear some filters.'; $('q-empty').hidden = false; }
    else $('q-empty').hidden = true;
  }

  // used by the Mobs & Maps tab
  window.questsApi = {
    load: loadQuests,
    forMob: id => quests ? quests.quests.filter(q => (q.mobs || []).some(([m]) => m === id)) : [],
    npcQuests: id => quests ? quests.quests.filter(q => q.startNpc === id) : [],
    needItem: id => quests ? quests.quests.filter(q => (q.items || []).some(([i, c]) => i === id && c > 0)) : [],
    rewardItem: id => quests ? quests.quests.filter(q => (q.reward || []).some(([i]) => i === id)) : [],
    search: q => {
      ['q-lv', 'q-job', 'q-reward'].forEach(i => $(i).value = '');
      $('qq').value = q; window.showTab('quests'); applyQuests();
    },
  };

  // ------------------------------------------------------------------------------ wiring
  let jobsDone = false;
  document.addEventListener('tab:show', e => {
    if (e.detail === 'jobs' && !jobsDone) { jobsDone = true; renderJobs(); }
    if (e.detail === 'skills') { renderSkills(); $('s-q').focus(); }
    if (e.detail === 'quests') { applyQuests(false); $('qq').focus(); }
  });

  document.addEventListener('click', e => {
    const a = e.target.closest('[data-jobq],[data-job].jcard,[data-skill],[data-sjob],[data-quest]');
    if (!a) return;
    e.preventDefault();
    if (a.dataset.jobq) {
      e.stopPropagation();
      $('qq').value = ''; $('q-lv').value = '';
      window.showTab('quests');
      initQuests().then(() => { $('q-job').value = a.dataset.jobq; applyQuests(); });
    } else if (a.classList.contains('jcard')) {
      $('s-q').value = '';
      window.showTab('skills');
      initSkills().then(() => { $('s-job').value = a.dataset.job; renderSkills(); });
    } else if (a.dataset.sjob) {
      $('s-q').value = ''; $('s-job').value = a.dataset.sjob; renderSkills();
    } else if (a.dataset.skill) {
      $('s-q').value = a.dataset.skill; renderSkills();
    } else if (a.dataset.quest) {
      $('qq').value = a.dataset.quest; $('q-lv').value = ''; $('q-job').value = ''; $('q-reward').value = ''; applyQuests();
    }
    window.scrollTo(0, 0);
  });

  document.addEventListener('input', e => {
    if (e.target.matches('input[data-lvfor]')) {
      const s = skillById.get(+e.target.dataset.lvfor);
      $(`lv-${s.id}`).innerHTML = levelLine(s, +e.target.value);
    }
  });

  let t1, t2;
  $('s-q').addEventListener('input', () => { clearTimeout(t1); t1 = setTimeout(renderSkills, 150); });
  $('s-job').addEventListener('change', () => { $('s-q').value = ''; renderSkills(); });
  $('s-all').addEventListener('change', renderSkills);
  ['qq', 'q-lv'].forEach(id => $(id).addEventListener('input', () => { clearTimeout(t2); t2 = setTimeout(applyQuests, 200); }));
  ['q-job', 'q-reward', 'q-kr'].forEach(id => $(id).addEventListener('change', () => applyQuests()));
  $('q-reset').addEventListener('click', () => { ['qq', 'q-lv', 'q-job', 'q-reward'].forEach(id => $(id).value = ''); $('q-kr').checked = true; applyQuests(); });
  $('q-more-btn').addEventListener('click', () => { qShown += QPAGE; renderQuests(); });
})();
