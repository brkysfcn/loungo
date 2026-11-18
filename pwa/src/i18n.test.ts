import { describe, it, expect } from 'vitest';
import { getLang, t } from './i18n';

describe('PWA i18n tests', () => {
  it('selects Turkish when language starts with tr', () => {
    expect(getLang('tr-TR')).toBe('tr');
    expect(getLang('tr')).toBe('tr');
    expect(t('tr').settings).toBe('Ayarlar');
    expect(t('tr').reportIssue).toBe('Sorun bildir');
  });

  it('selects English for non-Turkish languages', () => {
    expect(getLang('en-US')).toBe('en');
    expect(getLang('de')).toBe('en');
    expect(getLang('')).toBe('en');
    expect(t('en').settings).toBe('Settings');
    expect(t('en').reportIssue).toBe('Report issue');
  });

  it('formats parameterized strings in both languages', () => {
    expect(t('tr').adblockBlocked(5)).toBe('Bu sayfada 5 istek engellendi');
    expect(t('en').adblockBlocked(5)).toBe('5 requests blocked on this page');
    expect(t('tr').minutesAgo(5)).toBe('5 dk önce');
    expect(t('en').minutesAgo(5)).toBe('5 min ago');
  });

  it('has identical translation keys in tr and en', () => {
    expect(Object.keys(t('tr')).sort()).toEqual(Object.keys(t('en')).sort());
    expect(Object.keys(t('tr').onboarding).sort()).toEqual(Object.keys(t('en').onboarding).sort());
  });
});
