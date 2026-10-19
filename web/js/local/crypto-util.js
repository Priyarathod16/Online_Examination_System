// Password hashing and random numbers for the standalone mode.
// Uses PBKDF2-HMAC-SHA256 from the browser's Web Crypto API; falls back to an iterated SHA-256 written in JavaScript
// when Web Crypto is not available (for example on some insecure origins).

let iterations = 100000;

/** Tests use a small number of iterations to stay fast. */
export function setIterations(n) { iterations = n; }

function bytesToB64(bytes) {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

function b64ToBytes(b64) {
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

export function randomBytes(n) {
  const out = new Uint8Array(n);
  (globalThis.crypto || {}).getRandomValues ? globalThis.crypto.getRandomValues(out) : out.forEach((_, i) => { out[i] = Math.floor(Math.random() * 256); });
  return out;
}

let randomOverride = null;
/** Tests can inject a deterministic generator returning floats in [0, 1). */
export function setRandomSource(fn) { randomOverride = fn; }

/** Uniform random integer in [0, n) without modulo bias. */
export function randomInt(n) {
  if (randomOverride) return Math.floor(randomOverride() * n);
  const limit = Math.floor(4294967296 / n) * n;
  const buf = new Uint32Array(1);
  for (;;) {
    if (globalThis.crypto && globalThis.crypto.getRandomValues) globalThis.crypto.getRandomValues(buf);
    else buf[0] = Math.floor(Math.random() * 4294967296);
    if (buf[0] < limit) return buf[0] % n;
  }
}

// ---- compact SHA-256 (fallback only)
const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
  0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
  0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
  0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2]);

function sha256(data) {
  const l = data.length;
  const total = ((l + 9 + 63) >> 6) << 6;
  const m = new Uint8Array(total);
  m.set(data);
  m[l] = 0x80;
  const bits = l * 8;
  m[total - 4] = (bits >>> 24) & 255; m[total - 3] = (bits >>> 16) & 255; m[total - 2] = (bits >>> 8) & 255; m[total - 1] = bits & 255;
  const H = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
  const w = new Uint32Array(64);
  const rotr = (x, n) => (x >>> n) | (x << (32 - n));
  for (let off = 0; off < total; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = (m[off + 4 * i] << 24) | (m[off + 4 * i + 1] << 16) | (m[off + 4 * i + 2] << 8) | m[off + 4 * i + 3];
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }
    let [a, b, c, d, e, f, g, hh] = H;
    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const ch = (e & f) ^ (~e & g);
      const t1 = (hh + S1 + ch + K[i] + w[i]) | 0;
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + maj) | 0;
      hh = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    H[0] += a; H[1] += b; H[2] += c; H[3] += d; H[4] += e; H[5] += f; H[6] += g; H[7] += hh;
  }
  const out = new Uint8Array(32);
  for (let i = 0; i < 8; i++) { out[4 * i] = H[i] >>> 24; out[4 * i + 1] = (H[i] >>> 16) & 255; out[4 * i + 2] = (H[i] >>> 8) & 255; out[4 * i + 3] = H[i] & 255; }
  return out;
}

export { sha256 as sha256Fallback };

async function derive(password, salt, rounds, mode) {
  const enc = new TextEncoder().encode(password);
  if (mode === 'pbkdf2' && globalThis.crypto && globalThis.crypto.subtle) {
    const key = await globalThis.crypto.subtle.importKey('raw', enc, 'PBKDF2', false, ['deriveBits']);
    return new Uint8Array(await globalThis.crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: rounds }, key, 256));
  }
  let h = sha256(new Uint8Array([...salt, ...enc]));
  for (let i = 0; i < Math.min(rounds, 20000); i++) h = sha256(new Uint8Array([...h, ...salt, ...enc]));
  return h;
}

export async function hashPassword(password) {
  const salt = randomBytes(16);
  const mode = globalThis.crypto && globalThis.crypto.subtle ? 'pbkdf2' : 'sha256x';
  const out = await derive(password, salt, iterations, mode);
  return mode + '$' + iterations + '$' + bytesToB64(salt) + '$' + bytesToB64(out);
}

export async function verifyPassword(password, stored) {
  if (typeof password !== 'string' || typeof stored !== 'string') return false;
  const parts = stored.split('$');
  if (parts.length !== 4 || (parts[0] !== 'pbkdf2' && parts[0] !== 'sha256x')) return false;
  try {
    const rounds = Number(parts[1]);
    const expected = b64ToBytes(parts[3]);
    const actual = await derive(password, b64ToBytes(parts[2]), rounds, parts[0]);
    if (actual.length !== expected.length) return false;
    let diff = 0;
    for (let i = 0; i < actual.length; i++) diff |= actual[i] ^ expected[i];
    return diff === 0;
  } catch (e) {
    return false;
  }
}
