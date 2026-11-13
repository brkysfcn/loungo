import { describe, it, expect, vi, beforeEach } from 'vitest';
import { Trackpad } from './trackpad';

describe('Trackpad touch feedback (T28f)', () => {
  beforeEach(() => {
    (globalThis as any).requestAnimationFrame = vi.fn(() => 1);
    (globalThis as any).cancelAnimationFrame = vi.fn();
    const listeners: Record<string, ((e: any) => void)[]> = {};
    const mockElement: any = {
      addEventListener: vi.fn((event: string, cb: (e: any) => void) => {
        listeners[event] = listeners[event] || [];
        listeners[event].push(cb);
      }),
      getBoundingClientRect: () => ({ left: 0, top: 0, width: 400, height: 600 }),
      appendChild: vi.fn(),
      children: [],
    };
    (globalThis as any).document = {
      createElement: (tag: string) => ({
        tagName: tag,
        className: '',
        style: {},
        remove: vi.fn(),
      }),
    };
    (globalThis as any).window = {
      addEventListener: vi.fn((event: string, cb: (e: any) => void) => {
        listeners[event] = listeners[event] || [];
        listeners[event].push(cb);
      }),
    };
    (globalThis as any).listeners = listeners;
    (globalThis as any).mockElement = mockElement;
  });

  it('creates feedback ring element on mousedown and hides on mouseup', () => {
    const mockElement = (globalThis as any).mockElement;
    const listeners = (globalThis as any).listeners;
    const mockConn = { sendBinary: vi.fn(), sendText: vi.fn() } as any;

    const trackpad = new Trackpad(mockElement, mockConn);

    const mousedownCbs = listeners['mousedown'] || [];
    expect(mousedownCbs.length).toBeGreaterThan(0);

    mousedownCbs[0]({ button: 0, clientX: 100, clientY: 100 });
    expect(mockElement.appendChild).toHaveBeenCalledTimes(1);

    const appended = mockElement.appendChild.mock.calls[0][0];
    expect(appended.className).toBe('touch-ring');
    expect(appended.style.width).toBe('44px');
    expect(appended.style.height).toBe('44px');
    expect(appended.style.borderRadius).toBe('50%');

    const mouseupCbs = listeners['mouseup'] || [];
    expect(mouseupCbs.length).toBeGreaterThan(0);
    mouseupCbs[0]();

    expect(appended.style.opacity).toBe('0');
    trackpad.destroy();
  });
});
