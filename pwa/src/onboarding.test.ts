import { describe, it, expect } from 'vitest';
import {
  detectPlatform, isStandalone, a2hsApplicable, a2hsDue, a2hsDismissNotNow, a2hsDismissOk,
  computeSequence, markSeen, OnboardingRunner, StoreLike, KEY_ONBOARDING, KEY_A2HS_AT, A2HS_RETRY_MS,
  isAndroid, isIos, Step,
} from './onboarding';
import { translations } from './i18n';

const UA = {
  iphoneSafari: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1',
  iphoneChrome: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/123.0.6312.52 Mobile/15E148 Safari/604.1',
  iphoneChromeOld: 'Mozilla/5.0 (iPhone; CPU iPhone OS 15_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/110.0.5481.83 Mobile/15E148 Safari/604.1',
  iphoneEdge: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 EdgiOS/122.0.2365.69 Mobile/15E148 Safari/605.1.15',
  iphoneFirefox: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/124.0 Mobile/15E148 Safari/605.1.15',
  ipadDesktop: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15',
  androidChrome: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.99 Mobile Safari/537.36',
  androidSamsung: 'Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/24.0 Chrome/117.0.0.0 Mobile Safari/537.36',
  androidFirefox: 'Mozilla/5.0 (Android 14; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0',
  androidEdge: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36 EdgA/123.0.2420.65',
  androidOpera: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36 OPR/80.0.4170.63',
  windows: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36',
};

function memStore(): StoreLike & { m: Map<string, string> } {
  const m = new Map<string, string>();
  return {
    m,
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, v),
    removeItem: (k) => void m.delete(k),
  };
}

describe('detectPlatform', () => {
  it('iPhone browsers', () => {
    expect(detectPlatform(UA.iphoneSafari, 5)).toBe('iphone-safari');
    expect(detectPlatform(UA.iphoneChrome, 5)).toBe('iphone-chrome');
    expect(detectPlatform(UA.iphoneEdge, 5)).toBe('iphone-chrome');
    expect(detectPlatform(UA.iphoneFirefox, 5)).toBe('iphone-other');
  });
  it('Chrome on iOS older than 16.4 cannot add to home screen', () => {
    expect(detectPlatform(UA.iphoneChromeOld, 5)).toBe('iphone-other');
  });
  it('iPad with desktop UA counts as iPhone family; a Mac does not', () => {
    expect(detectPlatform(UA.ipadDesktop, 5)).toBe('iphone-safari');
    expect(detectPlatform(UA.ipadDesktop, 0)).toBe('other');
    expect(isIos('iphone-safari')).toBe(true);
  });
  it('Android browsers', () => {
    expect(detectPlatform(UA.androidChrome, 5)).toBe('android-chrome');
    expect(detectPlatform(UA.androidSamsung, 5)).toBe('android-samsung');
    expect(detectPlatform(UA.androidFirefox, 5)).toBe('android-other');
    expect(detectPlatform(UA.androidEdge, 5)).toBe('android-other');
    expect(detectPlatform(UA.androidOpera, 5)).toBe('android-other');
    expect(isAndroid('android-samsung')).toBe(true);
    expect(isAndroid('iphone-safari')).toBe(false);
  });
  it('desktop is unknown', () => {
    expect(detectPlatform(UA.windows, 0)).toBe('other');
    expect(detectPlatform('', 0)).toBe('other');
  });
});

describe('standalone and applicability', () => {
  it('standalone from either signal', () => {
    expect(isStandalone({})).toBe(false);
    expect(isStandalone({ standalone: true })).toBe(true);
    expect(isStandalone({ matchStandalone: true })).toBe(true);
  });
  it('tip only when not standalone and platform known', () => {
    expect(a2hsApplicable('iphone-safari', false)).toBe(true);
    expect(a2hsApplicable('iphone-safari', true)).toBe(false);
    expect(a2hsApplicable('other', false)).toBe(false);
  });
});

describe('a2hs dismissal', () => {
  it('fresh store is due', () => {
    expect(a2hsDue(memStore(), 1000)).toBe(true);
  });
  it('"not now" hides for 7 days, then once more, then never', () => {
    const s = memStore();
    a2hsDismissNotNow(s, 1000);
    expect(s.getItem(KEY_A2HS_AT)).toBe('1000');
    expect(a2hsDue(s, 1000 + A2HS_RETRY_MS - 1)).toBe(false);
    expect(a2hsDue(s, 1000 + A2HS_RETRY_MS)).toBe(true);
    a2hsDismissNotNow(s, 1000 + A2HS_RETRY_MS);
    expect(a2hsDue(s, 1000 + A2HS_RETRY_MS * 10)).toBe(false);
  });
  it('"ok" is permanent', () => {
    const s = memStore();
    a2hsDismissOk(s);
    expect(a2hsDue(s, 1e15)).toBe(false);
  });
});

describe('sequence ordering', () => {
  const base = { platform: 'android-chrome' as const, standalone: false, now: 0 };
  it('a2hs, then trackpad, then balloons', () => {
    expect(computeSequence({ ...base, store: memStore() })).toEqual(['a2hs', 'trackpad', 'balloons']);
  });
  it('standalone or desktop skips a2hs', () => {
    expect(computeSequence({ ...base, standalone: true, store: memStore() })).toEqual(['trackpad', 'balloons']);
    expect(computeSequence({ ...base, platform: 'other', store: memStore() })).toEqual(['trackpad', 'balloons']);
  });
  it('seen flag skips hints but a2hs can still be due', () => {
    const s = memStore();
    markSeen(s);
    expect(computeSequence({ ...base, store: s })).toEqual(['a2hs']);
    a2hsDismissOk(s);
    expect(computeSequence({ ...base, store: s })).toEqual([]);
  });
  it('force replays everything applicable, ignoring flags', () => {
    const s = memStore();
    markSeen(s);
    a2hsDismissOk(s);
    expect(computeSequence({ ...base, store: s, force: true })).toEqual(['a2hs', 'trackpad', 'balloons']);
    expect(computeSequence({ ...base, platform: 'other', store: s, force: true })).toEqual(['trackpad', 'balloons']);
    expect(s.getItem(KEY_ONBOARDING)).toBe('1');
  });
});

describe('OnboardingRunner', () => {
  it('runs steps strictly in order and blocks concurrent runs', async () => {
    const log: string[] = [];
    const resolvers: (() => void)[] = [];
    const r = new OnboardingRunner((s: Step) => {
      log.push('show:' + s);
      return new Promise<void>((res) => resolvers.push(() => { log.push('done:' + s); res(); }));
    });
    const p = r.run(['a2hs', 'trackpad'], (s) => log.push('start:' + s));
    expect(r.isRunning).toBe(true);
    expect(await r.run(['balloons'])).toBe(false);
    expect(log).toEqual(['start:a2hs', 'show:a2hs']);
    resolvers[0]();
    await new Promise((res) => setTimeout(res, 0));
    expect(log).toContain('show:trackpad');
    expect(log.indexOf('done:a2hs')).toBeLessThan(log.indexOf('show:trackpad'));
    resolvers[1]();
    expect(await p).toBe(true);
    expect(r.isRunning).toBe(false);
  });
  it('empty sequence does nothing', async () => {
    const r = new OnboardingRunner(async () => {});
    expect(await r.run([])).toBe(false);
  });
});

describe('onboarding texts', () => {
  it('both languages cover every platform', () => {
    for (const lang of ['tr', 'en'] as const) {
      const o = translations[lang].onboarding;
      for (const p of ['iphone-safari', 'iphone-chrome', 'iphone-other', 'android-chrome', 'android-samsung', 'android-other']) {
        expect((o.a2hsSteps as Record<string, readonly string[]>)[p].length).toBeGreaterThan(0);
        expect((o.a2hsGain as Record<string, string>)[p]).toBeTruthy();
      }
    }
  });
});
