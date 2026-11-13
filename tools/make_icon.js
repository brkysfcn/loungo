const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

// CRC32 table
const crcTable = new Uint32Array(256);
for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
        c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    }
    crcTable[n] = c;
}

function crc32(buf) {
    let c = 0xffffffff;
    for (let i = 0; i < buf.length; i++) {
        c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
    }
    return (c ^ 0xffffffff) >>> 0;
}

function makeChunk(type, data) {
    const len = data.length;
    const chunk = Buffer.alloc(12 + len);
    chunk.writeUInt32BE(len, 0);
    chunk.write(type, 4, 4, 'ascii');
    data.copy(chunk, 8);
    const typeAndData = chunk.subarray(4, 8 + len);
    chunk.writeUInt32BE(crc32(typeAndData), 8 + len);
    return chunk;
}

const width = 180;
const height = 180;

// Raw RGBA scanlines: 1 filter byte + width * 4 bytes per scanline
const rowSize = 1 + width * 4;
const raw = Buffer.alloc(rowSize * height);

for (let y = 0; y < height; y++) {
    const rowOffset = y * rowSize;
    raw[rowOffset] = 0; // Filter: None
    for (let x = 0; x < width; x++) {
        const px = rowOffset + 1 + x * 4;
        
        // Background: #141416 with rounded aesthetic
        raw[px] = 20;     // R
        raw[px + 1] = 20; // G
        raw[px + 2] = 22; // B
        raw[px + 3] = 255;// A

        // Draw cursor pointer:
        // Tip at (55, 45)
        const rx = x - 55;
        const ry = y - 45;

        // Mouse pointer arrow geometry:
        // Primary triangle: ry >= 0 && rx >= 0 && ry >= rx && ry <= 90 && rx <= ry * 0.55
        // Tail: ...
        const inMain = ry >= 0 && rx >= 0 && rx <= 60 && ry <= 90 && (rx <= ry * 0.58) && (ry - rx <= 45);
        const inTail = rx >= 20 && rx <= 36 && ry >= 45 && ry <= 95 && Math.abs((ry - 45) - (rx - 20) * 2.2) <= 12;

        if (inMain || inTail) {
            // Accent color: #0a84ff / white
            raw[px] = 10;
            raw[px + 1] = 132;
            raw[px + 2] = 255;
            raw[px + 3] = 255;
        }

        // Inner white highlight
        const inInnerMain = ry >= 6 && rx >= 3 && rx <= 45 && ry <= 72 && (rx <= ry * 0.52) && (ry - rx <= 36);
        const inInnerTail = rx >= 23 && rx <= 31 && ry >= 50 && ry <= 88 && Math.abs((ry - 45) - (rx - 20) * 2.2) <= 6;
        if (inInnerMain || inInnerTail) {
            raw[px] = 255;
            raw[px + 1] = 255;
            raw[px + 2] = 255;
            raw[px + 3] = 255;
        }
    }
}

const compressed = zlib.deflateSync(raw);

// PNG signature
const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

// IHDR
const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(width, 0);
ihdr.writeUInt32BE(height, 4);
ihdr[8] = 8; // bit depth
ihdr[9] = 6; // color type: RGBA
ihdr[10] = 0; // compression
ihdr[11] = 0; // filter
ihdr[12] = 0; // interlace

const outDir = path.join(__dirname, '..', 'pwa', 'public');
fs.mkdirSync(outDir, { recursive: true });

const png = Buffer.concat([
    sig,
    makeChunk('IHDR', ihdr),
    makeChunk('IDAT', compressed),
    makeChunk('IEND', Buffer.alloc(0))
]);

const outFile = path.join(outDir, 'apple-touch-icon.png');
fs.writeFileSync(outFile, png);
console.log('PNG generated:', outFile, png.length, 'bytes');
console.log('Magic bytes:', png.subarray(0, 4).toString('hex').toUpperCase());
