import { PhantomConnection } from './conn';

export type KeyboardAction =
  | 'focusHidden'
  | 'blur'
  | 'showYaz'
  | 'hideYaz'
  | 'applyAttributes';

/**
 * Pure state machine for keyboard prediction and focus states.
 */
export class KeyboardFsm {
  private hoverOn = false;
  private focusOn = false;
  private focusInfo: { value?: string; input?: string; enter?: string } = {};

  onHover(on: boolean): void {
    this.hoverOn = on;
  }

  isHoverOn(): boolean {
    return this.hoverOn;
  }

  isFocusOn(): boolean {
    return this.focusOn;
  }

  getFocusInfo() {
    return this.focusInfo;
  }

  onTap(): KeyboardAction[] {
    if (this.hoverOn) {
      return ['focusHidden'];
    }
    return [];
  }

  onFocusMessage(
    msg: { on: boolean; value?: string; input?: string; enter?: string },
    isKeyboardActive: boolean
  ): KeyboardAction[] {
    this.focusOn = msg.on;
    if (msg.on) {
      this.focusInfo = {
        value: msg.value ?? '',
        input: msg.input ?? 'text',
        enter: msg.enter ?? 'go',
      };
      const actions: KeyboardAction[] = ['applyAttributes'];
      if (!isKeyboardActive) {
        actions.push('showYaz');
      }
      return actions;
    } else {
      this.focusInfo = {};
      return ['blur', 'hideYaz'];
    }
  }

  onYazClick(): KeyboardAction[] {
    return ['focusHidden', 'hideYaz'];
  }
}

/**
 * DOM manager for hidden input, Yaz button, and Bitti accessory bar.
 */
export class KeyboardManager {
  public fsm: KeyboardFsm;
  private conn: PhantomConnection;
  private hiddenInput: HTMLInputElement;
  private yazButton: HTMLButtonElement;
  private bittiBar: HTMLDivElement;
  private textDebounceTimer: any = null;

  constructor(container: HTMLElement, conn: PhantomConnection) {
    this.conn = conn;
    this.fsm = new KeyboardFsm();

    // 1. Text field shown in the bar above the phone keyboard. It used to be hidden off-screen,
    // which made the phone's long-press "Paste" menu impossible (and the clipboard API is not
    // available on this plain-http page), so pasted text could not reach page fields on the TV.
    this.hiddenInput = document.createElement('input');
    this.hiddenInput.id = 'phantom-hidden-input';
    this.hiddenInput.type = 'text';
    this.hiddenInput.placeholder = 'Yazın ya da basılı tutup Yapıştır';
    this.hiddenInput.style.flex = '1';
    this.hiddenInput.style.minWidth = '0';
    this.hiddenInput.style.fontSize = '16px'; // 16px prevents iOS auto-zoom
    this.hiddenInput.style.padding = '8px 10px';
    this.hiddenInput.style.background = '#2c2c2e';
    this.hiddenInput.style.color = '#ffffff';
    this.hiddenInput.style.border = '1px solid #3a3a3e';
    this.hiddenInput.style.borderRadius = '8px';
    this.hiddenInput.style.outline = 'none';
    this.hiddenInput.setAttribute('autocomplete', 'off');
    this.hiddenInput.setAttribute('autocorrect', 'off');
    this.hiddenInput.setAttribute('autocapitalize', 'off');
    this.hiddenInput.setAttribute('spellcheck', 'false');

    // 2. Large "Yaz" button at the bottom of the screen
    this.yazButton = document.createElement('button');
    this.yazButton.id = 'yaz-button';
    this.yazButton.textContent = '⌨ Yaz';
    this.yazButton.style.display = 'none';
    this.yazButton.style.position = 'fixed';
    this.yazButton.style.bottom = 'calc(env(safe-area-inset-bottom, 0px) + 20px)';
    this.yazButton.style.left = '50%';
    this.yazButton.style.transform = 'translateX(-50%)';
    this.yazButton.style.padding = '14px 32px';
    this.yazButton.style.background = '#0a84ff';
    this.yazButton.style.color = '#ffffff';
    this.yazButton.style.fontSize = '18px';
    this.yazButton.style.fontWeight = 'bold';
    this.yazButton.style.borderRadius = '28px';
    this.yazButton.style.border = 'none';
    this.yazButton.style.boxShadow = '0 4px 16px rgba(0,0,0,0.6)';
    this.yazButton.style.cursor = 'pointer';
    this.yazButton.style.zIndex = '50';
    this.yazButton.style.touchAction = 'manipulation';
    container.appendChild(this.yazButton);

    // 3. "Bitti" accessory toolbar above keyboard
    this.bittiBar = document.createElement('div');
    this.bittiBar.id = 'bitti-bar';
    this.bittiBar.style.display = 'none';
    this.bittiBar.style.position = 'fixed';
    this.bittiBar.style.bottom = '0';
    this.bittiBar.style.left = '0';
    this.bittiBar.style.width = '100%';
    this.bittiBar.style.padding = '6px 16px';
    this.bittiBar.style.background = '#202024';
    this.bittiBar.style.borderTop = '1px solid #38383e';
    this.bittiBar.style.justifyContent = 'space-between';
    this.bittiBar.style.gap = '10px';
    this.bittiBar.style.alignItems = 'center';
    this.bittiBar.style.boxSizing = 'border-box';
    this.bittiBar.style.zIndex = '60';

    const bittiBtn = document.createElement('button');
    bittiBtn.textContent = 'Bitti';
    bittiBtn.style.background = 'transparent';
    bittiBtn.style.border = 'none';
    bittiBtn.style.color = '#0a84ff';
    bittiBtn.style.fontSize = '16px';
    bittiBtn.style.fontWeight = '600';
    bittiBtn.style.cursor = 'pointer';
    bittiBtn.style.padding = '4px 8px';
    bittiBtn.addEventListener('click', () => {
      this.hiddenInput.blur();
    });
    this.bittiBar.appendChild(this.hiddenInput);
    this.bittiBar.appendChild(bittiBtn);
    container.appendChild(this.bittiBar);

    this.bindEvents();
  }

  public isKeyboardActive(): boolean {
    return document.activeElement === this.hiddenInput;
  }

  public isYazVisible(): boolean {
    return this.yazButton.style.display !== 'none';
  }

  public handleTap(): void {
    const actions = this.fsm.onTap();
    this.applyActions(actions);
  }

  public handleHover(on: boolean): void {
    this.fsm.onHover(on);
  }

  public handleFocusMessage(msg: { on: boolean; value?: string; input?: string; enter?: string }): void {
    const actions = this.fsm.onFocusMessage(msg, this.isKeyboardActive());
    this.applyActions(actions);
  }

  private applyActions(actions: KeyboardAction[]): void {
    for (const a of actions) {
      switch (a) {
        case 'focusHidden':
          // The field lives in the bar; it must be displayed before it can take focus.
          this.bittiBar.style.display = 'flex';
          this.hiddenInput.focus();
          break;
        case 'blur':
          this.hiddenInput.blur();
          break;
        case 'showYaz':
          this.yazButton.style.display = 'block';
          break;
        case 'hideYaz':
          this.yazButton.style.display = 'none';
          break;
        case 'applyAttributes': {
          const info = this.fsm.getFocusInfo();
          if (info.value !== undefined) {
            this.hiddenInput.value = info.value;
          }
          if (info.input) {
            try {
              this.hiddenInput.type = info.input;
            } catch {
              this.hiddenInput.type = 'text';
            }
          }
          if (info.enter) {
            this.hiddenInput.setAttribute('enterkeyhint', info.enter);
          }
          break;
        }
      }
    }
  }

  private bindEvents(): void {
    this.yazButton.addEventListener('click', () => {
      const actions = this.fsm.onYazClick();
      this.applyActions(actions);
    });

    this.hiddenInput.addEventListener('focus', () => {
      this.bittiBar.style.display = 'flex';
      this.yazButton.style.display = 'none';
    });

    this.hiddenInput.addEventListener('blur', () => {
      this.bittiBar.style.display = 'none';
      if (this.fsm.isFocusOn()) {
        this.yazButton.style.display = 'block';
      }
    });

    // 30 ms debounce on input event, covers composition as well
    this.hiddenInput.addEventListener('input', () => {
      if (this.textDebounceTimer) {
        clearTimeout(this.textDebounceTimer);
      }
      this.textDebounceTimer = setTimeout(() => {
        this.conn.sendJson({ t: 'text', value: this.hiddenInput.value });
      }, 30);
    });

    // keydown handling
    this.hiddenInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        this.conn.sendJson({ t: 'key', k: 'Enter' });
      } else if (e.key === 'Backspace' && this.hiddenInput.value.length === 0) {
        this.conn.sendJson({ t: 'key', k: 'Backspace' });
      }
    });
  }
}
