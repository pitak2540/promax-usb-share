// PROMAX USB Share relay — pairs a phone and a PC by a pairing code so USB/IP
// can cross networks (mobile data, different Wi-Fi, NAT). Each USB/IP TCP
// connection from the PC becomes one WebSocket pair through a Durable Object.
//
// Licensing model: the "master" (เครื่องแม่) is the licensed PC that attaches devices; its
// CODE is its device id. Any number of "children" (เครื่องลูก: phones / PCs that only
// share USB, no key needed) join the master's room by typing that code.
//
//   child control : wss://<relay>/p/<CODE>/ctl?name=<name>&dev=<child id>   (kept open, many per room)
//   PC connection : wss://<relay>/c/<CODE>/data?id=<ID>&child=<child id>&lic=..&dev=..
//       relay -> child control: "open <ID>"
//   child data    : wss://<relay>/p/<CODE>/data?id=<ID>
//       relay -> PC: "ready", then binary frames are forwarded both ways.
//   P2P (optional): the PC adds &p2p=<offer>, the relay sends "open <ID> <offer>" to children
//       that connected with &p2p=1; the child answers with &p2p=<answer> -> PC gets "ready <answer>".
//   Resume (v2 links, &v=2): when one side's WebSocket drops, the other side is kept for
//       RESUME_MS; the dropped side reconnects to the same id with &resume=1, both get
//       "ready" again and exchange "resume <n>" (forwarded verbatim) to replay lost frames.
//       Each side is told the other's protocol version with "peer <v>" just before "ready".
//       "ping" on a data link is answered with "pong"; "bye" closes the peer at once.
//       Close codes to a v2 side: 4001 = replaced by your own resumed link, 4002 = session over.
//   status        : https://<relay>/c/<CODE>/status
//       -> {"online":true,"name":"...","children":[{"id":"..","name":"..","online":true}]}

const PATH = /^\/(p|c)\/([A-Z0-9]{6,16})\/(ctl|data|status)$/;
const ID = /^[A-Za-z0-9]{4,32}$/;
// P2P offer/answer: "<token hex16>-<ip:port>_<ip:port>..." (IPv4 literals only). The relay just
// passes addresses between master and child; the two then connect directly over UDP.
const P2P = /^[0-9a-f]{16}-[0-9.:_]{0,400}$/;
const RESUME_MS = 30000; // how long a half-open session waits for the dropped side to come back

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
      return new Response("PROMAX USB Share relay OK\n", { headers: { "content-type": "text/plain" } });
    }
    const m = url.pathname.match(PATH);
    if (!m) return new Response("not found\n", { status: 404 });
    if (m[1] === "p" && m[3] === "ctl") {
      // children share for free; only a revoked device is refused
      const d = (url.searchParams.get("dev") || "").toLowerCase();
      if (/^[0-9a-f]{16}$/.test(d) && REVOKED.has(base32(Uint8Array.from(d.match(/../g).map((h) => parseInt(h, 16))))))
        return new Response("เครื่องนี้ถูกระงับ", { status: 403, headers: { "content-type": "text/plain; charset=utf-8" } });
    }
    if (m[1] === "c" && m[3] === "data") {
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
    this.grace = new Map(); // tag -> timer closing the peer if that side does not come back
  }

  /** Open socket with this tag, if any (closing ones are skipped). */
  live(tag) {
    return this.state.getWebSockets(tag).find((w) => w.readyState === undefined || w.readyState === 1) || null;
  }

  version(ws) {
    const a = ws && ws.deserializeAttachment();
    return (a && a.v) || 1;
  }

  /** Both sides are present: tell each the other's version, then "ready". */
  pairUp(pc, ph, answer) {
    try { if (this.version(pc) >= 2) pc.send("peer " + this.version(ph)); } catch (e) {}
    try { if (this.version(ph) >= 2) ph.send("peer " + this.version(pc)); } catch (e) {}
    try { pc.send(answer ? "ready " + answer : "ready"); } catch (e) {}
    try { ph.send("ready"); } catch (e) {}
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
      const children = this.children();
      const on = children.filter((c) => c.online);
      const first = on[0] || children[0];
      return Response.json({ online: on.length > 0, name: first ? first.name : "", children });
    }

    if (req.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket\n", { status: 426 });
    }

    const id = url.searchParams.get("id") || "";
    const v = Math.max(1, Math.min(9, parseInt(url.searchParams.get("v") || "1", 10) || 1));
    const resume = url.searchParams.get("resume") === "1";
    let tag, attachment, notify;

    if (side === "p" && kind === "ctl") {
      let dev = (url.searchParams.get("dev") || "").toLowerCase();
      if (!/^[0-9a-f]{16}$/.test(dev)) dev = "anon";
      // the same child reconnecting replaces its old link; other children stay
      for (const old of this.state.getWebSockets("ctl")) {
        const oa = old.deserializeAttachment();
        if (oa && (oa.dev || "anon") === dev) {
          try { old.close(4000, "replaced by a newer connection from this device"); } catch (e) {}
        }
      }
      tag = "ctl";
      attachment = { role: "ctl", dev, p2p: url.searchParams.get("p2p") === "1", name: (url.searchParams.get("name") || "").slice(0, 80), seen: Date.now(), since: Date.now() };
    } else if (side === "c" && kind === "data") {
      if (!ID.test(id)) return new Response("bad id\n", { status: 400 });
      tag = "pc:" + id;
      attachment = { role: "pc", id, v };
      if (resume) {
        // the PC's link dropped and is back: pair with the child's link if it is still there,
        // otherwise wait for the child to resume as well (webSocketClose gave it RESUME_MS)
        const old = this.live(tag);
        if (old) { try { old.close(4001, "replaced by a resumed link"); } catch (e) {} }
        const ph = this.live("ph:" + id);
        if (ph) notify = (me) => this.pairUp(me, ph, null);
      } else {
        const child = (url.searchParams.get("child") || "").toLowerCase() || null;
        const c = this.newestCtl(child);
        const ca = c ? c.deserializeAttachment() : null;
        if (!c || !ca || Date.now() - (ca.seen || 0) > 70000) return new Response("phone offline\n", { status: 503 });
        const offer = url.searchParams.get("p2p") || "";
        notify = () => c.send("open " + id + (ca.p2p && P2P.test(offer) ? " " + offer : ""));
      }
    } else if (side === "p" && kind === "data") {
      if (!ID.test(id)) return new Response("bad id\n", { status: 400 });
      tag = "ph:" + id;
      attachment = { role: "ph", id, v };
      const old = this.live(tag);
      if (old) { try { old.close(4001, "replaced by a resumed link"); } catch (e) {} }
      const pc = this.live("pc:" + id);
      if (!pc && !resume) return new Response("pc gone\n", { status: 410 });
      const answer = url.searchParams.get("p2p") || "";
      if (pc) notify = (me) => this.pairUp(pc, me, !resume && P2P.test(answer) ? answer : null);
    } else {
      return new Response("bad request\n", { status: 400 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.state.acceptWebSocket(server, [tag]);
    server.serializeAttachment(attachment);
    const g = this.grace.get(tag);
    if (g) { clearTimeout(g); this.grace.delete(tag); }
    if (notify) {
      try { notify(server); } catch (e) {}
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  /** Every child in this room (newest link per device); online = pinged in the last 70 s. */
  children() {
    const by = new Map();
    for (const w of this.state.getWebSockets("ctl")) {
      if (w.readyState !== undefined && w.readyState !== 1) continue;
      const a = w.deserializeAttachment();
      if (!a) continue;
      const id = a.dev || "anon";
      const prev = by.get(id);
      if (!prev || (a.since || 0) > prev.since)
        by.set(id, { id, name: a.name || "", online: Date.now() - (a.seen || 0) < 70000, since: a.since || 0 });
    }
    return [...by.values()].sort((x, y) => x.since - y.since).map(({ id, name, online }) => ({ id, name, online }));
  }

  /** Most recent control link (of one child, or of any child when child is null). */
  newestCtl(child) {
    let best = null, bestT = -1;
    for (const w of this.state.getWebSockets("ctl")) {
      if (w.readyState !== undefined && w.readyState !== 1) continue; // 1 = OPEN
      const a = w.deserializeAttachment();
      if (child && (!a || (a.dev || "anon") !== child)) continue;
      const t = (a && (a.since || a.seen)) || 0;
      if (t > bestT) { best = w; bestT = t; }
    }
    return best;
  }

  peer(ws) {
    const a = ws.deserializeAttachment();
    if (!a) return null;
    if (a.role === "pc") return this.live("ph:" + a.id);
    if (a.role === "ph") return this.live("pc:" + a.id);
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
    if (typeof msg === "string") {
      if (msg === "ping") { try { ws.send("pong"); } catch (e) {} return; }
      if (msg === "bye") { // the local USB/IP side closed: this session is over for both
        const p = this.peer(ws);
        if (p) { try { p.close(4002, "peer closed"); } catch (e) {} } // 4002 = final, do not resume
        try { ws.close(1000, "bye"); } catch (e) {}
        return;
      }
      if (!msg.startsWith("resume ")) return; // only the resume handshake crosses the relay
    }
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
    const tag = (a.role === "pc" ? "pc:" : "ph:") + a.id;
    const peerTag = (a.role === "pc" ? "ph:" : "pc:") + a.id;
    const replacement = this.live(tag);
    if (replacement && replacement !== ws) return; // a resumed link already took over
    const p = this.peer(ws);
    if (!p) return;
    if ((a.v || 1) < 2 || this.version(p) < 2) {
      try { p.close(1000, "peer closed"); } catch (e) {} // old protocol: no resume possible
      return;
    }
    // v2: keep the other side for a while so the dropped side can resume into the same session
    const old = this.grace.get(tag);
    if (old) clearTimeout(old);
    this.grace.set(tag, setTimeout(() => {
      this.grace.delete(tag);
      if (this.live(tag)) return; // it came back
      const peer = this.live(peerTag);
      if (peer) { try { peer.close(4002, "peer gone"); } catch (e) {} }
    }, RESUME_MS));
  }

  async webSocketClose(ws, code, reason) {
    this.closePeers(ws);
    try { ws.close(1000, "bye"); } catch (e) {}
  }

  async webSocketError(ws) {
    this.closePeers(ws);
  }
}
