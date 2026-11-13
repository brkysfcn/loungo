import { describe, it, expect, vi, beforeEach } from 'vitest';
import { formatTime, PlayerPanel } from './player';

describe('formatTime', () => {
  it('formats 0 ms', () => {
    expect(formatTime(0)).toBe('0:00');
  });

  it('formats 59 seconds', () => {
    expect(formatTime(59000)).toBe('0:59');
  });

  it('formats 1 minute', () => {
    expect(formatTime(60000)).toBe('1:00');
  });

  it('formats mm:ss (e.g. 12:34)', () => {
    expect(formatTime(12 * 60 * 1000 + 34 * 1000)).toBe('12:34');
  });

  it('formats 1 hour', () => {
    expect(formatTime(3600000)).toBe('1:00:00');
  });

  it('formats > 1 hour (e.g. 1:45:00)', () => {
    const ms = (1 * 3600 + 45 * 60) * 1000;
    expect(formatTime(ms)).toBe('1:45:00');
  });

  it('handles negative or invalid inputs gracefully', () => {
    expect(formatTime(-1000)).toBe('0:00');
    expect(formatTime(NaN)).toBe('0:00');
  });
});

class MockElement {
  public tagName: string;
  public id: string = '';
  public style: any = {};
  public children: MockElement[] = [];
  public textContent: string = '';
  public innerHTML: string = '';
  public value: string = '0';
  public type: string = '';
  public min: string = '0';
  public max: string = '100';
  public listeners: Record<string, ((e?: any) => void)[]> = {};
  public onclick: ((e?: any) => void) | null = null;
  public onchange: ((e?: any) => void) | null = null;

  constructor(tag: string) {
    this.tagName = tag;
  }

  appendChild(child: MockElement) {
    this.children.push(child);
  }

  addEventListener(event: string, cb: (e?: any) => void) {
    this.listeners[event] = this.listeners[event] || [];
    this.listeners[event].push(cb);
  }

  querySelector(sel: string): MockElement | null {
    const id = sel.startsWith('#') ? sel.slice(1) : sel;
    const find = (el: MockElement): MockElement | null => {
      if (el.id === id) return el;
      for (const c of el.children) {
        const found = find(c);
        if (found) return found;
      }
      return null;
    };
    return find(this);
  }

  click() {
    this.onclick?.();
  }
}

describe('PlayerPanel', () => {
  let mockConn: any;
  let parent: any;

  beforeEach(() => {
    (globalThis as any).document = {
      createElement: (tag: string) => new MockElement(tag),
    };
    mockConn = {
      sendJson: vi.fn(),
      sendBinary: vi.fn(),
    };
    parent = new MockElement('div');
  });

  it('shows panel on player.on=true and hides on player.on=false', () => {
    const panel = new PlayerPanel(parent, mockConn);
    const el = panel.getElement();
    expect(el.style.display).toBe('none');

    panel.update({ on: true, playing: true, pos: 1000, dur: 10000, title: 'Test Video' });
    expect(el.style.display).toBe('flex');

    panel.update({ on: false });
    expect(el.style.display).toBe('none');
  });

  it('calls onVisibilityChange when panel visibility toggles (T50)', () => {
    const onVisibilityChange = vi.fn();
    const panel = new PlayerPanel(parent, mockConn, onVisibilityChange);
    expect(onVisibilityChange).not.toHaveBeenCalled();

    panel.update({ on: true, playing: true });
    expect(onVisibilityChange).toHaveBeenCalledWith(true);

    panel.update({ on: false });
    expect(onVisibilityChange).toHaveBeenCalledWith(false);
  });

  it('sends player_cmd close when close button is clicked', () => {
    const panel = new PlayerPanel(parent, mockConn);
    const closeBtn = panel.getElement().querySelector('#player-btn-close') as any;
    closeBtn.click();
    expect(mockConn.sendJson).toHaveBeenCalledWith({ t: 'player_cmd', a: 'close' });
  });

  it('sends player_cmd play/pause when playPauseBtn is clicked', () => {
    const panel = new PlayerPanel(parent, mockConn);
    panel.update({ on: true, playing: false });
    const btn = panel.getElement().querySelector('#player-btn-playpause') as any;

    btn.click();
    expect(mockConn.sendJson).toHaveBeenCalledWith({ t: 'player_cmd', a: 'play' });

    panel.update({ on: true, playing: true });
    btn.click();
    expect(mockConn.sendJson).toHaveBeenCalledWith({ t: 'player_cmd', a: 'pause' });
  });

  it('sends seek commands for -10s and +10s', () => {
    const panel = new PlayerPanel(parent, mockConn);
    panel.update({ on: true, pos: 30000, dur: 120000 });

    const backBtn = panel.getElement().querySelector('#player-btn-back10') as any;
    backBtn.click();
    expect(mockConn.sendJson).toHaveBeenCalledWith({ t: 'player_cmd', a: 'seek', ms: 20000 });

    const fwdBtn = panel.getElement().querySelector('#player-btn-fwd10') as any;
    fwdBtn.click();
    expect(mockConn.sendJson).toHaveBeenCalledWith({ t: 'player_cmd', a: 'seek', ms: 40000 });
  });

  it('sends vol commands from PlayerPanel', () => {
    const panel = new PlayerPanel(parent, mockConn);
    const upBtn = panel.getElement().querySelector('#player-vol-up') as any;
    upBtn.click();
    expect(mockConn.sendJson).toHaveBeenCalledWith({ t: 'vol', a: 'up' });

    const downBtn = panel.getElement().querySelector('#player-vol-down') as any;
    downBtn.click();
    expect(mockConn.sendJson).toHaveBeenCalledWith({ t: 'vol', a: 'down' });

    const muteBtn = panel.getElement().querySelector('#player-vol-mute') as any;
    muteBtn.click();
    expect(mockConn.sendJson).toHaveBeenCalledWith({ t: 'vol', a: 'mute' });
  });

  it('handles track selectors for subtitles, audio and quality (T43)', () => {
    const panel = new PlayerPanel(parent, mockConn);
    const subRow = panel.getElement().querySelector('#player-sub-row') as HTMLElement;
    const audioRow = panel.getElement().querySelector('#player-audio-row') as HTMLElement;
    const qualityRow = panel.getElement().querySelector('#player-quality-row') as HTMLElement;
    const subSelect = panel.getElement().querySelector('#player-sub-select') as HTMLSelectElement;
    const audioSelect = panel.getElement().querySelector('#player-audio-select') as HTMLSelectElement;
    const qualitySelect = panel.getElement().querySelector('#player-quality-select') as HTMLSelectElement;

    // Initially hidden
    expect(subRow.style.display).toBe('none');
    expect(audioRow.style.display).toBe('none');
    expect(qualityRow.style.display).toBe('none');

    panel.update({
      on: true,
      tracks: {
        text: [{ id: 'sub-1', label: 'Türkçe', lang: 'tur' }],
        audio: [{ id: 'aud-1', label: 'Turkish', lang: 'tur' }],
        video: [{ id: 'vid-1080', height: 1080, bitrate: 5000000 }],
      },
      sel: {
        text: 'sub-1',
        audio: 'aud-1',
        video: 'vid-1080',
      },
    });

    expect(subRow.style.display).toBe('flex');
    expect(audioRow.style.display).toBe('flex');
    expect(qualityRow.style.display).toBe('flex');

    expect(subSelect.children.length).toBe(2); // 'off' + 'sub-1'
    expect(subSelect.value).toBe('sub-1');
    expect(audioSelect.value).toBe('aud-1');
    expect(qualitySelect.value).toBe('vid-1080');

    // Changing selection sends player_cmd track
    subSelect.value = 'off';
    subSelect.onchange?.(new Event('change'));
    expect(mockConn.sendJson).toHaveBeenCalledWith({
      t: 'player_cmd',
      a: 'track',
      type: 'text',
      id: 'off',
    });

    audioSelect.value = 'aud-1';
    audioSelect.onchange?.(new Event('change'));
    expect(mockConn.sendJson).toHaveBeenCalledWith({
      t: 'player_cmd',
      a: 'track',
      type: 'audio',
      id: 'aud-1',
    });

    qualitySelect.value = 'vid-1080';
    qualitySelect.onchange?.(new Event('change'));
    expect(mockConn.sendJson).toHaveBeenCalledWith({
      t: 'player_cmd',
      a: 'track',
      type: 'video',
      id: 'vid-1080',
    });
  });

  describe('Resume Button (T44)', () => {
    it('renders resume button when msg.resume is present and > 0', () => {
      const panel = new PlayerPanel(parent, mockConn);
      const resumeBtn = panel.getElement().querySelector('#player-btn-resume') as any;
      expect(resumeBtn.style.display).toBe('none');

      panel.update({ on: true, resume: 75000 });
      expect(resumeBtn.style.display).toBe('flex');
      expect(resumeBtn.textContent).toBe("1:15'den devam et");
    });

    it('hides resume button automatically after 10 seconds', () => {
      vi.useFakeTimers();
      try {
        const panel = new PlayerPanel(parent, mockConn);
        const resumeBtn = panel.getElement().querySelector('#player-btn-resume') as any;

        panel.update({ on: true, resume: 90000 });
        expect(resumeBtn.style.display).toBe('flex');
        expect(resumeBtn.textContent).toBe("1:30'den devam et");

        vi.advanceTimersByTime(9999);
        expect(resumeBtn.style.display).toBe('flex');

        vi.advanceTimersByTime(1);
        expect(resumeBtn.style.display).toBe('none');

        // Subsequent update with same resume value should not re-open it
        panel.update({ on: true, resume: 90000 });
        expect(resumeBtn.style.display).toBe('none');
      } finally {
        vi.useRealTimers();
      }
    });

    it('sends seek command and hides resume button immediately when clicked', () => {
      const panel = new PlayerPanel(parent, mockConn);
      const resumeBtn = panel.getElement().querySelector('#player-btn-resume') as any;

      panel.update({ on: true, resume: 125000 });
      expect(resumeBtn.style.display).toBe('flex');

      resumeBtn.click();
      expect(mockConn.sendJson).toHaveBeenCalledWith({
        t: 'player_cmd',
        a: 'seek',
        ms: 125000,
      });
      expect(resumeBtn.style.display).toBe('none');
    });

    it('does not render resume button when msg.resume is missing or 0', () => {
      const panel = new PlayerPanel(parent, mockConn);
      const resumeBtn = panel.getElement().querySelector('#player-btn-resume') as any;

      panel.update({ on: true });
      expect(resumeBtn.style.display).toBe('none');

      panel.update({ on: true, resume: 0 });
      expect(resumeBtn.style.display).toBe('none');
    });
  });
});
