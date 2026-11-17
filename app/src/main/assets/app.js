/* Room Calendar TV — no frontend framework, runtime CDN, service, or remote JS. */
(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const pad = v => String(v).padStart(2, '0');
  const ymd = d => `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`;
  const dayOf = ms => ymd(new Date(ms));
  const addDays = (day,n) => {let d=new Date(day+'T12:00:00');d.setDate(d.getDate()+n);return ymd(d)};
  const fmtTime = d => new Intl.DateTimeFormat('en-US', {hour:'numeric',minute:'2-digit'}).format(d);
  const fmtHour = d => new Intl.DateTimeFormat('en-US', {hour:'numeric'}).format(d);
  const today = () => ymd(new Date());
  const icon = type => {
    const paths = {
      calendar:'<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 3v4M17 3v4M3 10h18M8 14h2M14 14h2M8 18h2"/>',
      chevronLeft:'<path d="m15 18-6-6 6-6"/>', chevronRight:'<path d="m9 18 6-6-6-6"/>',
      refresh:'<path d="M20 11a8 8 0 0 0-15-3M4 4v4h4M4 13a8 8 0 0 0 15 3m1 4v-4h-4"/>',
      settings:'<path d="M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z"/><path d="M19 12a7 7 0 0 0-.1-1l2-1.5-2-3.5-2.3.9a8 8 0 0 0-1.7-1l-.4-2.4h-4l-.4 2.4a8 8 0 0 0-1.7 1l-2.3-.9-2 3.5 2 1.5a9 9 0 0 0 0 2l-2 1.5 2 3.5 2.3-.9a8 8 0 0 0 1.7 1l.4 2.4h4l.4-2.4a8 8 0 0 0 1.7-1l2.3.9 2-3.5-2-1.5a7 7 0 0 0 .1-1Z"/>',
      check:'<path d="m5 12 4 4L19 6"/>',
    };
    return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[type]||paths.calendar}</svg>`;
  };
  const state = {
    paired:false, demo:false, pairingUrl:'', qr:'', events:[], error:'', loading:false,lastSynced:0,
    day:today(), settings:false, confirmRemove:false, timeZone:'', manual:false,
  };
  function selectedDate(){return new Date(state.day+'T12:00:00')}
  function eventsForDay(day) {
    const from = new Date(day+'T00:00:00').getTime();
    const to = new Date(addDays(day,1)+'T00:00:00').getTime();
    return state.events.filter(e => e.start < to && e.end > from).sort((a,b)=>a.start-b.start);
  }
  function headerClock(){
    const clock=$('liveClock'), date=$('liveDate');
    if(clock) clock.textContent=new Intl.DateTimeFormat('en-US',{hour:'numeric',minute:'2-digit'}).format(new Date());
    if(date) date.textContent=new Intl.DateTimeFormat('en-US',{weekday:'short',month:'short',day:'numeric'}).format(new Date());
  }
  function miniMonth() {
    let d = selectedDate(), first=new Date(d.getFullYear(),d.getMonth(),1);
    let mondayOffset=(first.getDay()+6)%7;
    let start=new Date(first);start.setDate(1-mondayOffset);
    let cells=['M','T','W','T','F','S','S'].map(day=>`<span class="mini-week">${day}</span>`).join('');
    const occupied = new Set(state.events.map(e=>dayOf(e.start)));
    for(let i=0;i<42;i++) {
      const day=new Date(start);day.setDate(start.getDate()+i);
      const key=ymd(day), dim=day.getMonth()!==d.getMonth(), now=key===today();
      cells+=`<button class="mini-day ${dim?'outside':''} ${key===state.day?'current':''} ${now?'now':''} ${occupied.has(key)?'has':''}" onclick="RoomUI.pick('${key}')" title="${esc(key)}">${day.getDate()}</button>`;
    }
    const title=new Intl.DateTimeFormat('en-US',{month:'long',year:'numeric'}).format(d);
    return `<section class="panel mini"><div class="mini-title"><span>${esc(title)}</span><span style="color:#a7b3c1;font-weight:500;font-size:13px">${state.timeZone?'Local time':''}</span></div><div class="mini-grid">${cells}</div></section>`;
  }
  function mainLayout() {
    let day = selectedDate(), isToday=state.day===today(), list=eventsForDay(state.day);
    let allDay=list.filter(e=>e.allDay),timed=list.filter(e=>!e.allDay);
    const dayFull=new Intl.DateTimeFormat('en-US',{weekday:'long',month:'long',day:'numeric'}).format(day);
    const next=state.events.filter(e=>e.end>Date.now()).sort((a,b)=>a.start-b.start)[0];
    const nextTime = next ? `${fmtTime(new Date(next.start))} · ${new Intl.DateTimeFormat('en-US',{weekday:'short',month:'short',day:'numeric'}).format(new Date(next.start))}`:'';
    const upNext=next?`<div class="upcoming-title">${esc(next.title)}</div><div class="upcoming-time">${esc(nextTime)}</div>`:'<div class="upcoming-empty">You’re all clear for now.<br/>Enjoy the breathing room.</div>';
    const statusColor=state.error?'error':state.loading?'refreshing':'';
    const status=state.error?'Calendar sync issue':state.loading?'Syncing events':state.demo?'Demo calendar':state.paired?'Google Calendar connected':'Calendar not connected';
    const weekday=new Intl.DateTimeFormat('en-US',{weekday:'long'}).format(day);
    return `<div class="app">
      <header class="topbar">
        <div class="brand"><div class="brandmark">${icon('calendar')}</div><div><div class="brandname">Room Calendar</div><div class="brandtag">Your day, beautifully simple</div></div></div>
        <div class="nav">
          <button class="nav-pill" onclick="RoomUI.today()" title="Jump to today">Today</button>
          <button class="iconbutton" onclick="RoomUI.navigate(-1)" title="Previous day">${icon('chevronLeft')}</button>
          <button class="iconbutton" onclick="RoomUI.navigate(1)" title="Next day">${icon('chevronRight')}</button>
          <button class="iconbutton" onclick="RoomUI.refresh()" title="Refresh calendar">${icon('refresh')}</button>
          <button class="iconbutton" onclick="RoomUI.toggleSettings()" title="Settings (Menu button)">${icon('settings')}</button>
          <div class="clock"><div id="liveClock"></div><div id="liveDate"></div></div>
        </div>
      </header>
      <div class="main">
        <aside class="leftcol">
          <section class="panel hero"><div><div class="eyebrow">${isToday?'Your day at a glance':'Looking ahead'}</div><div class="hero-title">${esc(weekday)}<span style="color:#4779e4">.</span></div><div class="hero-sub">${isToday?'Make some space for what matters.':'Here’s what’s on your schedule.'}</div></div><div class="hero-stats"><span class="dot"></span>${list.length} ${list.length===1?'event':'events'} ${isToday?'today':'scheduled'}</div></section>
          ${miniMonth()}
          <section class="panel upcoming"><div class="smallheading">Coming up next</div>${upNext}</section>
        </aside>
        <section class="panel calendar" aria-label="Daily calendar">
          <div class="calendar-head"><div><div class="calendar-h-title">${esc(dayFull)}</div><div class="calendar-h-sub">Your daily schedule ${state.loading?'<span class="loading-indicator"></span>':''}</div></div>${isToday?'<span class="tag">Today</span>':''}</div>
          ${allDay.length?`<div class="all-day">${allDay.map(e=>`<span>${esc(e.title)}</span>`).join('')}</div>`:''}
          <div class="calendar-body"><div id="timeline" class="timeline">${timelineMarkup(timed,state.day)}</div></div>
        </section>
      </div>
      <footer class="footer"><div class="status ${statusColor}"><span class="dot"></span>${esc(status)}${state.error?` · ${esc(state.error)}`:''}</div><div>Read-only · Private on your device · Use remote to navigate</div></footer>
      ${state.settings?settingsMarkup():''}
    </div>`;
  }
  function timelineMarkup(events,dayKey) {
    let startHour=7,endHour=22;
    for (let e of events) {
      const st=new Date(Math.max(e.start,new Date(dayKey+'T00:00:00').getTime()));
      const endMs=Math.min(e.end,new Date(addDays(dayKey,1)+'T00:00:00').getTime());
      const afterMidnight=endMs===new Date(addDays(dayKey,1)+'T00:00:00').getTime();
      const en=new Date(endMs);
      startHour=Math.min(startHour,st.getHours());
      endHour=Math.max(endHour,afterMidnight?24:(en.getHours() + (en.getMinutes()>0?1:0)));
    }
    startHour=Math.max(0,startHour);endHour=Math.min(24,Math.max(endHour,startHour+5));
    const px=58, height=(endHour-startHour)*px;
    let markup='';
    for(let hour=startHour;hour<=endHour;hour++) {
      let h=new Date(dayKey+'T00:00:00');h.setHours(hour,0,0,0);
      markup+=`<div class="hour" style="top:${(hour-startHour)*px}px"><span class="hourlabel">${esc(fmtHour(h))}</span><div class="hourrule"></div></div>`;
    }
    // Overlap groups, so simultaneous meetings remain legible.
    const sorted=events.slice().sort((a,b)=>a.start-b.start||b.end-a.end);
    const layouts=new Map();
    let group=[],groupEnd=0;
    function flush() {
      if(!group.length) return;
      let ends=[],max=1;
      for(let e of group){let lane=ends.findIndex(v=>v<=e.start);if(lane<0) lane=ends.length;ends[lane]=e.end;layouts.set(e,{lane});max=Math.max(max,lane+1)}
      for(let e of group) layouts.get(e).total=max;
    }
    for(let e of sorted) {
      if(group.length && e.start>=groupEnd){flush();group=[];groupEnd=0}
      group.push(e);groupEnd=Math.max(groupEnd,e.end);
    }
    flush();
    const swatches=[['#3d7ceb','#eaf1ff','#315fb4'],['#23a38e','#e6f6f2','#147764'],['#aa72d8','#f4ecfa','#8053a5'],['#e9a54b','#fff3e4','#aa731e'],['#e27c9d','#fff0f5','#a7506a']];
    for (let e of events) {
      const dayStart=new Date(dayKey+'T00:00:00').getTime(), dayEnd=new Date(addDays(dayKey,1)+'T00:00:00').getTime();
      let start=new Date(Math.max(e.start,dayStart)),end=new Date(Math.min(e.end,dayEnd));
      const top=((start.getHours()+start.getMinutes()/60)-startHour)*px+2;
      const duration=Math.max(20,(end.getTime()-start.getTime())/3600000*px-4);
      let hash=0;for(const c of e.title||'') hash=((hash*31)+c.charCodeAt(0))|0;
      const [border,bg,fg]=swatches[Math.abs(hash)%swatches.length];
      const l=layouts.get(e)||{lane:0,total:1},gap=5;
      const left=`calc(94px + ${l.lane*100/l.total}% - ${l.lane*105/l.total}px)`;
      const width=`calc(${100/l.total}% - ${105/l.total+gap}px)`;
      markup+=`<div class="event ${duration<54?'compact':''}" style="top:${top}px;height:${Math.max(duration,25)}px;left:${left};width:${width};--color:${border};--event-bg:${bg};--event-color:${fg}" title="${esc(e.title)}"><div class="event-title">${esc(e.title)}</div><div class="event-time">${fmtTime(new Date(e.start))} – ${fmtTime(new Date(e.end))}${e.location?' · '+esc(e.location):''}</div></div>`;
    }
    if (dayKey===today()) {
      let n=new Date(), minutes=(n.getHours()+n.getMinutes()/60-startHour)*px;
      if (minutes>=0 && minutes<=height) markup+=`<div class="nowline" style="top:${minutes}px"></div>`;
    }
    if (events.length===0) markup+=`<div class="empty"><div class="empty-icon">${icon('check')}</div><div class="empty-title">A beautifully open day</div><div class="empty-sub">No events on your schedule.</div></div>`;
    return `<div style="height:${height}px">${markup}</div>`;
  }
  function settingsMarkup() {
    const last=state.lastSynced?new Date(state.lastSynced).toLocaleString():'Not synced yet';
    return `<div class="settings-backdrop"><section class="settings" aria-label="Calendar settings">
      <button class="close" onclick="RoomUI.toggleSettings()" aria-label="Close">×</button>
      <h2>Calendar settings</h2><p>All calendar data stays on this Android TV. Nothing is sent to a Room Calendar server.</p>
      <p><strong>Last sync:</strong> ${esc(last)}<br/><strong>Time zone:</strong> ${esc(state.timeZone||'Device time zone')}<br/><strong>Sync:</strong> Every 15 minutes while the app is open</p>
      ${state.error?`<div class="notice">${esc(state.error)}</div>`:''}
      <div class="settings-list">
        <button class="action primary" onclick="RoomUI.refresh()">Refresh now</button>
        <button class="action" onclick="RoomUI.repair()">Connect another calendar</button>
        ${state.demo?'<button class="action" onclick="RoomUI.leaveDemo()">Exit demo</button>':''}
        <button class="action danger" onclick="RoomUI.remove()">${state.confirmRemove?'Confirm remove':'Remove calendar'}</button>
      </div>
      <p style="font-size:12px;margin-bottom:0">Tip: Press Menu on your remote to open settings, or use the buttons at the top of the screen. TV power-off is controlled separately through Alexa.</p>
    </section></div>`;
  }
  function pairingMarkup() {
    const address=state.pairingUrl||'';
    return `<section class="pair-screen">
      <header class="pair-top"><div class="brand"><div class="brandmark">${icon('calendar')}</div><div><div class="brandname">Room Calendar</div><div class="brandtag">Your day, beautifully simple</div></div></div></header>
      <div class="pair-layout">
        <div class="pair-copy"><div class="eyebrow">Connect your calendar</div><h1 class="pair-headline">Your day.<br/><em>On the big screen.</em></h1>
          <p class="pair-lead">Connect a private Google Calendar feed once. Your schedule will update automatically.</p>
          <div class="pair-steps"><div class="pair-step"><span class="stepnum">1</span><span>Open <b>Google Calendar settings</b> on a computer.</span></div>
          <div class="pair-step"><span class="stepnum">2</span><span>Select your calendar → <b>Integrate calendar</b> → copy your <b>Secret address in iCal format</b>.</span></div>
          <div class="pair-step"><span class="stepnum">3</span><span>Scan the QR code with your phone, paste the secret address, and connect. Your TV will load your events.</span></div></div>
          <div class="pair-actions"><button class="pair-action primary" onclick="RoomUI.preview()">Preview the calendar</button><button class="pair-action" onclick="RoomUI.newPairing()">New pairing code</button></div>
          ${state.error?`<p class="notice" style="margin-top:18px">${esc(state.error)}</p>`:''}
        </div>
        <div class="qr-card">
          <div class="qr-title">Pair your Google Calendar</div>
          ${state.qr?`<img src="${state.qr}" alt="Scan to open pairing page on phone">`:'<div class="qr-fallback">No local network pairing is available.<br/>You can enter a calendar address using the TV remote below.</div>'}
          <div class="qr-sub">Scan with your phone’s camera<br/>Both devices must use the same Wi-Fi.</div>
          ${address?`<div class="pair-url">${esc(address.replace(/t=.*/, 't=••••'))}</div>`:''}
          <div class="manual"><input id="manualFeed" type="url" placeholder="Or paste your private iCal URL with the remote" spellcheck="false" autocomplete="off"/><button onclick="RoomUI.manualSave()">Connect using TV remote</button></div>
        </div>
      </div>

    </section>`;
  }
  function render() {
    $('root').innerHTML = (!state.paired&&!state.demo)?pairingMarkup():mainLayout();
    headerClock();
  }
  window.RoomUI = {
    receive(data) {
      Object.assign(state,data);
      // Keep user-selected day and modal state between background updates.
      render();
    },
    pick(date){state.day=date;render()},
    navigate(offset){state.day=addDays(state.day,offset);render()},
    today(){state.day=today();render()},
    refresh(){state.settings=false;render();if(window.RoomNative)RoomNative.refresh()},
    toggleSettings(){state.settings=!state.settings;state.confirmRemove=false;render()},
    handleBack(){if(state.settings){state.settings=false;render()}else{state.day=today();render()}},
    repair(){state.settings=false;state.paired=false;state.demo=false;render();if(window.RoomNative)RoomNative.pair()},
    remove(){
      if(!state.confirmRemove){state.confirmRemove=true;render();return}
      state.confirmRemove=false;state.settings=false;
      if(window.RoomNative)RoomNative.disconnect();
    },
    preview(){if(window.RoomNative)RoomNative.preview();else{state.demo=true;state.paired=false;state.events=fakeEvents();render()}},
    leaveDemo(){if(window.RoomNative)RoomNative.exitPreview();else{state.demo=false;render()}},
    newPairing(){if(window.RoomNative)RoomNative.pair()},
    manualSave(){const el=$('manualFeed');if(!el||!el.value.trim())return;if(window.RoomNative)RoomNative.save(el.value.trim())},
  };
  function fakeEvents(){
    const d=today(),blocks=[[9,0,9,30],[11,0,12,0],[14,0,15,30],[17,0,17,45]];
    const names=['Morning planning','Design review','Focus time','Project check-in'];
    return blocks.map((b,i)=>({id:'demo:'+i,title:names[i],start:new Date(`${d}T${pad(b[0])}:${pad(b[1])}:00`).getTime(),end:new Date(`${d}T${pad(b[2])}:${pad(b[3])}:00`).getTime(),allDay:false,location:''}));
  }
  if(!window.RoomNative){
    state.events=fakeEvents();state.demo=true;state.paired=false;
    if(location.search.includes('pair')){state.demo=false;state.events=[];}
    render();
  } else {
    render();
    RoomNative.ready();
  }
  setInterval(headerClock, 15*1000);
})();
