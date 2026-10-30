// 零依赖后端：Node 18+ 直接运行 `node server.js`。数据存在 db.json。
const http = require('http'), fs = require('fs'), path = require('path'), crypto = require('crypto');
const PORT = process.env.PORT || 3000, KEY = process.env.PARENT_KEY || 'parent123';
const DBF = path.join(__dirname, 'db.json');
let db = { devices: {}, codes: {}, requests: [] };
try { db = JSON.parse(fs.readFileSync(DBF)); } catch {}
const save = () => fs.writeFileSync(DBF, JSON.stringify(db));
const send = (res, c, o) => { res.writeHead(c, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(o)); };
const body = req => new Promise(r => { let s = ''; req.on('data', d => s += d); req.on('end', () => { try { r(JSON.parse(s || '{}')); } catch { r({}); } }); });
const defRules = () => ({ locked: false, timed: true, sleepStart: 21, sleepEnd: 7, daily: 60, limits: {}, whitelist: [] });
const devOf = req => { const t = (req.headers.authorization || '').slice(7); return Object.values(db.devices).find(d => d.token === t); };
const pub = d => ({ id: d.id, model: d.model, rules: d.rules, battery: d.battery, seen: d.seen, used: d.used, loc: d.loc, fences: d.fences || [], inside: d.inside || {}, sessions: (d.sessions || []).slice(-200), adminDisabled: !!d.adminDisabled, device: d.device || null, track: (d.track || []).slice(-500), shotWanted: !!d.shotWanted, shotTs: d.shot ? d.shot.ts : 0, shotReady: !!d.shotReady });
const dist = (a, b, c, d) => { const r = x => x * Math.PI / 180, dl = r(c - a), dg = r(d - b), h = Math.sin(dl / 2) ** 2 + Math.cos(r(a)) * Math.cos(r(c)) * Math.sin(dg / 2) ** 2; return 2 * 6371000 * Math.asin(Math.sqrt(h)); };
const addAlert = (d, text) => { db.alerts = (db.alerts || []).concat({ ts: Date.now(), model: d.model, text }).slice(-50); };
function checkFences(d) {
  d.fences = d.fences || []; d.inside = d.inside || {};
  for (const f of d.fences) {
    const inn = dist(d.loc.lat, d.loc.lng, f.lat, f.lng) <= f.r;
    if (f.id in d.inside && d.inside[f.id] !== inn) addAlert(d, (inn ? '到达 ' : '离开 ') + f.name);
    d.inside[f.id] = inn;
  }
}

http.createServer(async (req, res) => {
  const p = new URL(req.url, 'http://x').pathname, m = req.method;
  if (!p.startsWith('/api/')) {
    // 首页
    if (p === '/' || p === '/index.html') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(fs.readFileSync(path.join(__dirname, 'public/index.html')));
    }
    // 静态文件（防目录穿越）
    const rel = decodeURIComponent(p).replace(/^\/+/, '');
    if (!rel.includes('..')) {
      const fp = path.join(__dirname, 'public', rel);
      try {
        const st = fs.statSync(fp);
        if (st.isFile()) {
          const ext = path.extname(fp).toLowerCase();
          const types = {
            '.html': 'text/html; charset=utf-8',
            '.js': 'application/javascript; charset=utf-8',
            '.css': 'text/css; charset=utf-8',
            '.json': 'application/json; charset=utf-8',
            '.png': 'image/png',
            '.svg': 'image/svg+xml',
            '.apk': 'application/vnd.android.package-archive',
          };
          res.writeHead(200, {
            'Content-Type': types[ext] || 'application/octet-stream',
            'Content-Length': st.size,
            'Cache-Control': ext === '.apk' ? 'no-cache' : 'public, max-age=300',
          });
          return fs.createReadStream(fp).pipe(res);
        }
      } catch (_) {}
    }
    return send(res, 404, {});
  }
  // ---- 上传新版本 APK（必须在 body(req) 之前处理，否则请求流已被读空）----
  if (p === '/api/parent/publish' && m === 'POST') {
    if (req.headers['x-parent-key'] !== KEY) return send(res, 401, {});
    const versionCode = +(req.headers['x-version-code'] || 0);
    const versionName = req.headers['x-version-name'] || '1.0.0';
    if (!versionCode) return send(res, 400, { error: '缺少 x-version-code' });

    const dir = path.join(__dirname, 'public/apk');
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, 'anxinban.apk');
    const tmp = file + '.tmp';

    try {
      await new Promise((resolve, reject) => {
        const ws = fs.createWriteStream(tmp);
        req.pipe(ws);
        ws.on('finish', resolve);
        ws.on('close', resolve);
        ws.on('error', reject);
        req.on('error', reject);
      });
    } catch (e) {
      return send(res, 500, { error: '接收失败: ' + e.message });
    }

    let sz = 0;
    try { sz = fs.statSync(tmp).size; } catch (_) {}
    if (sz < 100000) {
      try { fs.unlinkSync(tmp); } catch (_) {}
      return send(res, 400, { error: '文件过小（收到 ' + sz + ' 字节），不是有效 APK' });
    }
    fs.renameSync(tmp, file);

    db.latestVersion = {
      versionCode,
      versionName,
      apkUrl: '/apk/anxinban.apk',
      size: sz,
      notes: req.headers['x-notes'] ? decodeURIComponent(req.headers['x-notes']) : '',
      updatedAt: Date.now(),
    };
    save();
    return send(res, 200, db.latestVersion);
  }

  const b = m === 'POST' ? await body(req) : {};
  // ---- 孩子端 ----
  if (p === '/api/bind') {
    const exp = db.codes[b.code];
    if (!exp || exp < Date.now()) return send(res, 400, { error: '绑定码无效或已过期' });
    delete db.codes[b.code];
    const id = crypto.randomUUID(), token = crypto.randomBytes(24).toString('hex');
    db.devices[id] = { id, token, model: b.model || '未知设备', rules: defRules(), battery: null, seen: 0, used: {} };
    save(); return send(res, 200, { token });
  }
  if (['/api/heartbeat', '/api/rules', '/api/requests'].includes(p)) {
    const d = devOf(req);
    if (!d) {
      // 区分「被家长解绑」和「token 无效」，前者让孩子端自我清理
      const tk = (req.headers.authorization || '').slice(7);
      const wasUnbound = db.unbound && db.unbound['t:' + tk];
      return send(res, 401, wasUnbound ? { unbound: true } : {});
    }
    if (p === '/api/rules') return send(res, 200, d.rules);
    if (p === '/api/heartbeat') {
      Object.assign(d, { battery: b.battery, seen: Date.now(), used: b.usedMinutes || {} });
      if (typeof b.lat === 'number') { d.loc = { lat: b.lat, lng: b.lng, acc: b.acc, ts: Date.now() }; checkFences(d); }
      if (Array.isArray(b.sessions) && b.sessions.length) d.sessions = (d.sessions || []).concat(b.sessions).slice(-500);
      if (typeof b.adminDisabled === 'boolean') d.adminDisabled = b.adminDisabled;
      if (b.device && typeof b.device === 'object') d.device = b.device;
      // 孩子端上传截图
      if (typeof b.shot === 'string' && b.shot.length > 100) {
        d.shot = { ts: Date.now(), img: b.shot };
        d.shotWanted = 0;
      }
      if (Array.isArray(b.track) && b.track.length) {
        d.track = (d.track || []).concat(b.track);
        // 只保留最近 3 天，防止无限增长
        const cut = Date.now() - 3 * 86400000;
        d.track = d.track.filter(x => (x.ts || 0) >= cut).slice(-2000);
      }
      save(); return send(res, 200, { wantShot: !!d.shotWanted });
    }
    db.requests.push({ id: crypto.randomUUID(), deviceId: d.id, pkg: b.pkg, minutes: b.minutes || 15, ts: Date.now(), status: 'pending' });
    save(); return send(res, 200, {});
  }
  // ---- 版本信息（孩子端检查更新用）----
  if (p === '/api/version') {
    const v = db.latestVersion || { versionCode: 1, versionName: '1.0.0', apkUrl: '', notes: '' };
    // 拼成绝对地址，孩子端可直接下载
    const host = req.headers['x-forwarded-host'] || req.headers.host || '';
    const proto = req.headers['x-forwarded-proto'] || 'https';
    const out = { ...v };
    if (v.apkUrl && v.apkUrl.startsWith('/')) out.apkUrl = proto + '://' + host + v.apkUrl;
    return send(res, 200, out);
  }

  // ---- 家长端（请求头 x-parent-key）----
  if (p.startsWith('/api/parent/')) {
    if (req.headers['x-parent-key'] !== KEY) return send(res, 401, {});
    const a = p.slice(12);
    if (a === 'code') { const c = String(Math.floor(100000 + Math.random() * 900000)); db.codes[c] = Date.now() + 600000; save(); return send(res, 200, { code: c }); }
    if (a === 'state') return send(res, 200, { devices: Object.values(db.devices).map(pub), requests: db.requests.filter(r => r.status === 'pending'), alerts: (db.alerts || []).slice(-10).reverse(), deviceCount: Object.keys(db.devices).length });
    if (a === 'unbind') {
      const d = db.devices[b.deviceId];
      if (!d) return send(res, 404, { error: '设备不存在' });
      db.unbound = db.unbound || {};
      db.unbound['t:' + d.token] = Date.now();
      delete db.devices[d.id];
      db.requests = (db.requests || []).filter(r => r.deviceId !== d.id);
      save();
      return send(res, 200, { ok: true });
    }
    if (a === 'version/set') {
      db.latestVersion = {
        versionCode: +b.versionCode || 1,
        versionName: b.versionName || '1.0.0',
        apkUrl: b.apkUrl || '',
        notes: b.notes || '',
        updatedAt: Date.now(),
      };
      save();
      return send(res, 200, db.latestVersion);
    }
    if (a === 'shot/request') {
      const d = db.devices[b.deviceId]; if (!d) return send(res, 404, {});
      d.shotWanted = Date.now(); save();
      return send(res, 200, { ok: true });
    }
    if (a === 'shot/get') {
      const d = db.devices[b.deviceId]; if (!d) return send(res, 404, {});
      if (!d.shot) return send(res, 200, { empty: true });
      return send(res, 200, { ts: d.shot.ts, img: d.shot.img, ready: !!d.shotReady });
    }
    if (a === 'rules') { const d = db.devices[b.deviceId]; if (!d) return send(res, 404, {}); Object.assign(d.rules, b.rules); save(); return send(res, 200, d.rules); }
    if (a === 'fence') {
      const d = db.devices[b.deviceId]; if (!d) return send(res, 404, {});
      const lat = b.lat ?? (d.loc && d.loc.lat), lng = b.lng ?? (d.loc && d.loc.lng);
      if (lat == null) return send(res, 400, { error: '孩子端还没有上报位置' });
      d.fences = d.fences || []; d.inside = d.inside || {};
      const f = { id: crypto.randomUUID(), name: b.name || '围栏', lat, lng, r: +b.r || 200 };
      d.fences.push(f); d.inside[f.id] = true; save(); return send(res, 200, f);
    }
    if (a === 'fence/delete') { const d = db.devices[b.deviceId]; if (!d) return send(res, 404, {}); d.fences = (d.fences || []).filter(f => f.id !== b.id); save(); return send(res, 200, {}); }
    if (a === 'requests') {
      const r = db.requests.find(x => x.id === b.id); if (!r) return send(res, 404, {});
      r.status = b.approve ? 'approved' : 'denied';
      if (b.approve) { const d = db.devices[r.deviceId]; d.rules.limits[r.pkg] = (d.rules.limits[r.pkg] || 0) + r.minutes; d.rules.daily += r.minutes; }
      save(); return send(res, 200, {});
    }
  }
  send(res, 404, {});
}).listen(PORT, () => console.log('安心伴后端已启动 http://0.0.0.0:' + PORT + '  家长密钥: ' + KEY));
