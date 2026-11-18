import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { armConfirm } from './confirm';

describe('armConfirm', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function make() {
    let handler: ((e: unknown) => void) | null = null;
    const attrs: Record<string, string> = {};
    const fake = {
      textContent: 'Clear',
      addEventListener: (_: string, h: (e: unknown) => void) => { handler = h; },
      setAttribute: (k: string, v: string) => { attrs[k] = v; },
      removeAttribute: (k: string) => { delete attrs[k]; },
      click: () => handler?.({ preventDefault() {}, stopPropagation() {} }),
    };
    const action = vi.fn();
    armConfirm(fake as unknown as HTMLButtonElement, 'Sure? Tap again', action, 4000);
    return { btn: fake, action };
  }

  it('first tap arms, second tap runs the action and restores the label', () => {
    const { btn, action } = make();
    btn.click();
    expect(btn.textContent).toBe('Sure? Tap again');
    expect(action).not.toHaveBeenCalled();
    btn.click();
    expect(action).toHaveBeenCalledTimes(1);
    expect(btn.textContent).toBe('Clear');
  });

  it('disarms after the timeout', () => {
    const { btn, action } = make();
    btn.click();
    vi.advanceTimersByTime(4001);
    expect(btn.textContent).toBe('Clear');
    btn.click();
    expect(action).not.toHaveBeenCalled();
    expect(btn.textContent).toBe('Sure? Tap again');
  });
});
