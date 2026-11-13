import { describe, it, expect } from 'vitest';
import { EdgeSwipe } from './edgeswipe';

describe('EdgeSwipe', () => {
  it('triggers on a quick rightward pull from the left edge', () => {
    const e = new EdgeSwipe();
    expect(e.start(10, 200, 1, 0)).toBe(true);
    expect(e.move(60, 205, 1, 100)).toBeGreaterThan(0);
    expect(e.move(110, 210, 1, 200)).toBe(1);
    expect(e.end()).toBe(true);
  });

  it('ignores touches that do not start at the edge', () => {
    const e = new EdgeSwipe();
    expect(e.start(80, 200, 1, 0)).toBe(false);
    expect(e.move(300, 200, 1, 100)).toBe(-1);
    expect(e.end()).toBe(false);
  });

  it('does not trigger when released before the trigger distance', () => {
    const e = new EdgeSwipe();
    e.start(5, 100, 1, 0);
    e.move(50, 100, 1, 100);
    expect(e.end()).toBe(false);
  });

  it('gives up on vertical movement, slow drags and a second finger', () => {
    const vertical = new EdgeSwipe();
    vertical.start(5, 100, 1, 0);
    expect(vertical.move(20, 200, 1, 50)).toBe(-1);
    expect(vertical.tracking).toBe(false);

    const slow = new EdgeSwipe();
    slow.start(5, 100, 1, 0);
    expect(slow.move(150, 100, 1, 900)).toBe(-1);

    const two = new EdgeSwipe();
    two.start(5, 100, 1, 0);
    expect(two.move(150, 100, 2, 50)).toBe(-1);
    expect(two.end()).toBe(false);
  });

  it('does not start with two fingers', () => {
    expect(new EdgeSwipe().start(5, 100, 2, 0)).toBe(false);
  });
});
