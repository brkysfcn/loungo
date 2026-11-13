import { inApp } from './app';
// Phone-side onboarding: add-to-home-screen tip, trackpad gesture hints, button balloons.
// Top half: pure, unit-tested logic. Bottom half: DOM code (only touches `document` inside functions).
import { t } from './i18n';

export type Platform =
  | 'iphone-safari'
  | 'iphone-chrome'
  | 'iphone-other'
  | 'android-chrome'
  | 'android-samsung'
  | 'android-other'
  | 'other';

export const KEY_ONBOARDING = 'phantom_onboarding_v1';
export const KEY_A2HS_AT = 'phantom_a2hs_dismissed_at';
export const KEY_A2HS_COUNT = 'phantom_a2hs_dismiss_count';
export const KEY_A2HS_DONE = 'phantom_a2hs_done';
export const A2HS_RETRY_MS = 7 * 24 * 60 * 60 * 1000;

export interface StoreLike {
  getItem(k: string): string | null;
  setItem(k: string, v: string): void;
  removeItem(k: string): void;
}

export interface DetectInput {
  userAgent: string;
  maxTouchPoints?: number;
  standalone?: boolean;
}

export function isAndroid(p: Platform): boolean {
  return p.startsWith('android-');
}

export function isIos(p: Platform): boolean {
  return p.startsWith('iphone-');
}

export function detectPlatform(ua: string, maxTouchPoints = 0): Platform {
  const iosDevice = /iPhone|iPad|iPod/.test(ua);
  // iPadOS 13+ sends a desktop (Macintosh) UA; only touch support tells it apart from a Mac.
  const ipadDesktop = /Macintosh/.test(ua) && maxTouchPoints > 1;
  if (iosDevice || ipadDesktop) {
    if (/FxiOS|OPiOS|DuckDuckGo|GSA\/|YaBrowser|Brave/.test(ua)) return 'iphone-other';
    if (/CriOS|EdgiOS/.test(ua)) {
      const m = /OS (\d+)[_.](\d+)/.exec(ua);
      if (m) {
        const major = parseInt(m[1], 10);
        const minor = parseInt(m[2], 10);
        if (major < 16 || (major === 16 && minor < 4)) return 'iphone-other';
      }
      return 'iphone-chrome';
    }
    if (/Safari\//.test(ua) || ipadDesktop) return 'iphone-safari';
    return 'iphone-other'; // in-app web views etc.
  }
  if (/Android/.test(ua)) {
    if (/SamsungBrowser/.test(ua)) return 'android-samsung';
    if (/; wv\)|EdgA|OPR\/|Opera|Firefox|DuckDuckGo|UCBrowser|MiuiBrowser|YaBrowser|Brave/.test(ua)) {
      return 'android-other';
    }
    if (/Chrome\//.test(ua)) return 'android-chrome';
    return 'android-other';
  }
  return 'other';
}

export function isStandalone(i: { standalone?: boolean; matchStandalone?: boolean }): boolean {
  return !!i.standalone || !!i.matchStandalone;
}

// ---- Add-to-home-screen eligibility -------------------------------------------------------

export function a2hsApplicable(platform: Platform, standalone: boolean): boolean {
  return platform !== 'other' && !standalone;
}

/** Normal (non-forced) rule: not done, and "not now" lets it return at most once after 7 days. */
export function a2hsDue(store: StoreLike, now: number): boolean {
  if (store.getItem(KEY_A2HS_DONE)) return false;
  const at = Number(store.getItem(KEY_A2HS_AT));
  if (!store.getItem(KEY_A2HS_AT) || !isFinite(at)) return true;
  const count = Number(store.getItem(KEY_A2HS_COUNT) || '1');
  if (count >= 2) return false;
  return now - at >= A2HS_RETRY_MS;
}

export function a2hsDismissNotNow(store: StoreLike, now: number): void {
  const count = Number(store.getItem(KEY_A2HS_COUNT) || '0');
  store.setItem(KEY_A2HS_AT, String(now));
  store.setItem(KEY_A2HS_COUNT, String(count + 1));
}

export function a2hsDismissOk(store: StoreLike): void {
  store.setItem(KEY_A2HS_DONE, '1');
}

// ---- Sequence -----------------------------------------------------------------------------

export type Step = 'a2hs' | 'trackpad' | 'balloons';

export interface SequenceInput {
  platform: Platform;
  standalone: boolean;
  store: StoreLike;
  now: number;
  /** Replay from settings: ignore stored "seen"/dismissal flags. */
  force?: boolean;
}

export function computeSequence(i: SequenceInput): Step[] {
  const steps: Step[] = [];
  if (a2hsApplicable(i.platform, i.standalone) && (i.force || a2hsDue(i.store, i.now))) {
    steps.push('a2hs');
  }
  if (i.force || !i.store.getItem(KEY_ONBOARDING)) {
    steps.push('trackpad', 'balloons');
  }
  return steps;
}

export function markSeen(store: StoreLike): void {
  store.setItem(KEY_ONBOARDING, '1');
}

export function resetSeen(store: StoreLike): void {
  store.removeItem(KEY_ONBOARDING);
}

/**
 * Runs the steps strictly one after another. `show` resolves when the user dismissed that step.
 * Guards against concurrent runs (e.g. a second `hello` after a reconnect).
 */
export class OnboardingRunner {
  private running = false;
  constructor(private show: (s: Step) => Promise<void>) {}

  get isRunning(): boolean {
    return this.running;
  }

  async run(steps: Step[], onStart?: (s: Step) => void): Promise<boolean> {
    if (this.running || steps.length === 0) return false;
    this.running = true;
    try {
      for (const s of steps) {
        onStart?.(s);
        await this.show(s);
      }
    } finally {
      this.running = false;
    }
    return true;
  }
}

// ---- Fullscreen helpers -------------------------------------------------------------------

export function fullscreenSupported(): boolean {
  return typeof document !== 'undefined' && !!document.documentElement.requestFullscreen && document.fullscreenEnabled !== false;
}

export function inFullscreen(): boolean {
  return typeof document !== 'undefined' && !!document.fullscreenElement;
}

// ===========================================================================================
// DOM part
// ===========================================================================================

const ACCENT = '#0a84ff';
const SVG_NS = 'http://www.w3.org/2000/svg';

function svg(markup: string, size: number): SVGSVGElement {
  const wrap = document.createElement('div');
  wrap.innerHTML = `<svg xmlns="${SVG_NS}" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${markup}</svg>`;
  return wrap.firstChild as SVGSVGElement;
}

// iOS Share icon: square with an arrow pointing up.
const SHARE_ICON = '<path d="M12 15V3M8.5 6.5 12 3l3.5 3.5M7 10H5.5A1.5 1.5 0 0 0 4 11.5v8A1.5 1.5 0 0 0 5.5 21h13a1.5 1.5 0 0 0 1.5-1.5v-8a1.5 1.5 0 0 0-1.5-1.5H17"/>';
const DOTS_ICON = '<circle cx="12" cy="5" r="1.6" fill="currentColor"/><circle cx="12" cy="12" r="1.6" fill="currentColor"/><circle cx="12" cy="19" r="1.6" fill="currentColor"/>';
const BURGER_ICON = '<path d="M4 6h16M4 12h16M4 18h16"/>';

function inlineIcon(kind: 'share' | 'menu', platform: Platform): HTMLElement {
  const box = document.createElement('span');
  box.style.cssText = `display:inline-flex;vertical-align:middle;margin:0 2px;color:${ACCENT}`;
  const markup = kind === 'share' ? SHARE_ICON : platform === 'android-samsung' ? BURGER_ICON : DOTS_ICON;
  box.appendChild(svg(markup, 20));
  return box;
}

function richText(text: string, platform: Platform): DocumentFragment {
  const frag = document.createDocumentFragment();
  for (const part of text.split(/(\{share\}|\{menu\})/)) {
    if (part === '{share}') frag.appendChild(inlineIcon('share', platform));
    else if (part === '{menu}') frag.appendChild(inlineIcon('menu', platform));
    else if (part) frag.appendChild(document.createTextNode(part));
  }
  return frag;
}

function pillButton(label: string, primary: boolean): HTMLButtonElement {
  const b = document.createElement('button');
  b.textContent = label;
  b.style.cssText =
    `flex:1;min-width:0;padding:11px 12px;border-radius:10px;font-size:15px;font-weight:600;cursor:pointer;` +
    (primary
      ? `background:${ACCENT};color:#fff;border:1px solid ${ACCENT};`
      : 'background:#2c2c2e;color:#fff;border:1px solid #3a3a3c;');
  return b;
}

export function makeFullscreenButton(label: string): HTMLButtonElement {
  const b = pillButton('⛶ ' + label, false);
  b.id = 'btn-fullscreen';
  b.style.flex = 'none';
  const update = () => {
    b.style.display = fullscreenSupported() && !inFullscreen() ? '' : 'none';
  };
  b.addEventListener('click', () => {
    try {
      const p = document.documentElement.requestFullscreen();
      if (p && typeof p.catch === 'function') p.catch(() => {});
    } catch {
      // unsupported
    }
  });
  document.addEventListener('fullscreenchange', update);
  update();
  return b;
}

const FONT = '-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif';

function showA2hsTip(platform: Platform, store: StoreLike, now: () => number, force: boolean): Promise<void> {
  const s = t().onboarding;
  return new Promise((resolve) => {
    const wrap = document.createElement('div');
    wrap.id = 'a2hs-tip';
    wrap.style.cssText =
      'position:fixed;left:0;right:0;bottom:0;z-index:150;display:flex;justify-content:center;' +
      'padding:12px 12px calc(12px + env(safe-area-inset-bottom)) 12px;box-sizing:border-box;' +
      'padding-left:calc(12px + env(safe-area-inset-left));padding-right:calc(12px + env(safe-area-inset-right));';
    const card = document.createElement('div');
    card.style.cssText =
      `background:#1c1c1e;border:1px solid #3a3a3c;border-radius:14px;padding:16px;width:100%;max-width:380px;` +
      `box-sizing:border-box;color:#fff;font-family:${FONT};box-shadow:0 8px 30px rgba(0,0,0,.6);`;
    const title = document.createElement('div');
    title.textContent = s.a2hsTitle;
    title.style.cssText = 'font-size:17px;font-weight:700;margin-bottom:8px';
    card.appendChild(title);

    const steps = (s.a2hsSteps as Record<string, readonly string[]>)[platform] ?? [];
    steps.forEach((txt, idx) => {
      const row = document.createElement('div');
      row.style.cssText = 'font-size:15px;line-height:1.5;margin:4px 0;color:#e5e5ea';
      row.appendChild(document.createTextNode(`${idx + 1}. `));
      row.appendChild(richText(txt, platform));
      card.appendChild(row);
    });
    const gain = document.createElement('div');
    gain.textContent = (s.a2hsGain as Record<string, string>)[platform] ?? '';
    gain.style.cssText = 'font-size:13px;color:#8e8e93;margin-top:8px';
    card.appendChild(gain);

    if (isAndroid(platform)) {
      const note = document.createElement('div');
      note.textContent = s.androidFsNote;
      note.style.cssText = 'font-size:13px;color:#8e8e93;margin-top:6px';
      card.appendChild(note);
      const fsRow = document.createElement('div');
      fsRow.style.cssText = 'margin-top:10px;display:flex';
      const fs = makeFullscreenButton(s.fullscreen);
      fs.style.flex = '1';
      fsRow.appendChild(fs);
      card.appendChild(fsRow);
    }

    const btns = document.createElement('div');
    btns.style.cssText = 'display:flex;gap:8px;margin-top:12px';
    const later = pillButton(s.notNow, false);
    const ok = pillButton(s.ok, true);
    btns.appendChild(later);
    btns.appendChild(ok);
    card.appendChild(btns);
    wrap.appendChild(card);
    document.body.appendChild(wrap);

    const close = () => {
      wrap.remove();
      resolve();
    };
    later.addEventListener('click', () => {
      if (!force) a2hsDismissNotNow(store, now());
      close();
    });
    ok.addEventListener('click', () => {
      a2hsDismissOk(store);
      close();
    });
  });
}

function gestureCard(label: string, drawing: string): HTMLElement {
  const card = document.createElement('div');
  card.style.cssText = 'display:flex;flex-direction:column;align-items:center;gap:6px;text-align:center;min-width:0';
  const icon = document.createElement('div');
  icon.style.color = '#fff';
  icon.appendChild(svg(drawing, 56));
  const txt = document.createElement('div');
  txt.textContent = label;
  txt.style.cssText = 'font-size:13px;line-height:1.3;color:#e5e5ea';
  card.appendChild(icon);
  card.appendChild(txt);
  return card;
}

// Simple finger drawings (a filled dot = fingertip, arrow = motion).
const G_MOVE =
  '<circle cx="9" cy="15" r="3" fill="currentColor"/><path d="M13 11l6-6M14 5h5v5"/>';
const G_TAP =
  '<circle cx="12" cy="12" r="3" fill="currentColor"/><circle cx="12" cy="12" r="7" opacity=".5"/><circle cx="12" cy="12" r="10" opacity=".25"/>';
const G_SCROLL =
  '<circle cx="8" cy="13" r="2.6" fill="currentColor"/><circle cx="16" cy="13" r="2.6" fill="currentColor"/><path d="M8 8V3M5.5 5.5 8 3l2.5 2.5M16 8V3M13.5 5.5 16 3l2.5 2.5"/>';
const G_BACK =
  '<path d="M3 3v18" stroke-width="2.4"/><circle cx="8" cy="12" r="2.6" fill="currentColor"/><path d="M12 12h9M18 9l3 3-3 3"/>';

function stopTouch(el: HTMLElement): void {
  // Keep the underlying Trackpad from turning the dismiss tap into a cursor move or click.
  for (const ev of ['touchstart', 'touchmove', 'touchend', 'touchcancel', 'mousedown', 'pointerdown']) {
    el.addEventListener(ev, (e) => e.stopPropagation());
  }
}

function showTrackpadHints(trackpad: HTMLElement, hideWhileOpen: HTMLElement[] = []): Promise<void> {
  const s = t().onboarding;
  return new Promise((resolve) => {
    const ov = document.createElement('div');
    ov.id = 'trackpad-hints';
    ov.style.cssText =
      'position:absolute;top:0;left:0;right:0;bottom:0;z-index:30;background:rgba(0,0,0,.82);' +
      `display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;padding:16px;` +
      `box-sizing:border-box;font-family:${FONT};cursor:pointer;`;
    const title = document.createElement('div');
    title.textContent = s.gestureTitle;
    title.style.cssText = 'font-size:16px;font-weight:700;color:#fff';
    const grid = document.createElement('div');
    grid.style.cssText = 'display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px 12px;width:100%;max-width:320px';
    grid.appendChild(gestureCard(s.gMove, G_MOVE));
    grid.appendChild(gestureCard(s.gTap, G_TAP));
    grid.appendChild(gestureCard(s.gScroll, G_SCROLL));
    // In the Android app the system back gesture does this (edge swipe is off there).
    grid.appendChild(gestureCard(inApp ? s.gBackApp : s.gBack, G_BACK));
    const hint = document.createElement('div');
    hint.textContent = s.tapToContinue;
    hint.style.cssText = `font-size:13px;color:${ACCENT}`;
    ov.appendChild(title);
    ov.appendChild(grid);
    ov.appendChild(hint);
    stopTouch(ov);
    // Floating controls (volume) would show through the dimmed layer and sit over the text.
    const prev = hideWhileOpen.map((el) => el.style.visibility);
    hideWhileOpen.forEach((el) => (el.style.visibility = 'hidden'));
    ov.addEventListener('click', (e) => {
      e.stopPropagation();
      ov.remove();
      hideWhileOpen.forEach((el, i) => (el.style.visibility = prev[i]));
      resolve();
    });
    trackpad.appendChild(ov);
  });
}

function showBalloon(target: HTMLElement, text: string): Promise<void> {
  const s = t().onboarding;
  return new Promise((resolve) => {
    const layer = document.createElement('div');
    layer.className = 'onboarding-balloon';
    layer.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:160;background:rgba(0,0,0,.45);';
    const r = target.getBoundingClientRect();
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const bubble = document.createElement('div');
    bubble.style.cssText =
      `position:absolute;background:${ACCENT};color:#fff;border-radius:12px;padding:10px 12px;` +
      `font-family:${FONT};font-size:14px;line-height:1.35;box-sizing:border-box;` +
      `max-width:${Math.min(240, vw - 24)}px;box-shadow:0 6px 20px rgba(0,0,0,.5);`;
    const msg = document.createElement('div');
    msg.textContent = text;
    const sub = document.createElement('div');
    sub.textContent = s.tapToContinue;
    sub.style.cssText = 'font-size:11px;opacity:.8;margin-top:4px';
    bubble.appendChild(msg);
    bubble.appendChild(sub);

    // Highlight ring around the target.
    const ring = document.createElement('div');
    ring.style.cssText =
      `position:absolute;left:${r.left - 4}px;top:${r.top - 4}px;width:${r.width + 8}px;height:${r.height + 8}px;` +
      `border:2px solid ${ACCENT};border-radius:10px;box-sizing:border-box;pointer-events:none;`;
    layer.appendChild(ring);
    layer.appendChild(bubble);
    document.body.appendChild(layer);

    const bw = bubble.offsetWidth || 200;
    const bh = bubble.offsetHeight || 60;
    const centerX = r.left + r.width / 2;
    const below = r.bottom + 12 + bh < vh || r.top - 12 - bh < 0;
    const top = below ? r.bottom + 12 : r.top - 12 - bh;
    const left = Math.max(12, Math.min(vw - bw - 12, centerX - bw / 2));
    bubble.style.top = `${Math.max(8, top)}px`;
    bubble.style.left = `${left}px`;
    // Arrow pointing at the target.
    const arrow = document.createElement('div');
    const ax = Math.max(14, Math.min(bw - 14, centerX - left));
    arrow.style.cssText =
      `position:absolute;${below ? 'top:-6px' : 'bottom:-6px'};left:${ax - 6}px;width:12px;height:12px;` +
      `background:${ACCENT};transform:rotate(45deg);`;
    bubble.appendChild(arrow);

    layer.addEventListener('click', () => {
      layer.remove();
      resolve();
    });
  });
}

export interface OnboardingDeps {
  trackpad: HTMLElement;
  /** Balloon targets in order: URL input, volume controls, gear button. */
  urlInput: HTMLElement;
  volControls: HTMLElement;
  gearBtn: HTMLElement;
  store?: StoreLike;
  now?: () => number;
}

export interface OnboardingController {
  /** Start after the connection is up. No-op if nothing is due or a run is in progress. */
  start(): void;
  /** Settings "Usage tips": clears the seen flag and replays everything applicable. */
  replay(): void;
  /** The TV tour is running: show the trackpad hints and balloons again (A2HS tip only if due). */
  fromTv(): void;
}

export function createOnboarding(deps: OnboardingDeps): OnboardingController {
  const store: StoreLike = deps.store ?? safeStorage();
  const now = deps.now ?? Date.now;
  let force = false;
  const runner = new OnboardingRunner(async (step) => {
    if (step === 'a2hs') await showA2hsTip(detect().platform, store, now, force);
    else if (step === 'trackpad') await showTrackpadHints(deps.trackpad, [deps.volControls]);
    else {
      await showBalloon(deps.urlInput, t().onboarding.balloonUrl);
      await showBalloon(deps.volControls, t().onboarding.balloonVol);
      await showBalloon(deps.gearBtn, t().onboarding.balloonGear);
    }
  });

  function detect(): { platform: Platform; standalone: boolean } {
    const nav = navigator as Navigator & { standalone?: boolean };
    let mm = false;
    try {
      mm = !!window.matchMedia?.('(display-mode: standalone)').matches;
    } catch {
      // ignore
    }
    return {
      platform: detectPlatform(nav.userAgent || '', nav.maxTouchPoints || 0),
      // Inside the Loungo app there is nothing to add to the home screen.
      standalone: inApp || isStandalone({ standalone: nav.standalone, matchStandalone: mm }),
    };
  }

  function go(): void {
    const d = detect();
    const steps = computeSequence({ ...d, store, now: now(), force });
    void runner.run(steps, (s) => {
      if (s === 'trackpad') markSeen(store);
    });
  }

  return {
    start() {
      if (runner.isRunning) return;
      force = false;
      go();
    },
    replay() {
      if (runner.isRunning) return;
      resetSeen(store);
      force = true;
      go();
    },
    fromTv() {
      if (runner.isRunning) return;
      resetSeen(store);
      force = false;
      go();
    },
  };
}

function safeStorage(): StoreLike {
  try {
    const ls = window.localStorage;
    ls.getItem(KEY_ONBOARDING);
    return ls;
  } catch {
    const mem = new Map<string, string>();
    return {
      getItem: (k) => mem.get(k) ?? null,
      setItem: (k, v) => void mem.set(k, v),
      removeItem: (k) => void mem.delete(k),
    };
  }
}
