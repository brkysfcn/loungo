import { describe, it, expect } from 'vitest';
import { KeyboardFsm, KeyboardManager } from './keyboard';

describe('KeyboardFsm', () => {
  it('hover açık + tap -> focusHidden', () => {
    const fsm = new KeyboardFsm();
    fsm.onHover(true);
    expect(fsm.isHoverOn()).toBe(true);

    const actions = fsm.onTap();
    expect(actions).toEqual(['focusHidden']);
  });

  it('hover kapalı + tap -> odak yok', () => {
    const fsm = new KeyboardFsm();
    fsm.onHover(false);
    expect(fsm.isHoverOn()).toBe(false);

    const actions = fsm.onTap();
    expect(actions).toEqual([]);
  });

  it('focus on + klavye kapalı -> showYaz', () => {
    const fsm = new KeyboardFsm();
    // When focus message comes with on: true and keyboard is currently closed (!isKeyboardActive)
    const actions = fsm.onFocusMessage(
      { on: true, value: 'current text', input: 'search', enter: 'search' },
      false // keyboard is NOT active
    );

    expect(actions).toContain('showYaz');
    expect(actions).toContain('applyAttributes');
    expect(fsm.isFocusOn()).toBe(true);
    expect(fsm.getFocusInfo()).toEqual({
      value: 'current text',
      input: 'search',
      enter: 'search',
    });
  });

  it('focus off -> blur', () => {
    const fsm = new KeyboardFsm();
    // First simulate field had focus
    fsm.onFocusMessage({ on: true, value: 'test' }, true);
    expect(fsm.isFocusOn()).toBe(true);

    // Now field lost focus (e.g. user clicked away or blur event)
    const actions = fsm.onFocusMessage({ on: false }, true);
    expect(actions).toContain('blur');
    expect(actions).toContain('hideYaz');
    expect(fsm.isFocusOn()).toBe(false);
  });

  it('yaz button click -> focusHidden and hideYaz', () => {
    const fsm = new KeyboardFsm();
    fsm.onFocusMessage({ on: true, value: 'hello' }, false);
    const actions = fsm.onYazClick();
    expect(actions).toEqual(['focusHidden', 'hideYaz']);
  });

  it('KeyboardManager maintains isYazVisible and layout spacing (T50)', () => {
    const parent = {
      appendChild: () => {},
    } as any;
    const mockConn = { sendJson: () => {} } as any;
    const globalDoc = (globalThis as any).document;
    const createdElements: any[] = [];
    (globalThis as any).document = {
      createElement: (tag: string) => {
        const el = {
          tagName: tag,
          id: '',
          style: {} as any,
          textContent: '',
          listeners: {} as Record<string, Function[]>,
          addEventListener: (evt: string, fn: Function) => {
            el.listeners[evt] = el.listeners[evt] || [];
            el.listeners[evt].push(fn);
          },
          setAttribute: () => {},
          appendChild: () => {},
          focus: () => {},
          blur: () => {},
        };
        createdElements.push(el);
        return el;
      },
      activeElement: null,
    };

    const km = new KeyboardManager(parent, mockConn);
    const yazBtn = createdElements.find((e) => e.id === 'yaz-button');
    expect(yazBtn).toBeDefined();
    expect(yazBtn.style.bottom).toContain('20px');
    expect(km.isYazVisible()).toBe(false);

    km.handleFocusMessage({ on: true, value: '' });
    expect(km.isYazVisible()).toBe(true);

    km.handleFocusMessage({ on: false });
    expect(km.isYazVisible()).toBe(false);

    (globalThis as any).document = globalDoc;
  });
});
