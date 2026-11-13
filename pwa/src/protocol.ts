export interface SubtitleItem {
  url: string;
  lang: string;
}

export interface TrackItem {
  id: string;
  label?: string;
  lang?: string;
  height?: number;
  bitrate?: number;
}

export type In =
  | { t: 'hello'; v: number; w: number; h: number; dpr: number; ua: string; pwa?: string; lite?: 'auto' | 'on' | 'off'; tour?: boolean }
  | { t: 'state'; url: string; title: string; canBack: boolean; canFwd: boolean; loading: boolean; progress: number; vol?: { level: number; max: number; muted: boolean } }
  | { t: 'editable'; on: boolean; value?: string; input?: string; enter?: string }
  | { t: 'snap'; reqId: number; url: string; title: string; scrollY: number }
  | { t: 'popup'; id: string; url: string; gesture: boolean }
  | { t: 'popup_gone'; id: string; reason: string }
  | { t: 'fullscreen'; on: boolean }
  | { t: 'media'; url: string; kind: string; subs?: SubtitleItem[] }
  | {
      t: 'player';
      on: boolean;
      playing?: boolean;
      pos?: number;
      dur?: number;
      resume?: number;
      title?: string;
      tracks?: {
        audio?: TrackItem[];
        text?: TrackItem[];
        video?: TrackItem[];
      };
      sel?: {
        audio?: string;
        text?: string;
        video?: string;
      };
    }
  | { t: 'err'; code: string; msg: string }
  | { t: 'logs'; lines: string[] }
  | { t: 'cleared' }
  | { t: 'tips' }
  | { t: 'pong' };

export type Out =
  | { t: 'hello'; v: number; client: string; token?: string }
  | { t: 'nav'; url: string }
  | { t: 'back' }
  | { t: 'fwd' }
  | { t: 'reload' }
  | { t: 'stop' }
  | { t: 'text'; value: string }
  | { t: 'key'; k: string }
  | { t: 'tab'; url: string; scrollY: number }
  | { t: 'snap'; reqId: number }
  | { t: 'zoom'; text: number }
  | { t: 'ua'; mode: string }
  | { t: 'popup'; id: string; action: string }
  | { t: 'media_open'; url: string }
  | { t: 'player_cmd'; a: 'play' | 'pause' | 'seek' | 'close' | 'track'; ms?: number; type?: 'audio' | 'text' | 'video'; id?: string }
  | { t: 'vol'; a: 'up' | 'down' | 'mute' }
  | { t: 'pointer'; x: number; y: number }
  | { t: 'diag'; rtt?: { p50: number; p95: number; max: number; lost: number }; view?: { dpr: number; innerW: number; innerH: number; screenW: number; screenH: number; vvScale: number; vvW: number; vvH: number; standalone: boolean } }
  | { t: 'adblock'; on: boolean }
  | { t: 'adblock_update' }
  | { t: 'reset_pair' }
  | { t: 'get_logs' }
  | { t: 'clear_data' }
  | { t: 'lite'; mode: 'auto' | 'on' | 'off' }
  | { t: 'ping' };

export function encodeMove(dx: number, dy: number): ArrayBuffer {
  const buf = new ArrayBuffer(5);
  const view = new DataView(buf);
  view.setUint8(0, 0x01);
  view.setInt16(1, dx, true);
  view.setInt16(3, dy, true);
  return buf;
}

export function encodeScroll(sx: number, sy: number): ArrayBuffer {
  const buf = new ArrayBuffer(5);
  const view = new DataView(buf);
  view.setUint8(0, 0x02);
  view.setInt16(1, sx, true);
  view.setInt16(3, sy, true);
  return buf;
}

export function encodeButton(down: boolean, btn: number): ArrayBuffer {
  const buf = new ArrayBuffer(3);
  const view = new DataView(buf);
  view.setUint8(0, 0x03);
  view.setUint8(1, down ? 1 : 0);
  view.setUint8(2, btn);
  return buf;
}

export function splitDelta(d: number): number[] {
  const res: number[] = [];
  while (d > 32767 || d < -32768) {
    if (d > 0) {
      res.push(32767);
      d -= 32767;
    } else {
      res.push(-32768);
      d -= -32768;
    }
  }
  res.push(d);
  return res;
}

export function decodeThumb(buf: ArrayBuffer): { reqId: number; jpeg: Uint8Array } | null {
  if (buf.byteLength < 3) return null;
  const view = new DataView(buf);
  const op = view.getUint8(0);
  if (op !== 0x20) return null;
  const reqId = view.getUint16(1, true);
  const jpeg = new Uint8Array(buf, 3);
  return { reqId, jpeg };
}

export function parseIn(s: string): In | null {
  try {
    const obj = JSON.parse(s);
    if (!obj || typeof obj.t !== 'string') return null;
    switch (obj.t) {
      case 'hello':
      case 'state':
      case 'editable':
      case 'snap':
      case 'popup':
      case 'popup_gone':
      case 'fullscreen':
      case 'media':
      case 'player':
      case 'err':
      case 'logs':
      case 'cleared':
      case 'tips':
      case 'pong':
        return obj as In;
      default:
        return null;
    }
  } catch {
    return null;
  }
}
