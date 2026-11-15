/**
 * T-Pics · servidor intermediario (Cloudflare Worker)
 *
 * Guarda los tokens de Telegram fuera de la app. La app solo conoce la
 * dirección de este Worker y se identifica con su sesión de Firebase.
 *
 * Variables secretas (Settings → Variables and Secrets):
 *   BOT_TOKEN            token del bot donde se guardan los archivos nuevos
 *   BOT_TOKEN_PERSONAL   (opcional) token del bot personal antiguo, para leer fotos viejas
 *   STORAGE_CHAT_ID      chat o canal donde el bot guarda los archivos
 *   SIGNING_SECRET       texto largo al azar; firma las referencias de archivos
 *   FIREBASE_PROJECT_ID  p. ej. t-pics-clientes
 *   FIREBASE_DB_URL      p. ej. https://t-pics-clientes-default-rtdb.firebaseio.com
 */

const MAX_PART = 19 * 1024 * 1024; // Telegram solo deja descargar 20 MB por archivo
const TG = 'https://api.telegram.org';

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') return new Response(null, { headers: cors() });
    try {
      const res = await route(request, env, ctx);
      const headers = new Headers(res.headers);
      for (const [k, v] of Object.entries(cors())) headers.set(k, v);
      return new Response(res.body, { status: res.status, headers });
    } catch (e) {
      const status = e.status || 500;
      const extra = e.retryAfter ? { retryAfter: e.retryAfter } : {};
      return json({ error: e.message || String(e), ...extra }, status);
    }
  },
};

function cors() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization,Content-Type,Range',
    'Access-Control-Expose-Headers': 'Content-Range,Content-Length,Accept-Ranges',
  };
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
}

function fail(status, message, retryAfter) {
  const e = new Error(message);
  e.status = status;
  if (retryAfter) e.retryAfter = retryAfter;
  return e;
}

async function route(request, env, ctx) {
  const url = new URL(request.url);
  const path = url.pathname;

  if (path === '/' || path === '/health') {
    const ready = !!(env.BOT_TOKEN && env.STORAGE_CHAT_ID && env.SIGNING_SECRET &&
      env.FIREBASE_PROJECT_ID && env.FIREBASE_DB_URL);
    return json({ ok: true, service: 't-pics', ready });
  }

  const user = await authenticate(request, url, env);

  if (path === '/me' && request.method === 'GET') {
    const access = await getAccess(user, env);
    return json({ uid: user.uid, admin: access.admin, active: access.active });
  }
  if (path === '/upload' && request.method === 'POST') return upload(request, url, env, user);
  if (path.startsWith('/f/') && request.method === 'GET') return download(request, url, env, user, path.slice(3));
  if (path === '/v' && request.method === 'GET') return downloadParts(request, url, env, user);
  if (path === '/delete' && request.method === 'POST') return remove(request, env, user);
  if (path === '/admin/sign' && request.method === 'POST') return adminSign(request, env, user);

  throw fail(404, 'Ruta no encontrada');
}

/* ───────────────────────── Sesión (Firebase) ───────────────────────── */

let jwksCache = null;
let jwksAt = 0;

function b64uToBytes(s) {
  s = s.replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4) s += '=';
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function bytesToB64u(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function authenticate(request, url, env) {
  const header = request.headers.get('Authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : url.searchParams.get('auth');
  if (!token) throw fail(401, 'Falta iniciar sesión');

  const parts = token.split('.');
  if (parts.length !== 3) throw fail(401, 'Sesión no válida');

  let head, payload;
  try {
    head = JSON.parse(new TextDecoder().decode(b64uToBytes(parts[0])));
    payload = JSON.parse(new TextDecoder().decode(b64uToBytes(parts[1])));
  } catch {
    throw fail(401, 'Sesión no válida');
  }

  const now = Math.floor(Date.now() / 1000);
  const project = env.FIREBASE_PROJECT_ID;
  if (head.alg !== 'RS256' || payload.aud !== project ||
      payload.iss !== `https://securetoken.google.com/${project}` ||
      !payload.sub || payload.exp < now) {
    throw fail(401, 'Sesión vencida');
  }

  if (!jwksCache || Date.now() - jwksAt > 3600_000) {
    const r = await fetch('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com');
    if (!r.ok) throw fail(503, 'No se pudo verificar la sesión');
    jwksCache = (await r.json()).keys;
    jwksAt = Date.now();
  }
  const jwk = jwksCache.find((k) => k.kid === head.kid);
  if (!jwk) { jwksCache = null; throw fail(401, 'Sesión no válida'); }

  const key = await crypto.subtle.importKey(
    'jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  const ok = await crypto.subtle.verify(
    'RSASSA-PKCS1-v1_5', key, b64uToBytes(parts[2]),
    new TextEncoder().encode(parts[0] + '.' + parts[1]));
  if (!ok) throw fail(401, 'Sesión no válida');

  return { uid: payload.sub, email: payload.email || '', token };
}

/* ───────────────────── Permisos (admin / cuenta activa) ───────────────────── */

const accessCache = new Map();

async function getAccess(user, env) {
  const hit = accessCache.get(user.uid);
  if (hit && Date.now() - hit.at < 120_000) return hit;

  const base = env.FIREBASE_DB_URL.replace(/\/$/, '');
  const q = `?auth=${encodeURIComponent(user.token)}`;
  const [a, c] = await Promise.all([
    fetch(`${base}/v2/admins/${user.uid}.json${q}`),
    fetch(`${base}/v2/users/${user.uid}/account.json${q}`),
  ]);
  const admin = a.ok ? (await a.json()) === true : false;
  const account = c.ok ? await c.json() : null;
  const paidUntil = (account && account.paidUntil) || 0;
  const active = admin || (!!account && account.status === 'active' &&
    (paidUntil === 0 || paidUntil > Date.now()));

  const value = { admin, active, at: Date.now() };
  accessCache.set(user.uid, value);
  return value;
}

/* ─────────────────────── Referencias firmadas ───────────────────────
 * ref = bot.mensaje.file_id.firma
 * La firma ata el archivo a su dueño: otro usuario no puede pedirlo
 * aunque conozca el file_id.
 */

async function hmac(env, text) {
  const key = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(env.SIGNING_SECRET),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(text));
  return bytesToB64u(new Uint8Array(sig).slice(0, 16));
}

async function makeRef(env, uid, bot, mid, fileId) {
  const sig = await hmac(env, `${uid}|${bot}|${mid}|${fileId}`);
  return `${bot}.${mid}.${fileId}.${sig}`;
}

async function readRef(env, uid, ref) {
  const p = (ref || '').split('.');
  if (p.length !== 4) throw fail(400, 'Referencia no válida');
  const [bot, mid, fileId, sig] = p;
  const expected = await hmac(env, `${uid}|${bot}|${mid}|${fileId}`);
  if (expected !== sig) throw fail(403, 'Este archivo no es tuyo');
  return { bot, mid: Number(mid) || 0, fileId };
}

function botToken(env, bot) {
  const t = bot === 'p' ? env.BOT_TOKEN_PERSONAL : env.BOT_TOKEN;
  if (!t) throw fail(500, 'Falta configurar el token del bot');
  return t;
}

/* ───────────────────────────── Subir ───────────────────────────── */

async function upload(request, url, env, user) {
  const access = await getAccess(user, env);
  if (!access.active) throw fail(402, 'Tu cuenta no está activa');

  const length = Number(request.headers.get('Content-Length') || 0);
  if (length > MAX_PART) throw fail(413, 'Trozo demasiado grande');

  const data = await request.arrayBuffer();
  if (data.byteLength === 0) throw fail(400, 'Archivo vacío');
  if (data.byteLength > MAX_PART) throw fail(413, 'Trozo demasiado grande');

  const name = (url.searchParams.get('n') || 'archivo').replace(/[\\/:*?"<>|]/g, '_').slice(0, 120);

  const form = new FormData();
  form.append('chat_id', env.STORAGE_CHAT_ID);
  form.append('disable_notification', 'true');
  form.append('disable_content_type_detection', 'true');
  form.append('caption', `${user.uid} · ${name}`.slice(0, 1000));
  form.append('document', new Blob([data], { type: 'application/octet-stream' }), name);

  const r = await fetch(`${TG}/bot${botToken(env, 'g')}/sendDocument`, { method: 'POST', body: form });
  const out = await r.json().catch(() => ({}));
  if (!out.ok) {
    if (out.error_code === 429) {
      const wait = (out.parameters && out.parameters.retry_after) || 5;
      throw fail(429, 'Telegram pide esperar', wait);
    }
    throw fail(502, 'Telegram rechazó el archivo: ' + (out.description || r.status));
  }

  const m = out.result;
  const file = m.document || m.video || m.animation || m.audio ||
    (m.photo && m.photo[m.photo.length - 1]);
  if (!file) throw fail(502, 'Telegram no devolvió el archivo');

  const ref = await makeRef(env, user.uid, 'g', m.message_id, file.file_id);
  return json({ ref, size: data.byteLength });
}

/* ──────────────────────────── Descargar ──────────────────────────── */

const pathCache = new Map();

async function filePath(env, bot, fileId) {
  const hit = pathCache.get(fileId);
  if (hit && Date.now() - hit.at < 45 * 60_000) return hit.path;

  const r = await fetch(`${TG}/bot${botToken(env, bot)}/getFile?file_id=${encodeURIComponent(fileId)}`);
  const out = await r.json().catch(() => ({}));
  if (!out.ok) {
    if (out.error_code === 429) throw fail(429, 'Telegram pide esperar', (out.parameters && out.parameters.retry_after) || 3);
    throw fail(404, 'Archivo no disponible: ' + (out.description || ''));
  }
  if (pathCache.size > 5000) pathCache.clear();
  pathCache.set(fileId, { path: out.result.file_path, at: Date.now() });
  return out.result.file_path;
}

async function telegramFile(env, part, range) {
  const path = await filePath(env, part.bot, part.fileId);
  const headers = range ? { Range: range } : {};
  const r = await fetch(`${TG}/file/bot${botToken(env, part.bot)}/${path}`, { headers });
  if (!r.ok && r.status !== 206) {
    pathCache.delete(part.fileId);
    throw fail(502, 'No se pudo leer el archivo');
  }
  return r;
}

function safeMime(url) {
  const m = url.searchParams.get('m') || '';
  return /^(image|video)\/[\w.+-]+$/.test(m) ? m : 'application/octet-stream';
}

async function download(request, url, env, user, ref) {
  const part = await readRef(env, user.uid, decodeURIComponent(ref));
  const range = request.headers.get('Range');
  const r = await telegramFile(env, part, range);

  const headers = new Headers();
  headers.set('Content-Type', safeMime(url));
  headers.set('Cache-Control', 'private, max-age=31536000, immutable');
  headers.set('Accept-Ranges', 'bytes');
  for (const h of ['Content-Length', 'Content-Range']) {
    const v = r.headers.get(h);
    if (v) headers.set(h, v);
  }
  return new Response(r.body, { status: r.status, headers });
}

/** Archivos grandes: varios trozos servidos como un solo archivo, con saltos (Range). */
async function downloadParts(request, url, env, user) {
  const refs = (url.searchParams.get('p') || '').split(',').filter(Boolean);
  const partSize = Number(url.searchParams.get('ps') || 0);
  const total = Number(url.searchParams.get('sz') || 0);
  if (!refs.length || partSize <= 0 || total <= 0) throw fail(400, 'Faltan datos del archivo');
  if (refs.length > 24) throw fail(400, 'Archivo demasiado grande');

  const parts = [];
  for (const ref of refs) parts.push(await readRef(env, user.uid, ref));

  let start = 0;
  let end = total - 1;
  const range = request.headers.get('Range');
  const m = range && /^bytes=(\d*)-(\d*)$/.exec(range);
  if (m) {
    if (m[1] === '' && m[2] !== '') {
      start = Math.max(0, total - Number(m[2]));
    } else {
      start = Number(m[1]);
      if (m[2] !== '') end = Math.min(end, Number(m[2]));
    }
    if (start > end || start >= total) {
      return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${total}` } });
    }
  }
  const length = end - start + 1;

  const { readable, writable } = new FixedLengthStream(length);
  const pump = (async () => {
    try {
      let pos = start;
      while (pos <= end) {
        const index = Math.floor(pos / partSize);
        const inPart = pos - index * partSize;
        const partEnd = Math.min(end - index * partSize, partSize - 1);
        const want = partEnd - inPart + 1;
        const r = await telegramFile(env, parts[index], `bytes=${inPart}-${partEnd}`);
        if (r.status === 206) {
          await r.body.pipeTo(writable, { preventClose: true });
        } else {
          // Telegram ignoró el salto: se descarta lo que sobra.
          const reader = r.body.getReader();
          const writer = writable.getWriter();
          let skip = inPart;
          let left = want;
          while (left > 0) {
            const { done, value } = await reader.read();
            if (done) break;
            let chunk = value;
            if (skip > 0) {
              if (chunk.byteLength <= skip) { skip -= chunk.byteLength; continue; }
              chunk = chunk.subarray(skip);
              skip = 0;
            }
            if (chunk.byteLength > left) chunk = chunk.subarray(0, left);
            await writer.write(chunk);
            left -= chunk.byteLength;
          }
          await reader.cancel().catch(() => {});
          writer.releaseLock();
        }
        pos += want;
      }
      await writable.close();
    } catch (e) {
      await writable.abort(e).catch(() => {});
    }
  })();
  pump.catch(() => {});

  const headers = new Headers();
  headers.set('Content-Type', safeMime(url));
  headers.set('Content-Length', String(length));
  headers.set('Accept-Ranges', 'bytes');
  headers.set('Cache-Control', 'private, max-age=31536000, immutable');
  if (m) headers.set('Content-Range', `bytes ${start}-${end}/${total}`);
  return new Response(readable, { status: m ? 206 : 200, headers });
}

/* ───────────────────────────── Borrar ───────────────────────────── */

async function remove(request, env, user) {
  const body = await request.json().catch(() => ({}));
  const refs = Array.isArray(body.refs) ? body.refs.slice(0, 100) : [];
  let deleted = 0;
  for (const ref of refs) {
    try {
      const part = await readRef(env, user.uid, ref);
      if (!part.mid || part.bot !== 'g') continue;
      const r = await fetch(`${TG}/bot${botToken(env, 'g')}/deleteMessage`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ chat_id: env.STORAGE_CHAT_ID, message_id: part.mid }),
      });
      const out = await r.json().catch(() => ({}));
      if (out.ok) deleted++;
    } catch { /* se intenta con el siguiente */ }
  }
  return json({ deleted, total: refs.length });
}

/* ─────────────────── Admin: firmar archivos antiguos ─────────────────── */

async function adminSign(request, env, user) {
  const access = await getAccess(user, env);
  if (!access.admin) throw fail(403, 'Solo el administrador');

  const body = await request.json().catch(() => ({}));
  const uid = String(body.uid || '');
  const items = Array.isArray(body.items) ? body.items.slice(0, 500) : [];
  if (!uid) throw fail(400, 'Falta el usuario destino');

  const refs = [];
  for (const it of items) {
    const bot = it.b === 'p' ? 'p' : 'g';
    const fileId = String(it.f || '');
    refs.push(/^[\w-]+$/.test(fileId) ? await makeRef(env, uid, bot, 0, fileId) : '');
  }
  return json({ refs });
}
