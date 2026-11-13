export interface FavoriteItem {
  url: string;
  title: string;
}

export const STORAGE_KEY_FAVORITES = 'phantom_favorites';
export const MAX_FAVORITES = 30;

export const DEFAULT_FAVORITES: FavoriteItem[] = [
  { url: 'https://www.google.com', title: 'Google' },
  { url: 'https://www.wikipedia.org', title: 'Wikipedia' },
  { url: 'https://www.youtube.com', title: 'YouTube' },
];

export function normalizeUrl(rawUrl: string): string {
  if (!rawUrl) return '';
  const u = rawUrl.trim();
  try {
    const hasProtocol = /^[a-zA-Z]+:\/\//.test(u);
    const parsed = new URL(hasProtocol ? u : `https://${u}`);
    const host = parsed.host.toLowerCase();
    const pathname = parsed.pathname.replace(/\/+$/, '');
    const search = parsed.search;
    const hash = parsed.hash;
    return `${host}${pathname}${search}${hash}`.toLowerCase();
  } catch {
    return u.replace(/^[a-zA-Z]+:\/\//, '').replace(/\/+$/, '').toLowerCase();
  }
}

export function deriveTitle(url: string): string {
  try {
    const hasProtocol = /^[a-zA-Z]+:\/\//.test(url);
    const parsed = new URL(hasProtocol ? url : `https://${url}`);
    return parsed.hostname.replace(/^www\./i, '') || url;
  } catch {
    return url;
  }
}

export class FavoriteStore {
  private favorites: FavoriteItem[] = [];
  private listeners: Array<() => void> = [];
  private storage: Storage | null;

  constructor(storage?: Storage) {
    if (storage) {
      this.storage = storage;
    } else if (typeof window !== 'undefined' && window.localStorage) {
      this.storage = window.localStorage;
    } else if (typeof localStorage !== 'undefined') {
      this.storage = localStorage;
    } else {
      this.storage = null;
    }
    this.getFavorites();
  }

  getFavorites(): FavoriteItem[] {
    if (!this.storage) {
      if (this.favorites.length === 0) {
        this.favorites = [...DEFAULT_FAVORITES];
      }
      return [...this.favorites];
    }

    try {
      const raw = this.storage.getItem(STORAGE_KEY_FAVORITES);
      if (raw !== null) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) {
          this.favorites = parsed.slice(0, MAX_FAVORITES);
          return [...this.favorites];
        }
      }
    } catch (e) {
      console.warn('FavoriteStore load error', e);
    }

    this.favorites = [...DEFAULT_FAVORITES];
    this.save();
    return [...this.favorites];
  }

  isFavorite(url: string): boolean {
    if (!url) return false;
    const targetNorm = normalizeUrl(url);
    if (!targetNorm) return false;
    return this.getFavorites().some((item) => normalizeUrl(item.url) === targetNorm);
  }

  addFavorite(url: string, title?: string): void {
    if (!url || !url.trim()) return;
    const trimmedUrl = url.trim();
    const norm = normalizeUrl(trimmedUrl);
    const current = this.getFavorites();

    const existing = current.find((item) => normalizeUrl(item.url) === norm);
    const resolvedTitle = title?.trim() || existing?.title || deriveTitle(trimmedUrl);

    const filtered = current.filter((item) => normalizeUrl(item.url) !== norm);

    this.favorites = [{ url: trimmedUrl, title: resolvedTitle }, ...filtered].slice(0, MAX_FAVORITES);
    this.save();
    this.notify();
  }

  removeFavorite(url: string): void {
    if (!url) return;
    const norm = normalizeUrl(url);
    const current = this.getFavorites();
    this.favorites = current.filter((item) => normalizeUrl(item.url) !== norm);
    this.save();
    this.notify();
  }

  toggleFavorite(url: string, title?: string): boolean {
    if (this.isFavorite(url)) {
      this.removeFavorite(url);
      return false;
    } else {
      this.addFavorite(url, title);
      return true;
    }
  }

  subscribe(fn: () => void): () => void {
    this.listeners.push(fn);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== fn);
    };
  }

  private notify(): void {
    for (const fn of this.listeners) {
      try {
        fn();
      } catch (e) {
        console.error('FavoriteStore listener error', e);
      }
    }
  }

  private save(): void {
    if (!this.storage) return;
    try {
      this.storage.setItem(STORAGE_KEY_FAVORITES, JSON.stringify(this.favorites));
    } catch (e) {
      console.error('FavoriteStore save error', e);
    }
  }
}
