import { normalizeUrl, deriveTitle } from './favorites';
import { getLang, Lang, t } from './i18n';

export interface HistoryItem {
  url: string;
  title: string;
  t: number;
}

export const STORAGE_KEY_HISTORY = 'phantom_history';
export const MAX_HISTORY = 100;

function stripHash(rawUrl: string): string {
  const idx = rawUrl.indexOf('#');
  return idx >= 0 ? rawUrl.slice(0, idx) : rawUrl;
}

export function normalizeHistoryUrl(rawUrl: string): string {
  return normalizeUrl(stripHash(rawUrl));
}

export function isRecordableUrl(rawUrl: string): boolean {
  if (!rawUrl) return false;
  const trimmed = rawUrl.trim();
  if (!/^https?:\/\//i.test(trimmed)) return false;
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return false;
    const host = parsed.hostname.toLowerCase();
    if (!host) return false;
    if (host === 'appassets.androidplatform.net' || host.endsWith('.appassets.androidplatform.net')) {
      return false;
    }
    return true;
  } catch {
    return false;
  }
}

export function formatRelativeTime(timestamp: number, now: number = Date.now(), lang: Lang = getLang()): string {
  const str = t(lang);
  const diffMs = Math.max(0, now - timestamp);
  const mins = Math.floor(diffMs / 60_000);
  if (mins < 1) return str.justNow;
  if (mins < 60) return str.minutesAgo(mins);
  const hours = Math.floor(diffMs / 3_600_000);
  if (hours < 24) return str.hoursAgo(hours);
  const days = Math.floor(diffMs / 86_400_000);
  if (days === 1) return str.yesterday;
  return str.daysAgo(days);
}

function resolveDefaultStorage(): Storage | null {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      return window.localStorage;
    }
  } catch {
    return null;
  }
  try {
    if (typeof localStorage !== 'undefined') {
      return localStorage;
    }
  } catch {
    return null;
  }
  return null;
}

export class HistoryStore {
  private items: HistoryItem[] = [];
  private listeners: Array<() => void> = [];
  private storage: Storage | null;
  private loaded = false;

  constructor(storage?: Storage | null) {
    this.storage = storage !== undefined ? storage : resolveDefaultStorage();
    this.getHistory();
  }

  getHistory(): HistoryItem[] {
    if (!this.storage) {
      return [...this.items];
    }

    try {
      const raw = this.storage.getItem(STORAGE_KEY_HISTORY);
      if (raw !== null) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) {
          this.items = parsed
            .filter(
              (item): item is HistoryItem =>
                !!item && typeof item.url === 'string' && typeof item.title === 'string' && typeof item.t === 'number'
            )
            .slice(0, MAX_HISTORY);
          this.loaded = true;
          return [...this.items];
        }
      }
      if (!this.loaded) {
        this.loaded = true;
      }
    } catch {
      // Storage may be corrupted or inaccessible; keep in-memory items.
    }

    return [...this.items];
  }

  record(url: string, title?: string, now: number = Date.now()): void {
    if (!isRecordableUrl(url)) return;
    const trimmedUrl = url.trim();
    const baseKey = normalizeHistoryUrl(trimmedUrl);
    if (!baseKey) return;

    const fullKey = normalizeUrl(trimmedUrl);
    const current = this.getHistory();
    const existingIdx = current.findIndex((item) => normalizeHistoryUrl(item.url) === baseKey);

    if (existingIdx >= 0) {
      const existing = current[existingIdx];
      // Revisiting an older entry with the title of the page just left: keep the entry's own title.
      const staleRevisit = existingIdx > 0 && !!title && title.trim() === current[0].title;
      const nextTitle = (!staleRevisit && title?.trim()) || existing.title || deriveTitle(trimmedUrl);
      // Only the #fragment differs from the existing entry: update its title without creating/reordering a visit.
      if (normalizeUrl(existing.url) !== fullKey) {
        if (nextTitle !== existing.title) {
          this.items = current.map((item, idx) => (idx === existingIdx ? { ...item, title: nextTitle } : item));
          this.save();
          this.notify();
        }
        return;
      }
      const filtered = current.filter((_, idx) => idx !== existingIdx);
      this.items = [{ url: existing.url, title: nextTitle, t: now }, ...filtered].slice(0, MAX_HISTORY);
    } else {
      // The TV can report a new page with the previous page's title for a moment; treat that
      // as unknown so the host is shown until the real title arrives.
      const prev = current[0];
      const stale = !!prev && !!title && title.trim() === prev.title;
      const resolvedTitle = (!stale && title?.trim()) || deriveTitle(trimmedUrl);
      this.items = [{ url: trimmedUrl, title: resolvedTitle, t: now }, ...current].slice(0, MAX_HISTORY);
    }

    this.save();
    this.notify();
  }

  remove(url: string): void {
    if (!url) return;
    const baseKey = normalizeHistoryUrl(url);
    if (!baseKey) return;
    const current = this.getHistory();
    const next = current.filter((item) => normalizeHistoryUrl(item.url) !== baseKey);
    if (next.length === current.length) return;
    this.items = next;
    this.save();
    this.notify();
  }

  clear(): void {
    this.items = [];
    this.save();
    this.notify();
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
        console.error('HistoryStore listener error', e);
      }
    }
  }

  private save(): void {
    if (!this.storage) return;
    try {
      this.storage.setItem(STORAGE_KEY_HISTORY, JSON.stringify(this.items));
    } catch {
      // Ignore storage quota or private-mode errors.
    }
  }
}
