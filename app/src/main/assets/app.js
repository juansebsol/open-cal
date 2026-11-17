/* Open Cal TV UI. Plain JavaScript: no framework, CDN or remote code. */
(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const pad = v => String(v).padStart(2, '0');
  const ymd = d => `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`;
  const dayOf = ms => ymd(new Date(ms));
  const addDays = (day, n) => { const d = new Date(day + 'T12:00:00'); d.setDate(d.getDate() + n); return ymd(d); };
  const startOf = day => new Date(day + 'T00:00:00').getTime();
  const noon = day => new Date(day + 'T12:00:00');
  const today = () => ymd(new Date());
  const fmt = (opts, d) => new Intl.DateTimeFormat('en-US', opts).format(d);
  const fmtTime = d => fmt({hour: 'numeric', minute: '2-digit'}, d);
  const hourLabel = h => fmt({hour: 'numeric'}, new Date(2000, 0, 1, h % 24));
  const longDate = d => fmt({weekday: 'long', month: 'long', day: 'numeric'}, d);
  const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;
  const DAY_MS = 86400000, HOUR_MS = 3600000, MIN_HOURS = 8;

  const icon = type => {
    const paths = {
      calendar:'<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 3v4M17 3v4M3 10h18"/>',
      chevronLeft:'<path d="m15 18-6-6 6-6"/>', chevronRight:'<path d="m9 18 6-6-6-6"/>',
      refresh:'<path d="M20 11a8 8 0 0 0-15-3M4 4v4h4M4 13a8 8 0 0 0 15 3m1 4v-4h-4"/>',
      settings:'<circle cx="12" cy="12" r="3.2"/><path d="M19 12a7 7 0 0 0-.1-1l2-1.5-2-3.5-2.3.9a8 8 0 0 0-1.7-1l-.4-2.4h-4l-.4 2.4a8 8 0 0 0-1.7 1l-2.3-.9-2 3.5 2 1.5a9 9 0 0 0 0 2l-2 1.5 2 3.5 2.3-.9a8 8 0 0 0 1.7 1l.4 2.4h4l.4-2.4a8 8 0 0 0 1.7-1l2.3.9 2-3.5-2-1.5a7 7 0 0 0 .1-1Z"/>',
      check:'<path d="m5 12 4 4L19 6"/>',
    };
    return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[type]||paths.calendar}</svg>`;
  };

  const SWATCHES = [
    {bg:'#e6eeff', bd:'#a9c1f3', fg:'#1f4fb5'},
    {bg:'#def3ed', bd:'#94d1c0', fg:'#0f6655'},
    {bg:'#f0e8fa', bd:'#c9ade7', fg:'#5f3590'},
    {bg:'#ffeedb', bd:'#efc285', fg:'#7f4f0b'},
    {bg:'#ffe8f0', bd:'#eeabc3', fg:'#8f3352'},
  ];
  function swatch(title) {
    let hash = 0;
    for (const c of title || '') hash = ((hash * 31) + c.charCodeAt(0)) | 0;
    return SWATCHES[Math.abs(hash) % SWATCHES.length];
  }
  const swatchVars = sw => `--bg:${sw.bg};--bd:${sw.bd};--fg:${sw.fg}`;

  // "10:00 – 11:00 AM", or "11:30 AM – 12:00 PM" when the meridiem changes.
  function timeRange(start, end) {
    const a = fmtTime(new Date(start)), b = fmtTime(new Date(end));
    const suffix = /\s?[AP]M$/, sa = a.match(suffix), sb = b.match(suffix);
    return (sa && sb && sa[0].trim() === sb[0].trim() ? a.replace(suffix, '') : a) + ' – ' + b;
  }

  const state = {
    paired:false, demo:false, hasFeed:false, pairingUrl:'', qr:'', events:[], error:'', loading:false,
    lastSynced:0, timeZone:'', day:today(), view:'day', settings:false, confirmRemove:false,
  };

  // Sample week relative to today, shown in preview mode and when opened in a browser.
  const SAMPLE = [
    [0,'7:30','8:00','Gym'], [0,'9:00','9:30','Morning planning'],
    [0,'10:00','11:00','Design review','Conference room B'], [0,'10:30','11:30','Vendor call','Zoom'],
    [0,'12:30','13:30','Lunch with team','Cafeteria'], [0,'14:00','15:30','Focus time'],
    [0,'16:00','16:30','Project check-in','Room 4'],
    [1,'9:00','9:15','Standup'], [1,'10:00','12:00','Design workshop'], [1,'13:00','14:00','Lunch & learn'],
    [1,'17:30','18:30','Yoga'], [2,'9:00','9:15','Standup'], [2,'11:00','12:00','Client demo','Zoom'],
    [2,'14:00','16:00','Focus time'], [3,'9:30','10:30','Dentist'], [3,'13:00','14:30','Book club'],
    [-1,'10:00','11:00','Coffee with Priya'], [-1,'15:00','16:00','Haircut'], [-2,'10:00','12:00','Farmers market'],
  ];
  function sampleEvents() {
    const base = today();
    const at = (offset, hm) => {
      const [h, m] = hm.split(':').map(Number), d = noon(addDays(base, offset));
      d.setHours(h, m, 0, 0);
      return d.getTime();
    };
    const list = SAMPLE.map(([offset, from, to, title, location = ''], i) =>
      ({id:'sample:' + i, title, location, allDay:false, start:at(offset, from), end:at(offset, to)}));
    list.push({id:'sample:all-day', title:'Quarterly planning', location:'', allDay:true,
               start:startOf(base), end:startOf(addDays(base, 3))});
    return list;
  }

  let events = []; // Set at the start of each render.

  const weekStart = day => addDays(day, -((noon(day).getDay() + 6) % 7));
  const weekDays = day => { const s = weekStart(day); return [0, 1, 2, 3, 4, 5, 6].map(i => addDays(s, i)); };
  const overlapping = (from, to) => events.filter(e => e.start < to && e.end > from);
  const allDayOn = day => overlapping(startOf(day), startOf(addDays(day, 1))).filter(e => e.allDay);
  // Timed events on one day, clipped to that day, in display order.
  function timedOn(day) {
    const from = startOf(day), to = startOf(addDays(day, 1));
    return overlapping(from, to).filter(e => !e.allDay)
      .map(e => ({e, from, start:Math.max(e.start, from), end:Math.min(e.end, to)}))
      .sort((a, b) => a.start - b.start || b.end - a.end);
  }

  // Hours to show: just enough for the events, at least MIN_HOURS (8 AM – 6 PM when
  // there are none). The range is stretched to the screen, so nothing scrolls.
  function hourRange(items) {
    let lo = Infinity, hi = -Infinity;
    for (const it of items) {
      lo = Math.min(lo, (it.start - it.from) / HOUR_MS);
      hi = Math.max(hi, (it.end - it.from) / HOUR_MS);
    }
    if (lo === Infinity) { lo = 8; hi = 18; }
    let start = Math.max(0, Math.floor(lo)), end = Math.min(24, Math.ceil(hi));
    while (end - start < MIN_HOURS) {
      if (end < 24) end++;
      if (end - start < MIN_HOURS && start > 0) start--;
    }
    return {start, end};
  }

  // Overlapping events share their group's width in side-by-side lanes.
  function assignLanes(items) {
    let group = [], groupEnd = 0;
    const flush = () => {
      const ends = [];
      let total = 1;
      for (const it of group) {
        let lane = ends.findIndex(v => v <= it.start);
        if (lane < 0) lane = ends.length;
        ends[lane] = it.end; it.lane = lane; total = Math.max(total, lane + 1);
      }
      for (const it of group) it.total = total;
    };
    for (const it of items) {
      if (group.length && it.start >= groupEnd) { flush(); group = []; groupEnd = 0; }
      group.push(it); groupEnd = Math.max(groupEnd, it.end);
    }
    flush();
    return items;
  }

  function relDay(day) {
    const diff = Math.round((startOf(day) - startOf(today())) / DAY_MS);
    if (diff === 0) return 'Today';
    if (diff === 1) return 'Tomorrow';
    if (diff === -1) return 'Yesterday';
    return diff > 0 ? `In ${diff} days` : `${-diff} days ago`;
  }
  function relWeek(day) {
    const diff = Math.round((startOf(weekStart(day)) - startOf(weekStart(today()))) / (7 * DAY_MS));
    if (diff === 0) return 'This week';
    if (diff === 1) return 'Next week';
    if (diff === -1) return 'Last week';
    return diff > 0 ? `In ${diff} weeks` : `${-diff} weeks ago`;
  }
  function weekTitle(days, long) {
    const a = noon(days[0]), b = noon(days[6]), month = long ? 'long' : 'short';
    const right = a.getMonth() === b.getMonth() ? String(b.getDate()) : fmt({month, day:'numeric'}, b);
    return fmt({month, day:'numeric'}, a) + ' – ' + right + (long ? ', ' + b.getFullYear() : '');
  }
  function untilText(ms) {
    const mins = Math.round((ms - Date.now()) / 60000);
    if (mins < 60) return `in ${Math.max(mins, 1)} min`;
    if (dayOf(ms) === today()) return `in ${Math.floor(mins / 60)} h${mins % 60 ? ` ${mins % 60} min` : ''}`;
    if (dayOf(ms) === addDays(today(), 1)) return `tomorrow at ${fmtTime(new Date(ms))}`;
    return `${fmt({weekday:'short', month:'short', day:'numeric'}, new Date(ms))} at ${fmtTime(new Date(ms))}`;
  }
  const summary = (count, allDay) => count ? plural(count, 'event') + (allDay ? ` · ${allDay} all-day` : '') : 'No events';

  function topBar() {
    const week = state.view === 'week', unit = week ? 'week' : 'day';
    const [statusClass, statusText] = state.error ? ['error', 'Sync issue · ' + state.error]
      : state.loading ? ['busy', 'Syncing…']
      : state.demo ? ['', 'Sample calendar']
      : state.paired ? ['', state.lastSynced ? 'Synced ' + fmtTime(new Date(state.lastSynced)) : 'Connected']
      : ['', 'Not connected'];
    return `<header class="bar">
      <div class="brand-block"><div class="logo">${icon('calendar')}</div><div class="brand-text"><div class="appname">Open Cal</div><div class="sync ${statusClass}"><span class="dot"></span><span class="sync-text">${esc(statusText)}</span></div></div></div>
      <div class="bar-spacer"></div>
      <div class="seg" role="group" aria-label="View"><button class="${week ? '' : 'on'}" aria-pressed="${!week}" onclick="OpenCalUI.setView('day')">Day</button><button class="${week ? 'on' : ''}" aria-pressed="${week}" onclick="OpenCalUI.setView('week')">Week</button></div>
      <button class="pill" onclick="OpenCalUI.today()">Today</button>
      <button class="iconbtn" onclick="OpenCalUI.navigate(-1)" aria-label="Previous ${unit}">${icon('chevronLeft')}</button>
      <button class="iconbtn" onclick="OpenCalUI.navigate(1)" aria-label="Next ${unit}">${icon('chevronRight')}</button>
      <button class="iconbtn" onclick="OpenCalUI.refresh()" aria-label="Refresh calendar">${icon('refresh')}</button>
      <button class="iconbtn" onclick="OpenCalUI.toggleSettings()" aria-label="Settings">${icon('settings')}</button>
      <div class="clock"><div id="liveClock"></div><div id="liveDate"></div></div>
    </header>`;
  }

  function hero() {
    if (state.view === 'week') {
      const days = weekDays(state.day);
      const list = overlapping(startOf(days[0]), startOf(addDays(days[6], 1)));
      return `<section class="panel hero"><div class="kicker">${esc(relWeek(state.day))}</div><div class="hero-title week">${esc(weekTitle(days, false))}</div><div class="hero-stats">${esc(summary(list.length, list.filter(e => e.allDay).length))}</div></section>`;
    }
    const date = noon(state.day), allDay = allDayOn(state.day).length;
    return `<section class="panel hero"><div class="kicker">${esc(relDay(state.day))}</div><div class="hero-title">${esc(fmt({weekday:'long'}, date))}</div><div class="hero-sub">${esc(fmt({month:'long', day:'numeric'}, date))}</div><div class="hero-stats">${esc(summary(timedOn(state.day).length + allDay, allDay))}</div></section>`;
  }

  function miniMonth() {
    const d = noon(state.day), first = new Date(d.getFullYear(), d.getMonth(), 1);
    const start = new Date(first); start.setDate(1 - (first.getDay() + 6) % 7);
    const now = today(), week = new Set(state.view === 'week' ? weekDays(state.day) : []);
    const occupied = new Set();
    for (const e of events) {
      const last = dayOf(Math.max(e.start, e.end - 1));
      for (let key = dayOf(e.start), i = 0; key <= last && i < 62; key = addDays(key, 1), i++) occupied.add(key);
    }
    let cells = ['M','T','W','T','F','S','S'].map(l => `<span class="mini-week">${l}</span>`).join('');
    for (let i = 0; i < 42; i++) {
      const day = new Date(start); day.setDate(start.getDate() + i);
      const key = ymd(day);
      const cls = ['mini-day', day.getMonth() !== d.getMonth() && 'outside', occupied.has(key) && 'has',
                   week.has(key) && 'inweek', key === now && 'now', key === state.day && 'current'].filter(Boolean).join(' ');
      cells += `<button class="${cls}" onclick="OpenCalUI.pick('${key}')" aria-label="${esc(longDate(day))}">${day.getDate()}</button>`;
    }
    return `<section class="panel mini"><div class="mini-title">${esc(fmt({month:'long', year:'numeric'}, d))}</div><div class="mini-grid">${cells}</div></section>`;
  }

  function upNext() {
    const now = Date.now();
    const next = events.filter(e => !e.allDay && e.start > now).sort((a, b) => a.start - b.start)[0];
    const body = next
      ? `<div class="upnext-title">${esc(next.title)}</div><div class="upnext-time">${esc(timeRange(next.start, next.end) + (next.location ? ' · ' + next.location : ''))}</div><div class="upnext-when">${esc(untilText(next.start))}</div>`
      : '<div class="upnext-empty">Nothing else on the calendar.</div>';
    return `<section class="panel upnext"><div class="upnext-label">Coming up</div>${body}</section>`;
  }

  function hourLines(range) {
    let out = '';
    for (let h = range.start; h <= range.end; h++)
      out += `<div class="hour" style="top:${(h - range.start) / (range.end - range.start) * 100}%"><span>${esc(hourLabel(h))}</span></div>`;
    return out;
  }
  // The current-time line, or nothing when the current time is outside the shown hours.
  function nowLine(range, position) {
    const n = new Date();
    const top = (n.getHours() + n.getMinutes() / 60 - range.start) / (range.end - range.start) * 100;
    return top >= 0 && top <= 100 ? `<div class="nowline" style="top:${top}%;${position}"></div>` : '';
  }
  function eventBlock(it, range, left, width, meta) {
    const span = range.end - range.start, now = Date.now();
    const top = ((it.start - it.from) / HOUR_MS - range.start) / span * 100;
    const height = (it.end - it.start) / HOUR_MS / span * 100;
    const cls = 'event' + (it.e.start <= now && it.e.end > now ? ' live' : '') + (it.e.end <= now ? ' past' : '');
    return `<div class="${cls}" style="top:calc(${top}% + .125rem);height:calc(${height}% - .25rem);left:${left};width:${width};${swatchVars(swatch(it.e.title))}"><div class="event-title">${esc(it.e.title)}</div><div class="event-time">${esc(meta)}</div></div>`;
  }
  const emptyState = title => `<div class="empty"><div class="empty-icon">${icon('check')}</div><div class="empty-title">${esc(title)}</div></div>`;
  const loadingDot = () => state.loading ? '<span class="loading-indicator"></span>' : '';

  function dayPanel() {
    const isToday = state.day === today();
    const timed = assignLanes(timedOn(state.day)), allDay = allDayOn(state.day);
    const range = hourRange(timed), count = timed.length + allDay.length;
    const sub = count ? `${plural(count, 'event')} · ${hourLabel(range.start)} – ${hourLabel(range.end)}` : 'No events';
    let track = hourLines(range);
    for (const it of timed) {
      const meta = timeRange(it.e.start, it.e.end) + (it.e.location ? ' · ' + it.e.location : '');
      track += eventBlock(it, range, `calc(${it.lane / it.total * 100}% + .25rem)`, `calc(${100 / it.total}% - .5rem)`, meta);
    }
    if (isToday) track += nowLine(range, 'left:-.75rem;right:0');
    if (!timed.length) track += emptyState(allDay.length ? 'No timed events' : 'Nothing scheduled');
    return `<div class="cal-head"><div><div class="cal-title">${esc(longDate(noon(state.day)))}</div><div class="cal-sub">${esc(sub)}${loadingDot()}</div></div>${isToday ? '<span class="tag">Today</span>' : ''}</div>
      ${allDay.length ? `<div class="allday">${allDay.map(e => `<span class="chip" style="${swatchVars(swatch(e.title))}">All day · ${esc(e.title)}</span>`).join('')}</div>` : ''}
      <div class="timeline"><div class="track">${track}</div></div>`;
  }

  function weekPanel() {
    const days = weekDays(state.day), now = today(), todayIndex = days.indexOf(now);
    const columns = days.map(d => assignLanes(timedOn(d)));
    const range = hourRange([].concat(...columns));
    const inWeek = overlapping(startOf(days[0]), startOf(addDays(days[6], 1)));
    const sub = inWeek.length ? `${plural(inWeek.length, 'event')} · ${hourLabel(range.start)} – ${hourLabel(range.end)}` : 'No events';
    let head = '<div></div>';
    days.forEach(d => {
      const date = noon(d), cls = ['wd', d === now && 'today', d === state.day && 'selected'].filter(Boolean).join(' ');
      head += `<button class="${cls}" onclick="OpenCalUI.openDay('${d}')" aria-label="${esc(longDate(date))}"><span class="dow">${esc(fmt({weekday:'short'}, date))}</span><span class="dnum">${date.getDate()}</span></button>`;
    });
    let chips = '';
    for (const e of inWeek.filter(e => e.allDay)) {
      const cols = days.map((d, i) => startOf(d) < e.end && startOf(addDays(d, 1)) > e.start ? i : -1).filter(i => i >= 0);
      chips += `<span class="chip" style="grid-column:${cols[0] + 2} / span ${cols.length};${swatchVars(swatch(e.title))}">${esc(e.title)}</span>`;
    }
    let track = todayIndex >= 0 ? `<div class="col-today" style="left:${todayIndex / 7 * 100}%;width:${100 / 7}%"></div>` : '';
    track += hourLines(range);
    for (let i = 1; i < 7; i++) track += `<div class="col-line" style="left:${i / 7 * 100}%"></div>`;
    columns.forEach((items, i) => items.forEach(it => {
      track += eventBlock(it, range, `calc(${(i + it.lane / it.total) / 7 * 100}% + .1875rem)`,
                          `calc(${100 / (7 * it.total)}% - .375rem)`, timeRange(it.e.start, it.e.end));
    }));
    if (todayIndex >= 0) track += nowLine(range, `left:${todayIndex / 7 * 100}%;width:${100 / 7}%`);
    if (!inWeek.length) track += emptyState('Nothing scheduled this week');
    return `<div class="cal-head"><div><div class="cal-title">${esc(weekTitle(days, true))}</div><div class="cal-sub">${esc(sub)}${loadingDot()}</div></div>${todayIndex >= 0 ? '<span class="tag">This week</span>' : ''}</div>
      <div class="week-head">${head}</div>
      ${chips ? `<div class="week-allday"><div class="label">All day</div>${chips}</div>` : ''}
      <div class="timeline week"><div class="track">${track}</div></div>`;
  }

  function settingsMarkup() {
    const last = state.lastSynced ? new Date(state.lastSynced).toLocaleString() : 'Not synced yet';
    return `<div class="settings-backdrop"><section class="settings" aria-label="Calendar settings">
      <button class="close" onclick="OpenCalUI.toggleSettings()" aria-label="Close">×</button>
      <h2>Calendar settings</h2><p>Calendar data is stored encrypted on this TV. The only network request is to Google for your calendar feed.</p>
      <p><strong>Last sync:</strong> ${esc(last)}<br/><strong>Time zone:</strong> ${esc(state.timeZone || 'Device time zone')}<br/><strong>Sync:</strong> Every 15 minutes while the app is open</p>
      ${state.error ? `<div class="notice">${esc(state.error)}</div>` : ''}
      <div class="settings-list">
        <button class="action primary" onclick="OpenCalUI.refresh()">Refresh now</button>
        <button class="action" onclick="OpenCalUI.repair()">Connect another calendar</button>
        ${state.demo ? '<button class="action" onclick="OpenCalUI.leaveDemo()">Exit sample calendar</button>' : ''}
        <button class="action danger" onclick="OpenCalUI.remove()">${state.confirmRemove ? 'Confirm remove' : 'Remove calendar'}</button>
      </div>
      <p class="settings-tip">Menu opens settings. Rewind and fast-forward move back and forward. Back returns to today.</p>
    </section></div>`;
  }

  function pairingMarkup() {
    const address = state.pairingUrl || '';
    return `<section class="pair-screen">
      <header class="pair-top"><div class="brand"><div class="brandmark">${icon('calendar')}</div><div><div class="brandname">Open Cal</div></div></div></header>
      <div class="pair-layout">
        <div class="pair-copy"><div class="eyebrow">Connect your calendar</div><h1 class="pair-headline">Your day.<br/><em>On the big screen.</em></h1>
          <p class="pair-lead">Connect a private Google Calendar feed once. Your schedule will update automatically.</p>
          <div class="pair-steps"><div class="pair-step"><span class="stepnum">1</span><span>Open <b>Google Calendar settings</b> on a computer.</span></div>
          <div class="pair-step"><span class="stepnum">2</span><span>Select your calendar → <b>Integrate calendar</b> → copy your <b>Secret address in iCal format</b>.</span></div>
          <div class="pair-step"><span class="stepnum">3</span><span>Scan the QR code with your phone, paste the secret address, and connect. Your TV will load your events.</span></div></div>
          <div class="pair-actions"><button class="pair-action primary" onclick="OpenCalUI.preview()">Preview the calendar</button><button class="pair-action" onclick="OpenCalUI.newPairing()">New pairing code</button>${state.hasFeed ? '<button class="pair-action" onclick="OpenCalUI.cancelPairing()">Back to calendar</button>' : ''}</div>
          ${state.error ? `<p class="notice" style="margin-top:18px">${esc(state.error)}</p>` : ''}
        </div>
        <div class="qr-card">
          <div class="qr-title">Pair your Google Calendar</div>
          ${state.qr ? `<img src="${state.qr}" alt="Scan to open pairing page on phone">` : '<div class="qr-fallback">QR pairing isn’t available right now.<br/>Select New pairing code, or enter the address below.</div>'}
          <div class="qr-sub">Scan with your phone’s camera<br/>Both devices must use the same Wi-Fi.</div>
          ${address ? `<div class="pair-url">${esc(address.replace(/t=.*/, 't=••••'))}</div>` : ''}
          <div class="manual"><input id="manualFeed" type="url" placeholder="Or paste your private iCal URL with the remote" spellcheck="false" autocomplete="off"/><button onclick="OpenCalUI.manualSave()">Connect using TV remote</button></div>
        </div>
      </div>
    </section>`;
  }

  function mainLayout() {
    const week = state.view === 'week';
    return `<div class="app">${topBar()}
      <div class="main"><aside class="side">${hero()}${miniMonth()}${upNext()}</aside>
      <section class="panel cal" aria-label="${week ? 'Week' : 'Day'} calendar">${week ? weekPanel() : dayPanel()}</section></div>
      ${state.settings ? settingsMarkup() : ''}</div>`;
  }

  function headerClock() {
    const clock = $('liveClock'), date = $('liveDate');
    if (clock) clock.textContent = fmtTime(new Date());
    if (date) date.textContent = fmt({weekday:'short', month:'short', day:'numeric'}, new Date());
  }

  // Event text adapts to the height each block actually got on this screen.
  function fitEvents() {
    const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
    for (const el of document.querySelectorAll('.event')) {
      const h = el.offsetHeight;
      el.classList.toggle('compact', h < 3.6 * rem);
      el.classList.toggle('tiny', h < 2.1 * rem);
    }
  }

  function render() {
    events = state.demo ? sampleEvents() : state.events;
    // innerHTML replaces every node, so carry over focus and typed input.
    const active = document.activeElement;
    const focusKey = active && active !== document.body ? (active.id || active.getAttribute('onclick')) : '';
    const typed = $('manualFeed') ? $('manualFeed').value : '';
    $('root').innerHTML = (!state.paired && !state.demo) ? pairingMarkup() : mainLayout();
    headerClock();
    fitEvents();
    const input = $('manualFeed'); if (input && typed) input.value = typed;
    if (focusKey) {
      const el = [...$('root').querySelectorAll('[id],[onclick]')].find(e => e.id === focusKey || e.getAttribute('onclick') === focusKey);
      if (el) el.focus();
    }
  }

  window.OpenCalUI = {
    receive(data) { Object.assign(state, data); render(); },
    pick(date) { state.day = date; render(); },
    openDay(date) { state.day = date; state.view = 'day'; render(); },
    setView(view) { state.view = view; render(); },
    navigate(offset) { state.day = addDays(state.day, offset * (state.view === 'week' ? 7 : 1)); render(); },
    today() { state.day = today(); render(); },
    refresh() { state.settings = false; render(); if (window.OpenCalNative) OpenCalNative.refresh(); },
    toggleSettings() { state.settings = !state.settings; state.confirmRemove = false; render(); },
    handleBack() {
      if (state.settings) { state.settings = false; render(); }
      else if (!state.paired && !state.demo && state.hasFeed) OpenCalUI.cancelPairing();
      else { state.day = today(); state.view = 'day'; render(); }
    },
    cancelPairing() { if (window.OpenCalNative) OpenCalNative.cancelPairing(); },
    repair() { state.settings = false; state.paired = false; state.demo = false; render(); if (window.OpenCalNative) OpenCalNative.pair(); },
    remove() {
      if (!state.confirmRemove) { state.confirmRemove = true; render(); return; }
      state.confirmRemove = false; state.settings = false;
      if (window.OpenCalNative) OpenCalNative.disconnect();
    },
    preview() { if (window.OpenCalNative) OpenCalNative.preview(); else { state.demo = true; render(); } },
    leaveDemo() { if (window.OpenCalNative) OpenCalNative.exitPreview(); else { state.demo = false; state.settings = false; render(); } },
    newPairing() { if (window.OpenCalNative) OpenCalNative.pair(); },
    manualSave() { const el = $('manualFeed'); if (!el || !el.value.trim()) return; if (window.OpenCalNative) OpenCalNative.save(el.value.trim()); },
  };

  if (!window.OpenCalNative) {
    // Opened directly in a browser: show the sample calendar (?view=week for the week view),
    // or the pairing screen with ?pair=1.
    state.demo = !location.search.includes('pair');
    if (location.search.includes('view=week')) state.view = 'week';
    render();
  } else {
    render();
    OpenCalNative.ready();
  }

  // Re-render each minute so the now line and "Coming up" stay current,
  // and follow the date at midnight if the viewer was looking at today.
  let currentDay = today();
  setInterval(() => {
    const now = today();
    if (now !== currentDay) { if (state.day === currentDay) state.day = now; currentDay = now; }
    if (state.paired || state.demo) render();
  }, 60 * 1000);
  setInterval(headerClock, 15 * 1000);
  window.addEventListener('resize', () => { if (state.paired || state.demo) render(); });
})();
