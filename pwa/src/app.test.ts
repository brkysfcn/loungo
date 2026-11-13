import { describe, it, expect } from 'vitest';
import { isInApp } from './app';

describe('isInApp', () => {
  it('detects app=1', () => {
    expect(isInApp('?k=abc&app=1')).toBe(true);
    expect(isInApp('?app=1')).toBe(true);
  });
  it('ignores browsers', () => {
    expect(isInApp('?k=abc')).toBe(false);
    expect(isInApp('?app=10')).toBe(false);
    expect(isInApp('')).toBe(false);
  });
});
