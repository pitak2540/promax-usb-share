// JAMES USB Share relay — pairs a phone and a PC by a pairing code so USB/IP
// can cross networks (mobile data, different Wi-Fi, NAT). Each USB/IP TCP
// connection from the PC becomes one WebSocket pair through a Durable Object.
//
//   phone control : wss://<relay>/p/<CODE>/ctl?name=<phone name>   (kept open)
//   PC connection : wss://<relay>/c/<CODE>/data?id=<ID>
//       relay -> phone control: "open <ID>"
//   phone data    : wss://<relay>/p/<CODE>/data?id=<ID>
//       relay -> PC: "ready", then binary frames are forwarded both ways.
//   status        : https://<relay>/c/<CODE>/status  -> {"online":true,"name":"..."}

const PATH = /^\/(p|c)\/([A-Z0-9]{6,16})\/(ctl|data|status)$/;
const ID = /^[A-Za-z0-9]{4,32}$/;

// ---------------------------------------------------------------- licensing
// Same public key as the apps (License.java). Keys are verified here too, so a patched
// ("cracked") app still cannot use the cross-network relay without a genuine key.
const PUBLIC_KEY_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFb5E6RdUMGR3Q8UUwlRD1EIR0rM7mBccSo7qRAc56im18zMGHl1BJ3m3q3FtmZfDOIs5n//DOhzYyRvvk/4EWA==";
const B32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const EPOCH_MS = 1767225600000; // 2026-01-01
const TRIAL_DAYS = 7;
// Revoked device ids (as shown in the apps, without dashes). Add one and push to block it.
const REVOKED = new Set([]);
let pubKey = null;

function unbase32(s) {
  const out = [];
  let buf = 0, bits = 0;
  for (const ch of s) {
    const v = B32.indexOf(ch);
    if (v < 0) return null;
    buf = ((buf << 5) | v) & 0xffff;
    bits += 5;
    if (bits >= 8) { out.push((buf >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return new Uint8Array(out);
}

function base32(bytes) {
  let s = "", buf = 0, bits = 0;
  for (const b of bytes) {
    buf = ((buf << 8) | b) & 0xffff; bits += 8;
    while (bits >= 5) { s += B32[(buf >>> (bits - 5)) & 31]; bits -= 5; }
  }
  if (bits > 0) s += B32[(buf << (5 - bits)) & 31];
  return s;
}

const hex = (u8) => [...u8].map((b) => b.toString(16).padStart(2, "0")).join("");

async function verifyKey(text, devHex) {
  let c = (text || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (c.startsWith("JUS1")) c = c.slice(4);
  const all = unbase32(c);
  if (!all || all.length < 77) return "คีย์ไม่ถูกต้อง";
  const plen = all[0] === 1 ? 13 : all[0] === 2 ? 21 : -1;  // v1: one device, v2: PC + phone
  if (plen < 0 || all.length < plen + 64) return "คีย์ไม่ถูกต้อง";
  const payload = all.slice(0, plen), sig = all.slice(plen, plen + 64);
  if (!pubKey) {
    const der = Uint8Array.from(atob(PUBLIC_KEY_B64), (ch) => ch.charCodeAt(0));
    pubKey = await crypto.subtle.importKey("spki", der, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
  }
  const ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pubKey, sig, payload);
  if (!ok) return "คีย์ไม่ถูกต้อง";
  const devs = [payload.slice(1, 9)];
  if (plen === 21) devs.push(payload.slice(9, 17));
  const dev = devs.find((d) => hex(d) === devHex);
  if (!dev) return "คีย์นี้ออกให้เครื่องอื่น";
  for (const d of devs) if (REVOKED.has(base32(d))) return "คีย์นี้ถูกยกเลิกแล้ว";
  const expiryDay = plen === 13 ? (payload[9] << 8) | payload[10] : (payload[17] << 8) | payload[18];
  if (expiryDay !== 0 && Date.now() > EPOCH_MS + (expiryDay + 1) * 86400000 - 1) return "คีย์หมดอายุแล้ว";
  return null;
}

/** null = allowed, otherwise a Thai reason (sent back as HTTP 403 body). */
async function checkLicense(url, env) {
  const devHex = (url.searchParams.get("dev") || "").toLowerCase();
  if (!/^[0-9a-f]{16}$/.test(devHex)) return "แอพเวอร์ชันเก่า — กรุณาอัปเดต";
  const lic = url.searchParams.get("lic");
  if (lic) return await verifyKey(lic, devHex);
  if (REVOKED.has(base32(Uint8Array.from(devHex.match(/../g).map((h) => parseInt(h, 16)))))) return "เครื่องนี้ถูกระงับ";
  // no key: free trial counted on the server per device
  const stub = env.ROOM.get(env.ROOM.idFromName("trial-" + devHex));
  const r = await stub.fetch("https://trial/trial");
  const { first } = await r.json();
  if (Date.now() - first > TRIAL_DAYS * 86400000) return "หมดช่วงทดลองใช้ — กรุณาใส่คีย์";
  return null;
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === "/" || url.pathname === "/health") {
      return new Response("JAMES USB Share relay OK\n", { headers: { "content-type": "text/plain" } });
    }
    const m = url.pathname.match(PATH);
    if (!m) return new Response("not found\n", { status: 404 });
    if ((m[1] === "p" && m[3] === "ctl") || (m[1] === "c" && m[3] === "data")) {
      const why = await checkLicense(url, env);
      if (why) return new Response(why, { status: 403, headers: { "content-type": "text/plain; charset=utf-8" } });
    }
    const stub = env.ROOM.get(env.ROOM.idFromName(m[2]));
    return stub.fetch(req);
  },
};

export class Room {
  constructor(state, env) {
    this.state = state;
  }

  async fetch(req) {
    const url = new URL(req.url);
    if (url.pathname === "/trial") {
      let first = await this.state.storage.get("first");
      if (!first) { first = Date.now(); await this.state.storage.put("first", first); }
      return Response.json({ first });
    }
    const [, side, , kind] = url.pathname.split("/");

    if (kind === "status") {
      const c = this.newestCtl();
      let name = "";
      let online = false;
      if (c) {
        const a = c.deserializeAttachment();
        name = (a && a.name) || "";
        // the phone pings every 25 s; no ping for 70 s means its link is stale
        online = !!a && Date.now() - (a.seen || 0) < 70000;
      }
      return Response.json({ online, name });
    }

    if (req.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket\n", { status: 426 });
    }

    const id = url.searchParams.get("id") || "";
    let tag, attachment, notify;

    if (side === "p" && kind === "ctl") {
      for (const old of this.state.getWebSockets("ctl")) {
        try { old.close(4000, "replaced by a newer phone connection"); } catch (e) {}
      }
      tag = "ctl";
      attachment = { role: "ctl", name: (url.searchParams.get("name") || "").slice(0, 80), seen: Date.now(), since: Date.now() };
    } else if (side === "c" && kind === "data") {
      if (!ID.test(id)) return new Response("bad id\n", { status: 400 });
      const c = this.newestCtl();
      const ca = c ? c.deserializeAttachment() : null;
      if (!c || !ca || Date.now() - (ca.seen || 0) > 70000) return new Response("phone offline\n", { status: 503 });
      tag = "pc:" + id;
      attachment = { role: "pc", id };
      notify = () => c.send("open " + id);
    } else if (side === "p" && kind === "data") {
      if (!ID.test(id)) return new Response("bad id\n", { status: 400 });
      const pcs = this.state.getWebSockets("pc:" + id);
      if (!pcs.length) return new Response("pc gone\n", { status: 410 });
      tag = "ph:" + id;
      attachment = { role: "ph", id };
      notify = () => pcs[0].send("ready");
    } else {
      return new Response("bad request\n", { status: 400 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.state.acceptWebSocket(server, [tag]);
    server.serializeAttachment(attachment);
    if (notify) {
      try { notify(); } catch (e) {}
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  /** The phone's most recent control link (older ones may still be closing). */
  newestCtl() {
    let best = null, bestT = -1;
    for (const w of this.state.getWebSockets("ctl")) {
      if (w.readyState !== undefined && w.readyState !== 1) continue; // 1 = OPEN
      const a = w.deserializeAttachment();
      const t = (a && (a.since || a.seen)) || 0;
      if (t > bestT) { best = w; bestT = t; }
    }
    return best;
  }

  peer(ws) {
    const a = ws.deserializeAttachment();
    if (!a) return null;
    if (a.role === "pc") return this.state.getWebSockets("ph:" + a.id)[0] || null;
    if (a.role === "ph") return this.state.getWebSockets("pc:" + a.id)[0] || null;
    return null;
  }

  async webSocketMessage(ws, msg) {
    const a = ws.deserializeAttachment();
    if (!a) return;
    if (a.role === "ctl") {
      if (msg === "ping") {
        a.seen = Date.now();
        ws.serializeAttachment(a);
        ws.send("pong");
      }
      return;
    }
    if (typeof msg === "string") return; // only binary payload is forwarded
    const p = this.peer(ws);
    if (p) {
      try { p.send(msg); } catch (e) { try { ws.close(1011, "peer send failed"); } catch (e2) {} }
    }
  }

  closePeers(ws) {
    const a = ws.deserializeAttachment();
    if (!a) return;
    if (a.role === "ctl") {
      // Losing the control link only stops NEW connections. Data links that are already
      // paired keep working and close on their own; a PC still waiting times out by itself.
      return;
    }
    const p = this.peer(ws);
    if (p) { try { p.close(1000, "peer closed"); } catch (e) {} }
  }

  async webSocketClose(ws, code, reason) {
    this.closePeers(ws);
    try { ws.close(1000, "bye"); } catch (e) {}
  }

  async webSocketError(ws) {
    this.closePeers(ws);
  }
}
