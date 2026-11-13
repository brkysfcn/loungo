import { describe, it, expect } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import {
  encodeMove,
  encodeScroll,
  encodeButton,
  splitDelta,
  decodeThumb,
  parseIn
} from './protocol';

describe('Protocol Tests', () => {
  const vectorsPath = path.resolve(__dirname, '../../docs/protocol_vectors.json');
  const vectors = JSON.parse(fs.readFileSync(vectorsPath, 'utf8'));

  it('should test vectors correctly', () => {
    for (const v of vectors) {
      const hex = v.hex;
      const fields = v.fields;

      if (v.kind === 'move') {
        const buf = encodeMove(fields.dx, fields.dy);
        const bytes = new Uint8Array(buf);
        expect(Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('')).toBe(hex);
      } else if (v.kind === 'scroll') {
        const buf = encodeScroll(fields.sx, fields.sy);
        const bytes = new Uint8Array(buf);
        expect(Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('')).toBe(hex);
      } else if (v.kind === 'button') {
        const buf = encodeButton(fields.down, fields.btn);
        const bytes = new Uint8Array(buf);
        expect(Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('')).toBe(hex);
      } else if (v.kind === 'thumb') {
        // decode thumb test
        const bytes = new Uint8Array(hex.match(/.{1,2}/g)!.map((byte: string) => parseInt(byte, 16)));
        const decoded = decodeThumb(bytes.buffer);
        expect(decoded).not.toBeNull();
        expect(decoded?.reqId).toBe(fields.reqId);
        const jpegHex = Array.from(decoded!.jpeg).map(b => b.toString(16).padStart(2, '0')).join('');
        expect(jpegHex).toBe(fields.jpegHex);
      }
    }
  });

  it('should split delta correctly', () => {
    expect(splitDelta(40000)).toEqual([32767, 7233]);
    expect(splitDelta(-40000)).toEqual([-32768, -7232]);
    expect(splitDelta(100)).toEqual([100]);
  });

  it('should parse In JSON messages correctly', () => {
    const hello = parseIn(JSON.stringify({ t: 'hello', v: 1, w: 1920, h: 1080, dpr: 1, ua: 'test' }));
    expect(hello?.t).toBe('hello');

    const state = parseIn(JSON.stringify({ t: 'state', url: 'http://a.com', title: 'A', canBack: true, canFwd: false, loading: false, progress: 100 }));
    expect(state?.t).toBe('state');

    const editable = parseIn(JSON.stringify({ t: 'editable', on: true }));
    expect(editable?.t).toBe('editable');

    const snap = parseIn(JSON.stringify({ t: 'snap', reqId: 1, url: 'http://a.com', title: 'A', scrollY: 0 }));
    expect(snap?.t).toBe('snap');

    const popup = parseIn(JSON.stringify({ t: 'popup', id: '1', url: 'http://a.com', gesture: true }));
    expect(popup?.t).toBe('popup');

    const popupGone = parseIn(JSON.stringify({ t: 'popup_gone', id: '1', reason: 'timeout' }));
    expect(popupGone?.t).toBe('popup_gone');

    const fullscreen = parseIn(JSON.stringify({ t: 'fullscreen', on: true }));
    expect(fullscreen?.t).toBe('fullscreen');

    const media = parseIn(JSON.stringify({ t: 'media', url: 'http://a.com', kind: 'mp4' }));
    expect(media?.t).toBe('media');

    const player = parseIn(JSON.stringify({ t: 'player', on: true, playing: true, pos: 1000, dur: 5000, title: 'Video' }));
    expect(player?.t).toBe('player');

    const err = parseIn(JSON.stringify({ t: 'err', code: 'version', msg: 'bad version' }));
    expect(err?.t).toBe('err');

    const pong = parseIn(JSON.stringify({ t: 'pong' }));
    expect(pong?.t).toBe('pong');

    const logs = parseIn(JSON.stringify({ t: 'logs', lines: ['line1', 'line2'] }));
    expect(logs?.t).toBe('logs');
    if (logs && logs.t === 'logs') {
      expect(logs.lines).toHaveLength(2);
    }

    expect(parseIn('invalid json')).toBeNull();
    expect(parseIn(JSON.stringify({ t: 'unknown' }))).toBeNull();
  });
});
