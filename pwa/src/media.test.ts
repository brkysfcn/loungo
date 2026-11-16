import { describe, it, expect } from 'vitest';
import { shouldReplaceMedia } from './media';

describe('shouldReplaceMedia', () => {
  it('takes the first media of a page', () => {
    expect(shouldReplaceMedia(null, 'mp4')).toBe(true);
  });
  it('replaces a placeholder mp4 with a later manifest', () => {
    expect(shouldReplaceMedia('mp4', 'dash')).toBe(true);
    expect(shouldReplaceMedia('mp4', 'hls')).toBe(true);
  });
  it('keeps a manifest once found', () => {
    expect(shouldReplaceMedia('hls', 'mp4')).toBe(false);
    expect(shouldReplaceMedia('dash', 'hls')).toBe(false);
  });
  it('keeps the first plain file', () => {
    expect(shouldReplaceMedia('mp4', 'mp4')).toBe(false);
  });
});
