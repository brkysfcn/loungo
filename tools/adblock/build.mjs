// tools/adblock/build.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const HOSTS_URL = 'https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts';
const HOSTS_FILE = path.join(__dirname, 'hosts.txt');
const OUT_BIN = path.join(__dirname, '../../app/src/main/assets/adblock.bin');

export const FNV_OFFSET_BASIS = 0xcbf29ce484222325n;
export const FNV_PRIME = 0x100000001b3n;
export const MASK64 = 0xffffffffffffffffn;

export function fnv1a64(str) {
  const bytes = Buffer.from(str, 'utf8');
  let hash = FNV_OFFSET_BASIS;
  for (let i = 0; i < bytes.length; i++) {
    hash = ((hash ^ BigInt(bytes[i])) * FNV_PRIME) & MASK64;
  }
  return BigInt.asIntN(64, hash); // signed 64-bit representation
}

async function getHostsContent() {
  if (fs.existsSync(HOSTS_FILE)) {
    console.log(`Using cached hosts file: ${HOSTS_FILE}`);
    return fs.readFileSync(HOSTS_FILE, 'utf8');
  }
  console.log(`Downloading hosts from ${HOSTS_URL}...`);
  const res = await fetch(HOSTS_URL);
  if (!res.ok) {
    throw new Error(`Failed to download hosts: ${res.status} ${res.statusText}`);
  }
  const text = await res.text();
  fs.writeFileSync(HOSTS_FILE, text, 'utf8');
  console.log(`Saved hosts to ${HOSTS_FILE} (${text.length} chars)`);
  return text;
}

async function main() {
  const content = await getHostsContent();
  const lines = content.split(/\r?\n/);
  const domainSet = new Set();

  const IGNORED = new Set([
    'localhost',
    'localhost.localdomain',
    'local',
    'broadcasthost',
    '0.0.0.0',
    '127.0.0.1',
    '::1',
    'ip6-localhost',
    'ip6-loopback',
  ]);

  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    // Format: 0.0.0.0 domain.com
    const parts = trimmed.split(/\s+/);
    if (parts.length >= 2 && (parts[0] === '0.0.0.0' || parts[0] === '127.0.0.1')) {
      const d = parts[1].toLowerCase().trim();
      if (d && !IGNORED.has(d) && !d.startsWith('#')) {
        domainSet.add(d);
      }
    }
  }

  // Ensure test domains from blocklist_min are included
  domainSet.add('ads.example');
  domainSet.add('doubleclick.net');
  domainSet.add('googlesyndication.com');
  domainSet.add('adservice.google.com');
  domainSet.add('popads.net');
  domainSet.add('propellerads.com');
  domainSet.add('exoclick.com');

  console.log(`Unique domains parsed: ${domainSet.size}`);

  // Compute FNV-1a 64 hashes
  const hashMap = new Map();
  for (const d of domainSet) {
    const h = fnv1a64(d);
    hashMap.set(h, d);
  }

  const hashes = Array.from(hashMap.keys());
  // Sort in ascending signed 64-bit order
  hashes.sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));

  console.log(`Unique 64-bit hashes: ${hashes.length}`);

  // Create binary file: 'PBL1' (4 bytes) + count (u32 LE) + hashes (count * 8 bytes LE)
  const totalBytes = 8 + hashes.length * 8;
  const buf = Buffer.alloc(totalBytes);
  buf.write('PBL1', 0, 4, 'ascii');
  buf.writeUInt32LE(hashes.length, 4);

  let offset = 8;
  for (const h of hashes) {
    buf.writeBigInt64LE(h, offset);
    offset += 8;
  }

  fs.mkdirSync(path.dirname(OUT_BIN), { recursive: true });
  fs.writeFileSync(OUT_BIN, buf);

  console.log(`Wrote binary blocklist to: ${OUT_BIN}`);
  console.log(`File size: ${totalBytes} bytes (${(totalBytes / 1024).toFixed(1)} KB)`);

  // Test vectors for JVM test
  const testDomains = ['doubleclick.net', 'ads.example', 'googlesyndication.com'];
  console.log('\n--- 3 Test Vectors for Kotlin JVM Test ---');
  for (const td of testDomains) {
    const h = fnv1a64(td);
    console.log(`domain="${td}" -> fnv1a64=${h.toString()}L (0x${(BigInt.asUintN(64, h)).toString(16).padStart(16, '0')})`);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
