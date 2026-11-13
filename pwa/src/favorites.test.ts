import { describe, it, expect, beforeEach } from 'vitest';
import {
  FavoriteStore,
  DEFAULT_FAVORITES,
  STORAGE_KEY_FAVORITES,
  MAX_FAVORITES,
  normalizeUrl,
} from './favorites';

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

describe('normalizeUrl', () => {
  it('handles protocols and trailing slashes correctly', () => {
    expect(normalizeUrl('https://www.example.com/')).toBe('www.example.com');
    expect(normalizeUrl('https://www.example.com')).toBe('www.example.com');
    expect(normalizeUrl('http://www.example.com/')).toBe('www.example.com');
    expect(normalizeUrl('http://www.example.com')).toBe('www.example.com');
    expect(normalizeUrl('www.example.com/')).toBe('www.example.com');
    expect(normalizeUrl('www.example.com')).toBe('www.example.com');
  });

  it('normalizes paths with and without trailing slash', () => {
    expect(normalizeUrl('https://example.com/category/action/')).toBe('example.com/category/action');
    expect(normalizeUrl('https://example.com/category/action')).toBe('example.com/category/action');
    expect(normalizeUrl('http://example.com/category/action/')).toBe('example.com/category/action');
  });

  it('handles case-insensitivity', () => {
    expect(normalizeUrl('HTTPS://Example.COM/')).toBe('example.com');
  });

  it('handles empty string', () => {
    expect(normalizeUrl('')).toBe('');
    expect(normalizeUrl('   ')).toBe('');
  });
});

describe('FavoriteStore', () => {
  let storage: MockStorage;

  beforeEach(() => {
    storage = new MockStorage();
  });

  it('returns default 3 sites when storage is empty and saves them', () => {
    const store = new FavoriteStore(storage);
    const favs = store.getFavorites();

    expect(favs).toEqual(DEFAULT_FAVORITES);
    expect(favs.length).toBe(3);
    expect(favs[0]).toEqual({ url: 'https://www.google.com', title: 'Google' });
    expect(favs[1]).toEqual({ url: 'https://www.wikipedia.org', title: 'Wikipedia' });
    expect(favs[2]).toEqual({ url: 'https://www.youtube.com', title: 'YouTube' });

    // Verify written to storage
    const storedRaw = storage.getItem(STORAGE_KEY_FAVORITES);
    expect(storedRaw).not.toBeNull();
    expect(JSON.parse(storedRaw!)).toEqual(DEFAULT_FAVORITES);
  });

  it('handles isFavorite with URL normalization (protocol & trailing slash)', () => {
    const store = new FavoriteStore(storage);

    // Default sites should be recognized regardless of protocol and trailing slash
    expect(store.isFavorite('https://www.google.com')).toBe(true);
    expect(store.isFavorite('https://www.google.com/')).toBe(true);
    expect(store.isFavorite('http://www.google.com/')).toBe(true);
    expect(store.isFavorite('http://www.google.com')).toBe(true);
    expect(store.isFavorite('www.google.com')).toBe(true);

    expect(store.isFavorite('https://www.wikipedia.org/')).toBe(true);
    expect(store.isFavorite('http://www.wikipedia.org')).toBe(true);

    expect(store.isFavorite('https://non-favorite-site.org')).toBe(false);
  });

  it('adds a favorite to the beginning and removes it properly', () => {
    const store = new FavoriteStore(storage);

    store.addFavorite('https://youtube.com', 'YouTube');
    const favs = store.getFavorites();
    expect(favs.length).toBe(4);
    expect(favs[0]).toEqual({ url: 'https://youtube.com', title: 'YouTube' });
    expect(store.isFavorite('https://youtube.com')).toBe(true);
    expect(store.isFavorite('http://youtube.com/')).toBe(true);

    // Remove favorite
    store.removeFavorite('https://youtube.com/');
    expect(store.isFavorite('https://youtube.com')).toBe(false);
    expect(store.getFavorites().length).toBe(3);
  });

  it('moves existing favorite to front if re-added', () => {
    const store = new FavoriteStore(storage);
    // Wikipedia is at index 1 initially
    expect(store.getFavorites()[1].url).toBe('https://www.wikipedia.org');

    store.addFavorite('https://www.wikipedia.org/', 'Wikipedia VIP');
    const favs = store.getFavorites();
    expect(favs.length).toBe(3); // no duplicate
    expect(favs[0].url).toBe('https://www.wikipedia.org/');
    expect(favs[0].title).toBe('Wikipedia VIP');
  });

  it('toggles favorite status (remove if exists -> false, add if not -> true)', () => {
    const store = new FavoriteStore(storage);

    // Add new site via toggle
    const added = store.toggleFavorite('https://github.com', 'GitHub');
    expect(added).toBe(true);
    expect(store.isFavorite('https://github.com')).toBe(true);
    expect(store.getFavorites()[0].url).toBe('https://github.com');

    // Remove existing site via toggle
    const removed = store.toggleFavorite('https://github.com/');
    expect(removed).toBe(false);
    expect(store.isFavorite('https://github.com')).toBe(false);

    // Toggle default favorite
    const removedDefault = store.toggleFavorite('http://www.wikipedia.org');
    expect(removedDefault).toBe(false);
    expect(store.isFavorite('https://www.wikipedia.org')).toBe(false);
  });

  it('enforces 30 item limit by dropping the oldest when 31st item is added', () => {
    const store = new FavoriteStore(storage);
    // Initial 3 items
    expect(store.getFavorites().length).toBe(3);

    // Add 27 more items to reach exactly 30
    for (let i = 1; i <= 27; i++) {
      store.addFavorite(`https://site${i}.com`, `Site ${i}`);
    }
    expect(store.getFavorites().length).toBe(30);

    // The oldest item was the last default favorite: YouTube
    const listAt30 = store.getFavorites();
    expect(listAt30[29].url).toBe('https://www.youtube.com');

    // Add 31st item: newest should be at index 0, and YouTube should be dropped
    store.addFavorite('https://overflow-site.com', 'Overflow Site');
    const listAt31 = store.getFavorites();

    expect(listAt31.length).toBe(MAX_FAVORITES); // 30
    expect(listAt31[0]).toEqual({ url: 'https://overflow-site.com', title: 'Overflow Site' });
    expect(store.isFavorite('https://www.youtube.com')).toBe(false);
    expect(store.isFavorite('https://overflow-site.com')).toBe(true);
  });

  it('notifies subscribers on change', () => {
    const store = new FavoriteStore(storage);
    let notified = 0;
    const unsubscribe = store.subscribe(() => {
      notified++;
    });

    store.addFavorite('https://test.com', 'Test');
    expect(notified).toBe(1);

    store.toggleFavorite('https://test.com');
    expect(notified).toBe(2);

    unsubscribe();
    store.addFavorite('https://test2.com', 'Test 2');
    expect(notified).toBe(2);
  });
});
