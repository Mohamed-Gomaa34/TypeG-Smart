/* Type-G Smart — واجهة التحكم (vanilla, offline). */
'use strict';

/* ---------- i18n ---------- */
let LANG = (localStorage.getItem('lang') || 'ar');
const AR = () => LANG === 'ar';
const t = (ar, en) => (AR() ? ar : en);
const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));

/* ---------- bridge (real or mock) ---------- */
const hasBridge = !!(window.TypeG && window.TypeG.call);
function api(action, body) {
  if (hasBridge) {
    try { const r = JSON.parse(window.TypeG.call(action, JSON.stringify(body || {}))); if (r && r.error) throw new Error(r.error); return r; }
    catch (e) { toast(errText(e.message)); return null; }
  }
  return MOCK(action, body);
}
function errText(code) {
  const m = {
    PROTECTED: t('المخرج محمي — شيل الحماية الأول', 'Outlet is protected — remove the lock first'),
    DEVICE_DISCONNECTED: t('المشترك مش متصل', 'Strip is not connected'),
    WIFI_REQUIRED: t('فعّل الواي فاي', 'Turn on Wi-Fi')
  };
  return m[code] || t('حصل خطأ', 'Something went wrong');
}

/* ---------- mock for preview without a device ---------- */
let _mock = null;
function MOCK(action, b) {
  if (!_mock) _mock = {
    running: true, haConfigured: false, lang: LANG, themeMode: 'system',
    tariff: { rate: 2.15 }, homeSSID: 'Home-WiFi', appVersion: '1.3.0-dev',
    devices: [{ mac: 'DEMO01', name: t('مشترك المكتب', 'Office strip'), online: true, outlets: [
      { channel: 1, name: t('الكمبيوتر', 'Computer'), room: '', state: 'on', watts: 142.5, wh: 480, cost: 1.03, protected: false },
      { channel: 2, name: t('الشاشة', 'Monitor'), room: '', state: 'on', watts: 28.0, wh: 95, cost: 0.2, protected: false },
      { channel: 3, name: t('الراوتر', 'Router'), room: '', state: 'on', watts: 9.3, wh: 30, cost: 0.06, protected: true },
      { channel: 4, name: t('الشاحن', 'Charger'), room: '', state: 'off', watts: 0, wh: 12, cost: 0.02, protected: false }
    ]}]
  };
  const dev = _mock.devices[0];
  const out = ch => dev.outlets.find(o => o.channel === ch);
  switch (action) {
    case 'state': _mock.lang = LANG; return JSON.parse(JSON.stringify(_mock));
    case 'control': { const o = out(b.channel); if (o) { o.state = b.state; o.watts = b.state === 'on' ? (10 + Math.random()*120) : 0; } return { ok: true }; }
    case 'groupControl': dev.outlets.forEach(o => { o.state = b.state; o.watts = b.state === 'on' ? 20 : 0; }); return { ok: true };
    case 'allOff': dev.outlets.forEach(o => { o.state = 'off'; o.watts = 0; }); return { ok: true };
    case 'saveMeta': { const o = out(b.channel); if (o) { if ('name' in b) o.name = b.name; if ('protected' in b) o.protected = !!b.protected; } return { ok: true }; }
    case 'saveTariff': _mock.tariff = { rate: parseFloat(b.rate) || 0 }; return { ok: true };
    case 'haStatus': return { configured: _mock.haConfigured, url: _mock.haUrl || '', connected: _mock.haConfigured };
    case 'haSave': _mock.haUrl = b.url; _mock.haConfigured = !!b.url; return { ok: true };
    case 'settings': if ('themeMode' in b) _mock.themeMode = b.themeMode; return { ok: true };
    case 'timer': { const o = out(b.channel); if (o) { if (b.seconds > 0) o.timer = { seconds: b.seconds, state: b.state }; else delete o.timer; } return { ok: true }; }
    case 'history': { const pts = []; for (let i=0;i<24;i++) pts.push({ w: 40+Math.random()*120 }); return { points: pts }; }
    case 'scanStrips': return { networks: ['TONLY_TAP_A1B2C3D'] };
    case 'apPassword': { const s=b.ssid||''; return { password: s.startsWith('TONLY_TAP_')?('LGU_'+s.slice(10)):'' }; }
    case 'homeIp': return { ip: '192.168.1.37' };
    case 'wifiGet': return { password: '' };
    case 'provision': {
      setTimeout(()=>window.onProvision&&window.onProvision('progress','CONNECTING_AP'),300);
      setTimeout(()=>window.onProvision&&window.onProvision('progress','SENDING_WIFI'),1200);
      setTimeout(()=>window.onProvision&&window.onProvision('ok','OK'),2200);
      return { started: true };
    }
    default: return { ok: true };
  }
}

/* ---------- app state ---------- */
let S = {}, tab = 'home';

/* ---------- helpers ---------- */
const $ = id => document.getElementById(id);
function toast(msg) { const el = $('toast'); el.textContent = msg; el.classList.add('show'); clearTimeout(toast._t); toast._t = setTimeout(() => el.classList.remove('show'), 2200); }
function devices() { return (S.devices || []); }
function stripDevices() { return devices().filter(d => d.mac !== 'HA'); }
function haDevice() { return devices().find(d => d.mac === 'HA'); }
function totalWatts() { return devices().flatMap(d => d.online ? (d.outlets||[]) : []).reduce((s,o)=>s+(+o.watts||0),0); }

/* ---------- strip illustration (physical order 1..4 + 2 USB) ---------- */
function stripSVG(dev) {
  const o = ch => (dev.outlets || []).find(x => x.channel === ch) || {};
  const W = 520, H = 190, pad = 28, n = 4;
  const gap = (W - pad*2) / n, r = 34;
  let sockets = '';
  for (let i = 0; i < n; i++) {
    const ch = i + 1, oo = o(ch), on = oo.state === 'on';
    const cx = pad + gap*i + gap/2, cy = 84;
    const ring = on ? 'var(--mint)' : 'var(--socket-off)';
    sockets += `
      <g class="sockhit" data-ch="${ch}">
        ${on ? `<circle cx="${cx}" cy="${cy}" r="${r+12}" fill="var(--glow)" opacity="0.5"/>` : ''}
        <circle cx="${cx}" cy="${cy}" r="${r}" fill="var(--socket)" stroke="${ring}" stroke-width="5"/>
        <circle cx="${cx-11}" cy="${cy-4}" r="4.3" fill="${on?'var(--mint)':'var(--faint)'}"/>
        <circle cx="${cx+11}" cy="${cy-4}" r="4.3" fill="${on?'var(--mint)':'var(--faint)'}"/>
        <rect x="${cx-4}" y="${cy+8}" width="8" height="12" rx="3" fill="${on?'var(--mint)':'var(--faint)'}"/>
        <text x="${cx}" y="${cy+r+26}" text-anchor="middle" font-size="19" font-weight="700" fill="var(--fg)">${ch}</text>
        ${oo.protected ? `<text x="${cx+r-6}" y="${cy-r+2}" text-anchor="middle" font-size="16">🔒</text>` : ''}
      </g>`;
  }
  // 2 USB جوه جسم المشترك (مش بيتحكم فيها)
  const usb = `
    <text x="462" y="140" text-anchor="middle" font-size="10" fill="var(--faint)">USB</text>
    <rect x="446" y="146" width="15" height="8" rx="2" fill="var(--faint)"/>
    <rect x="465" y="146" width="15" height="8" rx="2" fill="var(--faint)"/>`;
  return `<svg class="strip" viewBox="0 0 ${W} ${H}" role="img" aria-label="${t('شكل المشترك','Power strip')}">
    <rect x="8" y="34" width="${W-16}" height="120" rx="26" fill="var(--surface2)" stroke="var(--line)" stroke-width="2"/>
    <rect x="${W-12}" y="78" width="16" height="32" rx="5" fill="var(--line)"/>
    ${sockets}${usb}
  </svg>`;
}

/* ---------- screens ---------- */
function render() {
  drawNav();
  const v = $('view');
  const conn = $('conn');
  const anyOnline = stripDevices().some(d => d.online) || (haDevice() && haDevice().online);
  conn.textContent = anyOnline ? t('متصل','Connected') : t('مفيش مشترك متصل','No strip connected');
  conn.className = anyOnline ? 'live' : 'dead';
  if (tab === 'home') v.innerHTML = viewHome();
  else if (tab === 'outlets') v.innerHTML = viewOutlets();
  else if (tab === 'energy') v.innerHTML = viewEnergy();
  else v.innerHTML = viewSettings();
  afterRender();
}

function viewHome() {
  const total = totalWatts();
  const sd = stripDevices();
  let strips = sd.map(d => `
    <section class="block stripwrap" data-mac="${esc(d.mac)}">
      <div class="cap"><b>${esc(d.name||'Type-G')}</b><span>${d.online?t('يعمل','online'):t('غير متصل','offline')}</span></div>
      ${stripSVG(d)}
    </section>`).join('');
  if (!sd.length) strips = `<section class="block card"><div class="empty">
     ${t('لسه مفيش مشترك متصل. وصّل الموبايل بواي فاي البيت وافتح المشترك.','No strip yet. Connect your phone to home Wi-Fi and power the strip.')}
    </div></section>`;
  const ha = haDevice();
  const haLine = ha ? `<div class="hero"><div class="cap"></div></div>` : '';
  return `
    <h1 class="page">${t('أهلاً بيك','Welcome')}</h1>
    <p class="sub">${t('تحكم محلي مباشر من موبايلك','Local control, straight from your phone')}</p>
    <section class="block hero">
      <div class="big"><b>${total.toFixed(1)}</b><span class="unit">W</span></div>
      <div class="meta">
        <span>${stripDevices().filter(d=>d.online).length} ${t('مشترك متصل','strips online')}</span>
        <span>${S.running?t('الخدمة شغالة','service running'):t('الخدمة متوقفة','service stopped')}</span>
      </div>
    </section>
    <div class="btnrow">
      <button class="b pri" data-act="allOn">${t('تشغيل الكل','All on')}</button>
      <button class="b danger" data-act="allOff">${t('فصل الكل','All off')}</button>
    </div>
    ${strips}
    ${ha ? haBlock(ha) : ''}`;
}

function outletRow(mac, o) {
  const on = o.state === 'on';
  const w = (+o.watts||0).toFixed(1);
  const sub = on ? `<small class="w">${w} W</small>` : `<small>${t('مفصول','off')}</small>`;
  return `<div class="row ${on?'on':''}">
    <div class="idx">${o.channel}</div>
    <div class="info">
      <b>${esc(o.name||('Outlet '+o.channel))} ${o.protected?'<span class="lock">🔒</span>':''}</b>
      ${sub} ${o.timer?('· <small>⏱ '+Math.round((o.timer.seconds||0)/60)+'m</small>'):''}
    </div>
    <button class="iconbtn" data-edit="${mac}|${o.channel}" aria-label="${t('تعديل','Edit')}">⋯</button>
    <button class="sw ${on?'on':''} ${o.pending?'pending':''}" data-toggle="${mac}|${o.channel}|${on?'off':'on'}" aria-label="${esc(o.name)}"></button>
  </div>`;
}

function viewOutlets() {
  const sd = stripDevices();
  const addBtn = `<div class="btnrow"><button class="b pri wide" data-act="setup">${t('+ إضافة مشترك جديد','+ Add a strip')}</button></div>`;
  if (!sd.length) return `<h1 class="page">${t('المخارج','Outlets')}</h1><section class="block card"><div class="empty">${t('مفيش مشترك متصل','No strip connected')}</div></section>${addBtn}`;
  return `<h1 class="page">${t('المخارج','Outlets')}</h1>
    <p class="sub">${t('اضغط على المخرج في الصورة أو المفتاح','Tap a socket in the picture or a switch')}</p>
    ${addBtn}
    ${sd.map(d => `
      <section class="block stripwrap"><div class="cap"><b>${esc(d.name||'Type-G')}</b>
        <span>${d.online?t('يعمل','online'):t('غير متصل','offline')}</span></div>
        ${stripSVG(d)}
      </section>
      <section class="block card">${(d.outlets||[]).map(o=>outletRow(d.mac,o)).join('')}</section>
      <div class="btnrow">
        <button class="b pri" data-group="${d.mac}|on">${t('تشغيل كل المخارج','All on')}</button>
        <button class="b danger" data-group="${d.mac}|off">${t('فصل الكل','All off')}</button>
      </div>`).join('')}`;
}

function haBlock(ha) {
  return `<h2 class="hd">Home Assistant</h2>
    <section class="block card">${(ha.outlets||[]).length?(ha.outlets||[]).map(o=>outletRow('HA',o)).join('')
      :`<div class="empty">${ha.online?t('مفيش سويتشات','No switches found'):t('مش متصل بـ HA','Not connected to HA')}</div>`}</section>`;
}

function viewEnergy() {
  const sd = stripDevices();
  const rate = (S.tariff && +S.tariff.rate) || 0;
  if (!sd.length) return `<h1 class="page">${t('الطاقة','Energy')}</h1><section class="block card"><div class="empty">${t('مفيش بيانات بعد','No data yet')}</div></section>`;
  let body = sd.map(d => {
    const outs = d.outlets||[];
    const wh = outs.reduce((s,o)=>s+(+o.wh||0),0), cost = outs.reduce((s,o)=>s+(+o.cost||0),0);
    const maxw = Math.max(1, ...outs.map(o=>+o.watts||0));
    return `<section class="block card" data-mac="${esc(d.mac)}"><div class="row"><div class="info">
        <b>${esc(d.name||'Type-G')}</b><small>${(wh/1000).toFixed(2)} kWh · ${cost.toFixed(2)} ${t('جنيه','EGP')} ${t('اليوم','today')}</small>
      </div></div>
      <div class="row" data-barsfor="${esc(d.mac)}"><div class="info"><div class="bars" data-bars='${encodeURIComponent(JSON.stringify(outs.map(o=>({l:o.channel,w:+o.watts||0}))))}' data-max="${maxw}"></div></div></div>
      ${outs.map(o=>`<div class="kv"><span>${esc(o.name||('Outlet '+o.channel))}</span><b>${(+o.watts||0).toFixed(1)} W · ${((+o.wh||0)/1000).toFixed(3)} kWh</b></div>`).join('')}
    </section>`;
  }).join('');
  return `<h1 class="page">${t('الطاقة','Energy')}</h1>
    <p class="sub">${t('تعريفة الكهرباء','Electricity tariff')}: ${rate?(rate+' '+t('جنيه/كيلوواط','EGP/kWh')):t('مش متحددة','not set')}</p>
    ${body}
    <div class="btnrow"><button class="b wide" data-act="tariff">${t('ضبط التعريفة','Set tariff')}</button></div>
    <p class="sub">${t('قراءة الواط من المشترك تقريبية ولسه بنتأكد من دقتها.','Watt readings from the strip are approximate and still being verified.')}</p>`;
}

function viewSettings() {
  const ha = api('haStatus') || {};
  return `<h1 class="page">${t('الإعدادات','Settings')}</h1>
    <h2 class="hd">Home Assistant</h2>
    <section class="block card"><div class="row"><div class="info">
        <b>${ha.configured?(ha.connected?t('متصل ✓','Connected ✓'):t('محفوظ — مش متصل','Saved — not connected')):t('مش مربوط','Not linked')}</b>
        <small>${esc(ha.url||t('اربط عشان تتحكم في أجهزة HA','Link to control HA devices'))}</small>
      </div><button class="iconbtn" data-act="ha">⋯</button></div></section>

    <h2 class="hd">${t('الكهرباء','Power')}</h2>
    <section class="block card">
      <div class="row"><div class="info"><b>${t('تعريفة الكهرباء','Tariff')}</b><small>${(S.tariff&&S.tariff.rate)?S.tariff.rate+' '+t('جنيه/كيلوواط','EGP/kWh'):t('مش متحددة','not set')}</small></div><button class="iconbtn" data-act="tariff">⋯</button></div>
      <div class="row"><div class="info"><b>${t('إيقاظ الكمبيوتر','Wake computer')}</b><small>Wake-on-LAN</small></div><button class="iconbtn" data-act="wake">⋯</button></div>
    </section>

    <h2 class="hd">${t('المظهر','Appearance')}</h2>
    <section class="block card">
      <div class="row"><div class="info"><b>${t('الوضع','Theme')}</b></div>
        <div class="chips">
          ${['system','dark','light'].map(m=>`<button class="chip ${(localStorage.getItem('theme')||'system')===m?'sel':''}" data-theme="${m}">${t({system:'تلقائي',dark:'غامق',light:'فاتح'}[m],{system:'Auto',dark:'Dark',light:'Light'}[m])}</button>`).join('')}
        </div>
      </div>
      <div class="row"><div class="info"><b>${t('اللغة','Language')}</b></div>
        <div class="chips">
          <button class="chip ${AR()?'sel':''}" data-setlang="ar">العربية</button>
          <button class="chip ${!AR()?'sel':''}" data-setlang="en">English</button>
        </div>
      </div>
    </section>

    <h2 class="hd">${t('عن','About')}</h2>
    <section class="block card"><div class="kv"><span>${t('المشترك','Strip')}</span><b>MTTL-W01</b></div>
      <div class="kv"><span>${t('الإصدار','Version')}</span><b dir="ltr">${esc(S.appVersion||'—')}</b></div>
      ${hasBridge?'':`<div class="kv"><span>${t('وضع المعاينة','Preview mode')}</span><b>${t('بيانات تجريبية','demo data')}</b></div>`}</section>`;
}

/* ---------- sheets ---------- */
function openSheet(html) { $('sheet-body').innerHTML = html; $('sheet').hidden = false; }
function closeSheet() { $('sheet').hidden = true; }

function sheetEdit(mac, ch) {
  const d = devices().find(x => x.mac === mac); if (!d) return;
  const o = (d.outlets||[]).find(x => x.channel === ch); if (!o) return;
  const ha = mac === 'HA';
  openSheet(`<h3>${t('المخرج','Outlet')} ${ch}</h3>
    <label class="f">${t('الاسم','Name')}</label>
    <input class="in" id="e-name" value="${esc(o.name||'')}" ${ha?'disabled':''}>
    ${ha?'':`<div class="row"><div class="info"><b>${t('مخرج محمي','Protected')}</b><small>${t('ميتفصلش بالغلط','Stays on')}</small></div>
      <button class="sw ${o.protected?'on':''}" id="e-prot"></button></div>`}
    <h2 class="hd">${t('مؤقت','Timer')}</h2>
    <div class="chips" id="e-timer">
      ${[['5',t('٥ دقائق','5m')],['30',t('٣٠ دقيقة','30m')],['60',t('ساعة','1h')],['120',t('ساعتين','2h')]].map(([m,l])=>`<button class="chip" data-tmin="${m}">${l}</button>`).join('')}
      <button class="chip" data-tmin="0">${t('إلغاء المؤقت','Cancel')}</button>
    </div>
    <p class="sub">${t('المؤقت هيفصل المخرج بعد المدة.','Timer turns the outlet off after the time.')}</p>
    <div class="btnrow"><button class="b pri wide" id="e-save">${t('حفظ','Save')}</button></div>`);
  if (!ha) $('e-prot').onclick = e => e.currentTarget.classList.toggle('on');
  $('e-timer').onclick = e => { const b = e.target.closest('[data-tmin]'); if (!b) return;
    const min = +b.dataset.tmin;
    api('timer', { mac, channel: ch, seconds: min*60, state: 'off' });
    toast(min?t('اتضبط المؤقت','Timer set'):t('اتلغى المؤقت','Timer cancelled')); closeSheet(); refresh(); };
  $('e-save').onclick = () => {
    const body = { mac, channel: ch, name: $('e-name').value };
    if (!ha) body.protected = $('e-prot').classList.contains('on');
    api('saveMeta', body); toast(t('اتحفظ','Saved')); closeSheet(); refresh();
  };
}

function sheetTariff() {
  openSheet(`<h3>${t('تعريفة الكهرباء','Electricity tariff')}</h3>
    <label class="f">${t('سعر الكيلوواط (جنيه)','Price per kWh (EGP)')}</label>
    <input class="in" id="ta" inputmode="decimal" value="${esc((S.tariff&&S.tariff.rate)||'')}" placeholder="2.15">
    <div class="btnrow"><button class="b pri wide" id="ta-save">${t('حفظ','Save')}</button></div>`);
  $('ta-save').onclick = () => { api('saveTariff', { rate: $('ta').value }); toast(t('اتحفظ','Saved')); closeSheet(); refresh(); };
}

function sheetWake() {
  const w = S.wakeConfig || {};
  openSheet(`<h3>${t('إيقاظ الكمبيوتر','Wake computer')} · Wake-on-LAN</h3>
    <label class="f">MAC</label><input class="in" id="w-mac" value="${esc(w.mac||'')}" placeholder="AA:BB:CC:DD:EE:FF" dir="ltr">
    <label class="f">${t('عنوان البث (اختياري)','Broadcast (optional)')}</label><input class="in" id="w-bc" value="${esc(w.broadcast||'')}" placeholder="255.255.255.255" dir="ltr">
    <div class="btnrow"><button class="b" id="w-save">${t('حفظ','Save')}</button><button class="b pri" id="w-now">${t('أيقظ دلوقتي','Wake now')}</button></div>`);
  $('w-save').onclick = () => { api('wakeSave', { mac: $('w-mac').value, broadcast: $('w-bc').value }); toast(t('اتحفظ','Saved')); closeSheet(); };
  $('w-now').onclick = () => { api('wakeSave', { mac: $('w-mac').value, broadcast: $('w-bc').value }); api('wakeNow', {}); toast(t('اتبعت','Sent')); };
}

function sheetHA() {
  const st = api('haStatus') || {};
  openSheet(`<h3>${t('ربط Home Assistant','Link Home Assistant')}</h3>
    <label class="f">${t('عنوان HA','HA address')}</label>
    <input class="in" id="ha-url" value="${esc(st.url||'')}" placeholder="http://192.168.1.50:8123" dir="ltr">
    <label class="f">Long-Lived Access Token</label>
    <input class="in" id="ha-tok" placeholder="${t('الصق التوكن','Paste token')}" dir="ltr">
    <div class="btnrow"><button class="b pri wide" id="ha-save">${t('حفظ وفحص','Save & test')}</button></div>
    <p class="sub">${t('من HA: بروفايلك ← Long-Lived Access Tokens ← Create Token','In HA: your profile → Long-Lived Access Tokens → Create Token')}</p>
    <div id="ha-msg" class="sub"></div>`);
  $('ha-save').onclick = () => {
    api('haSave', { url: $('ha-url').value, token: $('ha-tok').value });
    $('ha-msg').textContent = t('بيفحص…','Testing…');
    setTimeout(() => { const r = api('haStatus') || {}; $('ha-msg').textContent = r.connected ? t('اتصل بنجاح ✓','Connected ✓') : t('اتحفظ بس مفيش اتصال — راجع العنوان/التوكن','Saved but not connected — check address/token'); refresh(); }, 1200);
  };
}

function provMsg(code){
  const m={
    CONNECTING_AP:t('بنتصل بشبكة المشترك… وافق على الطلب اللي هيظهر','Joining the strip network… approve the prompt'),
    SENDING_IP:t('بنظبط عنوان الموبايل…','Setting phone address…'),
    SENDING_WIFI:t('بنبعت بيانات الواي فاي…','Sending Wi-Fi details…'),
    OK:t('تم! المشترك هيعمل ريستارت ويتصل خلال لحظات','Done! The strip will restart and connect shortly'),
    AP_UNAVAILABLE:t('مقدرش يتصل بشبكة المشترك — اتأكد إنها ظاهرة وإن المشترك في وضع الإعداد','Could not join the strip network — make sure it is in setup mode'),
    IP_REJECTED:t('المشترك رفض عنوان الموبايل','The strip rejected the phone address'),
    CONNECT_REJECTED:t('المشترك رفض بيانات الواي فاي — راجع الاسم والباسوورد','The strip rejected the Wi-Fi details'),
    AP_IO_ERROR:t('مشكلة في الاتصال بخدمة الإعداد','Could not reach the setup service'),
    BAD_WIFI_CREDS:t('اسم/باسوورد الواي فاي ميحتوش «:» أو أسطر جديدة','Wi-Fi name/password cannot contain ":" or newlines'),
    ANDROID_TOO_OLD:t('نسخة الأندرويد قديمة على الإعداد التلقائي — اتصل بالشبكة يدويًا','Android too old for auto-setup — connect manually')
  };
  return m[code]||code;
}
window.onProvision=function(kind,code){ const el=document.getElementById('s-msg'); if(!el)return;
  el.innerHTML = kind==='ok'?('<span class="ok">'+provMsg('OK')+'</span>')
    : kind==='fail'?('<span class="bad">'+provMsg(code)+'</span>') : provMsg(code);
};

function sheetSetup(){
  api('prepPerms');
  const ip=(api('homeIp')||{}).ip||'';
  openSheet(`<h3>${t('إضافة مشترك جديد','Add a strip')}</h3>
    <p class="sub">${t('هنتصل بشبكة المشترك المؤقتة، نظبط الواي فاي، ونوجّهه للموبايل — من غير ما تكتب باسوورد المشترك.','We join the strip network, set Wi-Fi, and point it to your phone — no typing the strip password.')}</p>
    <p class="sub">${t('اضغط زرار المشترك ١٠ ثواني لحد ما اللمبة تنوّر بسرعة.','Hold the strip button ~10s until the LED blinks fast.')}</p>
    <label class="f">${t('شبكة المشترك','Strip network')}</label>
    <input class="in" id="s-ap" placeholder="TONLY_TAP_XXXXXXX" dir="ltr">
    <div class="btnrow"><button class="b wide" id="s-scan">${t('مسح الشبكات القريبة','Scan nearby')}</button></div>
    <div id="s-list" class="chips"></div>
    <label class="f">${t('باسوورد المشترك (تلقائي من الاسم)','Strip password (auto from name)')}</label>
    <input class="in" id="s-appw" dir="ltr" readonly>
    <h2 class="hd">${t('شبكة البيت','Home Wi-Fi')}</h2>
    <label class="f">${t('اسم شبكة البيت','Home Wi-Fi name')}</label>
    <input class="in" id="s-hs" dir="ltr">
    <label class="f">${t('باسوورد شبكة البيت','Home Wi-Fi password')}</label>
    <input class="in" id="s-hp" dir="ltr">
    <label class="f">${t('عنوان الموبايل (IP)','Phone address (IP)')}</label>
    <input class="in" id="s-ip" dir="ltr" value="${esc(ip)}">
    <div class="btnrow"><button class="b pri wide" id="s-go">${t('ابدأ الإعداد','Start setup')}</button></div>
    <div id="s-msg" class="sub"></div>`);
  const ap=document.getElementById('s-ap'), appw=document.getElementById('s-appw');
  const deriv=()=>{ appw.value=((api('apPassword',{ssid:ap.value})||{}).password)||''; };
  ap.addEventListener('input',deriv);
  document.getElementById('s-scan').onclick=()=>{ api('prepPerms');
    const r=api('scanStrips')||{}; const nets=r.networks||[];
    document.getElementById('s-list').innerHTML = nets.length?nets.map(n=>`<button class="chip" data-net="${esc(n)}">${esc(n)}</button>`).join('')
      : `<span class="sub">${t('مفيش شبكات مشترك ظهرت — قرّب من المشترك وتأكد إنه في وضع الإعداد','No strip networks found — get closer and ensure setup mode')}</span>`; };
  document.getElementById('s-list').onclick=e=>{ const b=e.target.closest('[data-net]'); if(!b)return; ap.value=b.dataset.net; deriv(); };
  document.getElementById('s-hs').addEventListener('blur',e=>{ const pw=((api('wifiGet',{ssid:e.target.value})||{}).password)||''; if(pw&&!document.getElementById('s-hp').value) document.getElementById('s-hp').value=pw; });
  document.getElementById('s-go').onclick=()=>{
    const apSsid=ap.value.trim(), homeSsid=document.getElementById('s-hs').value.trim(),
          homePass=document.getElementById('s-hp').value, serverIp=document.getElementById('s-ip').value.trim();
    if(!apSsid){ document.getElementById('s-msg').textContent=t('اكتب أو امسح اسم شبكة المشترك','Enter or scan the strip network'); return; }
    if(!homeSsid){ document.getElementById('s-msg').textContent=t('اكتب اسم شبكة البيت','Enter the home Wi-Fi name'); return; }
    document.getElementById('s-msg').textContent=t('بنبدأ…','Starting…');
    api('provision',{apSsid,homeSsid,homePass,serverIp});
  };
}

/* ---------- actions ---------- */
function afterRender() {
  // bars heights (avoid inline style in markup; set via JS)
  document.querySelectorAll('.bars[data-bars]').forEach(b => {
    const data = JSON.parse(decodeURIComponent(b.dataset.bars)); const max = +b.dataset.max || 1;
    b.innerHTML = data.map(x => `<div><div class="bar"></div><div class="bl">${x.l}</div></div>`).join('');
    b.querySelectorAll('.bar').forEach((el,i) => { el.style.height = Math.max(4, (data[i].w/max)*70) + 'px'; });
  });
}

document.addEventListener('click', e => {
  const tg = e.target.closest('[data-toggle]');
  if (tg) { const [mac,ch,to] = tg.dataset.toggle.split('|'); tg.classList.add('pending');
    const r = api('control', { mac, channel:+ch, state:to }); if (r) setTimeout(refresh, 350); else refresh(); return; }
  const sk = e.target.closest('.sockhit');
  if (sk) { const mac = sk.closest('[data-mac]')?.dataset.mac || stripDevices()[0]?.mac; const ch = +sk.dataset.ch;
    const dev = devices().find(d=>d.mac===mac); const o = dev && (dev.outlets||[]).find(x=>x.channel===ch);
    if (o) { api('control', { mac, channel:ch, state:o.state==='on'?'off':'on' }); setTimeout(refresh,350);} return; }
  const ed = e.target.closest('[data-edit]'); if (ed) { const [mac,ch] = ed.dataset.edit.split('|'); sheetEdit(mac,+ch); return; }
  const gp = e.target.closest('[data-group]'); if (gp) { const [mac,st] = gp.dataset.group.split('|'); api('groupControl',{mac,state:st}); setTimeout(refresh,350); return; }
  const th = e.target.closest('[data-theme]'); if (th) { applyTheme(th.dataset.theme); api('settings',{themeMode:th.dataset.theme}); render(); return; }
  const sl = e.target.closest('[data-setlang]'); if (sl) { LANG = sl.dataset.setlang; localStorage.setItem('lang',LANG); document.documentElement.lang=LANG; document.documentElement.dir=AR()?'rtl':'ltr'; $('lang').textContent=AR()?'EN':'ع'; render(); return; }
  const ac = e.target.closest('[data-act]'); if (ac) { const a = ac.dataset.act;
    if (a==='allOff') { api('allOff',{}); setTimeout(refresh,350);} 
    else if (a==='allOn') { stripDevices().forEach(d=>api('groupControl',{mac:d.mac,state:'on'})); setTimeout(refresh,350);} 
    else if (a==='tariff') sheetTariff(); else if (a==='wake') sheetWake(); else if (a==='ha') sheetHA();
    else if (a==='setup') sheetSetup(); return; }
  if (e.target.id === 'sheet') closeSheet();
});

/* ---------- nav ---------- */
function drawNav() {
  const items = [
    ['home', t('الرئيسية','Home'), 'M3 11l9-8 9 8M5 10v10h14V10'],
    ['outlets', t('المخارج','Outlets'), 'M6 3v6a6 6 0 0012 0V3M9 3v4M15 3v4M12 15v6'],
    ['energy', t('الطاقة','Energy'), 'M13 2L4 14h7l-1 8 9-12h-7z'],
    ['settings', t('الإعدادات','Settings'), 'M12 15a3 3 0 100-6 3 3 0 000 6zM19 12l2 1-2 4-2-1a7 7 0 01-2 1l-1 2h-4l-1-2a7 7 0 01-2-1l-2 1-2-4 2-1a7 7 0 010-2l-2-1 2-4 2 1a7 7 0 012-1l1-2h4l1 2a7 7 0 012 1l2-1 2 4-2 1a7 7 0 010 2z']
  ];
  $('nav').innerHTML = items.map(([k,label,p]) =>
    `<button data-nav="${k}" class="${tab===k?'sel':''}"><svg viewBox="0 0 24 24"><path d="${p}"/></svg>${label}</button>`).join('');
}
$('nav').addEventListener('click', e => { const b = e.target.closest('[data-nav]'); if (b) { tab = b.dataset.nav; render(); } });
$('lang').addEventListener('click', () => { LANG = AR()?'en':'ar'; localStorage.setItem('lang',LANG); document.documentElement.lang=LANG; document.documentElement.dir=AR()?'rtl':'ltr'; $('lang').textContent=AR()?'EN':'ع'; render(); });

/* ---------- theme ---------- */
function applyTheme(mode) {
  localStorage.setItem('theme', mode);
  if (mode === 'system') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', mode);
}

/* ---------- loop ---------- */
function refresh() { const s = api('state'); if (s) { S = s; if (S.lang && !localStorage.getItem('lang')) LANG = S.lang; render(); } }
function boot() {
  applyTheme(localStorage.getItem('theme') || 'system');
  document.documentElement.lang = LANG; document.documentElement.dir = AR()?'rtl':'ltr';
  $('lang').textContent = AR()?'EN':'ع';
  if (hasBridge) api('start', {});
  refresh();
  setInterval(() => { if (!document.hidden) refresh(); }, 3000);
}
window.goBack = () => { if (!$('sheet').hidden) { closeSheet(); return; } if (tab!=='home'){ tab='home'; render(); } };
boot();
