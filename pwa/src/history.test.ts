import { describe, it, expect, beforeEach } from 'vitest';
import {
  HistoryStore,
  MAX_HISTORY,
  STORAGE_KEY_HISTORY,
  formatRelativeTime,
  isRecordableUrl,
} from './history';

class MockStorage implements Storage {
  private data = new Map<string, string>();
  get length() {
    return this.data.size;
  }
  clear(): void {
    this.data.clear();
  }
  getItem(key: string): string | null {
    return this.data.get(key) ?? null;
  }
  key(index: number): string | null {
    return Array.from(this.data.keys())[index] ?? null;
  }
  removeItem(key: string): void {
    this.data.delete(key);
  }
  setItem(key: string, value: string): void {
    this.data.set(key, value);
  }
}

describe('isRecordableUrl', () => {
  it('accepts http(s) URLs and ignores internal/appassets/about URLs', () => {
    expect(isRecordableUrl('https://www.wikipedia.org')).toBe(true);
    expect(isRecordableUrl('http://example.com/path?a=1')).toBe(true);
    expect(isRecordableUrl('about:blank')).toBe(false);
    expect(isRecordableUrl('https://appassets.androidplatform.net/assets/index.html')).toBe(false);
    expect(isRecordableUrl('loungo-app://unpaired')).toBe(false);
    expect(isRecordableUrl('javascript:void(0)')).toBe(false);
    expect(isRecordableUrl('')).toBe(false);
  });
});

describe('formatRelativeTime', () => {
  it('formats Turkish and English relative times', () => {
    const now = 1_000_000_000;
    expect(formatRelativeTime(now - 20_000, now, 'tr')).toBe('şimdi');
    expect(formatRelativeTime(now - 5 * 60_000, now, 'tr')).toBe('5 dk önce');
    expect(formatRelativeTime(now - 2 * 3_600_000, now, 'tr')).toBe('2 sa önce');
    expect(formatRelativeTime(now - 25 * 3_600_000, now, 'tr')).toBe('dün');
    expect(formatRelativeTime(now - 3 * 86_400_000, now, 'tr')).toBe('3 gün önce');

    expect(formatRelativeTime(now - 20_000, now, 'en')).toBe('just now');
    expect(formatRelativeTime(now - 5 * 60_000, now, 'en')).toBe('5 min ago');
    expect(formatRelativeTime(now - 2 * 3_600_000, now, 'en')).toBe('2 hr ago');
    expect(formatRelativeTime(now - 25 * 3_600_000, now, 'en')).toBe('yesterday');
    expect(formatRelativeTime(now - 3 * 86_400_000, now, 'en')).toBe('3 days ago');
  });
});

describe('HistoryStore', () => {
  let storage: MockStorage;

  beforeEach(() => {
    storage = new MockStorage();
  });

  it('adds http(s) records newest-first and ignores appassets/about URLs', () => {
    const store = new HistoryStore(storage);
    store.record('about:blank', 'Blank', 100);
    store.record('https://appassets.androidplatform.net/welcome.html', 'Welcome', 200);
    store.record('https://www.wikipedia.org', 'Wikipedia', 300);
    store.record('http://example.com', 'Example', 400);

    const list = store.getHistory();
    expect(list).toEqual([
      { url: 'http://example.com', title: 'Example', t: 400 },
      { url: 'https://www.wikipedia.org', title: 'Wikipedia', t: 300 },
    ]);
    expect(JSON.parse(storage.getItem(STORAGE_KEY_HISTORY)!)).toEqual(list);
  });

  it('moves revisited URL to the top and updates its timestamp', () => {
    const store = new HistoryStore(storage);
    store.record('https://www.wikipedia.org', 'Wikipedia', 1000);
    store.record('https://example.com', 'Example Domain', 2000);
    store.record('https://www.wikipedia.org/', 'Wikipedia', 3000);

    const list = store.getHistory();
    expect(list.length).toBe(2);
    expect(list[0]).toEqual({ url: 'https://www.wikipedia.org', title: 'Wikipedia', t: 3000 });
    expect(list[1]).toEqual({ url: 'https://example.com', title: 'Example Domain', t: 2000 });
  });

  it('does not add a new record when only the #fragment changes and updates title', () => {
    const store = new HistoryStore(storage);
    store.record('https://www.youtube.com/watch?v=abc', 'Video Title', 1000);
    store.record('https://www.youtube.com/watch?v=abc#bottom-sheet', 'Comments - Video Title', 2000);

    const list = store.getHistory();
    expect(list.length).toBe(1);
    expect(list[0]).toEqual({
      url: 'https://www.youtube.com/watch?v=abc',
      title: 'Comments - Video Title',
      t: 1000,
    });
  });

  it('enforces 100-item cap by dropping the oldest entries', () => {
    const store = new HistoryStore(storage);
    for (let i = 1; i <= 105; i++) {
      store.record(`https://site${i}.com`, `Site ${i}`, i * 1000);
    }

    const list = store.getHistory();
    expect(list.length).toBe(MAX_HISTORY);
    expect(list[0]).toEqual({ url: 'https://site105.com', title: 'Site 105', t: 105000 });
    expect(list[99]).toEqual({ url: 'https://site6.com', title: 'Site 6', t: 6000 });
  });

  it('removes a single record and clears all records', () => {
    const store = new HistoryStore(storage);
    store.record('https://a.com', 'A', 100);
    store.record('https://b.com', 'B', 200);
    store.record('https://c.com', 'C', 300);

    store.remove('https://b.com/');
    expect(store.getHistory().map((x) => x.title)).toEqual(['C', 'A']);

    store.clear();
    expect(store.getHistory()).toEqual([]);
    expect(JSON.parse(storage.getItem(STORAGE_KEY_HISTORY)!)).toEqual([]);
  });

  it('derives domain title when page title is empty', () => {
    const store = new HistoryStore(storage);
    store.record('https://www.wikipedia.org/wiki/Android_TV', '   ', 500);

    expect(store.getHistory()[0].title).toBe('wikipedia.org');
  });

  it('works without crashing when localStorage is unavailable or throws', () => {
    const nullStore = new HistoryStore(null);
    nullStore.record('https://example.com', 'Example', 100);
    expect(nullStore.getHistory()).toEqual([{ url: 'https://example.com', title: 'Example', t: 100 }]);
    nullStore.remove('https://example.com');
    expect(nullStore.getHistory()).toEqual([]);

    const throwingStorage: Storage = {
      length: 0,
      clear: () => { throw new Error('denied'); },
      getItem: () => { throw new Error('denied'); },
      key: () => null,
      removeItem: () => { throw new Error('denied'); },
      setItem: () => { throw new Error('quota'); },
    };
    const errStore = new HistoryStore(throwingStorage);
    errStore.record('https://example.org', 'Org', 200);
    expect(errStore.getHistory()).toEqual([{ url: 'https://example.org', title: 'Org', t: 200 }]);
    errStore.clear();
    expect(errStore.getHistory()).toEqual([]);
  });

  it('ignores the previous page title reported for a new or revisited page', () => {
    const h = new HistoryStore(new MockStorage());
    h.record('https://www.wikipedia.org/', 'Wikipedia', 1);
    h.record('https://example.com/', 'Example Domain', 2);
    h.record('https://www.wikipedia.org/', 'Example Domain', 3);
    expect(h.getHistory()[0].title).toBe('Wikipedia');
    h.record('https://news.example.org/', 'Wikipedia', 4);
    expect(h.getHistory()[0].title).toBe('news.example.org');
    h.record('https://news.example.org/', 'News', 5);
    expect(h.getHistory()[0].title).toBe('News');
  });
});
