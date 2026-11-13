import { PhantomConnection } from './conn';
import { TrackItem } from './protocol';

export function formatTime(ms: number): string {
  if (!ms || ms < 0 || isNaN(ms)) return '0:00';
  const totalSeconds = Math.floor(ms / 1000);
  const seconds = totalSeconds % 60;
  const totalMinutes = Math.floor(totalSeconds / 60);
  const minutes = totalMinutes % 60;
  const hours = Math.floor(totalMinutes / 60);

  const pad = (n: number) => n.toString().padStart(2, '0');

  if (hours > 0) {
    return `${hours}:${pad(minutes)}:${pad(seconds)}`;
  } else {
    return `${minutes}:${pad(seconds)}`;
  }
}

export class PlayerPanel {
  private container: HTMLElement;
  private titleEl: HTMLElement;
  private playPauseBtn: HTMLButtonElement;
  private back10Btn: HTMLButtonElement;
  private fwd10Btn: HTMLButtonElement;
  private closeBtn: HTMLButtonElement;
  private progressBar: HTMLInputElement;
  private timeText: HTMLElement;
  private conn: PhantomConnection;

  // Track selectors (T43)
  private tracksContainer: HTMLElement;
  private subRow: HTMLElement;
  private audioRow: HTMLElement;
  private qualityRow: HTMLElement;
  private subSelect: HTMLSelectElement;
  private audioSelect: HTMLSelectElement;
  private qualitySelect: HTMLSelectElement;

  // Resume button (T44)
  private resumeBtn: HTMLButtonElement;
  private resumeTimeout: any = null;
  private lastResumeTarget: number | null = null;
  private resumeDismissed = false;
  private currentResumeMs = 0;

  private isSeeking = false;
  private currentPos = 0;
  private currentDur = 0;
  private isPlaying = false;
  private onVisibilityChange?: (visible: boolean) => void;

  constructor(
    parent: HTMLElement,
    conn: PhantomConnection,
    onVisibilityChange?: (visible: boolean) => void
  ) {
    this.conn = conn;
    this.onVisibilityChange = onVisibilityChange;

    this.container = document.createElement('div');
    this.container.id = 'player-panel';
    this.container.style.position = 'absolute';
    this.container.style.left = '16px';
    this.container.style.right = '16px';
    this.container.style.top = '16px';
    this.container.style.zIndex = '50';
    this.container.style.background = 'rgba(28, 28, 30, 0.92)';
    this.container.style.backdropFilter = 'blur(20px)';
    (this.container.style as any).webkitBackdropFilter = 'blur(20px)';
    this.container.style.borderRadius = '16px';
    this.container.style.border = '1px solid #38383e';
    this.container.style.boxShadow = '0 8px 32px rgba(0,0,0,0.6)';
    this.container.style.padding = '16px';
    this.container.style.display = 'none';
    this.container.style.flexDirection = 'column';
    this.container.style.gap = '12px';
    this.container.style.boxSizing = 'border-box';

    // Prevent trackpad gestures from firing when interacting with player panel
    const stopBubble = (e: Event) => e.stopPropagation();
    this.container.addEventListener('touchstart', stopBubble, { passive: true });
    this.container.addEventListener('touchmove', stopBubble, { passive: true });
    this.container.addEventListener('touchend', stopBubble, { passive: true });
    this.container.addEventListener('mousedown', stopBubble);
    this.container.addEventListener('mousemove', stopBubble);
    this.container.addEventListener('mouseup', stopBubble);

    // Header row: Title + Close button
    const headerRow = document.createElement('div');
    headerRow.style.display = 'flex';
    headerRow.style.alignItems = 'center';
    headerRow.style.justifyContent = 'space-between';
    headerRow.style.gap = '8px';

    this.titleEl = document.createElement('div');
    this.titleEl.id = 'player-title';
    this.titleEl.style.flex = '1';
    this.titleEl.style.fontSize = '15px';
    this.titleEl.style.fontWeight = '600';
    this.titleEl.style.color = '#ffffff';
    this.titleEl.style.overflow = 'hidden';
    this.titleEl.style.textOverflow = 'ellipsis';
    this.titleEl.style.whiteSpace = 'nowrap';
    this.titleEl.textContent = 'Video';

    this.closeBtn = document.createElement('button');
    this.closeBtn.id = 'player-btn-close';
    this.closeBtn.textContent = 'Kapat';
    this.closeBtn.style.background = 'rgba(255, 255, 255, 0.12)';
    this.closeBtn.style.border = 'none';
    this.closeBtn.style.borderRadius = '8px';
    this.closeBtn.style.color = '#ffffff';
    this.closeBtn.style.fontSize = '13px';
    this.closeBtn.style.fontWeight = '600';
    this.closeBtn.style.padding = '6px 12px';
    this.closeBtn.style.cursor = 'pointer';
    this.closeBtn.onclick = () => {
      this.conn.sendJson({ t: 'player_cmd', a: 'close' });
      // Hide right away; if the TV has no player open no 'player' message will come back.
      this.update({ on: false });
    };

    headerRow.appendChild(this.titleEl);
    headerRow.appendChild(this.closeBtn);
    this.container.appendChild(headerRow);

    // Resume Button (T44)
    this.resumeBtn = document.createElement('button');
    this.resumeBtn.id = 'player-btn-resume';
    this.resumeBtn.style.display = 'none';
    this.resumeBtn.style.alignItems = 'center';
    this.resumeBtn.style.justifyContent = 'center';
    this.resumeBtn.style.background = '#0a84ff';
    this.resumeBtn.style.border = 'none';
    this.resumeBtn.style.borderRadius = '10px';
    this.resumeBtn.style.color = '#ffffff';
    this.resumeBtn.style.fontSize = '14px';
    this.resumeBtn.style.fontWeight = '600';
    this.resumeBtn.style.padding = '8px 14px';
    this.resumeBtn.style.cursor = 'pointer';
    this.resumeBtn.style.width = '100%';
    this.resumeBtn.onclick = () => {
      this.conn.sendJson({ t: 'player_cmd', a: 'seek', ms: this.currentResumeMs });
      this.hideResumeButton();
    };
    this.container.appendChild(this.resumeBtn);

    // Progress Bar + Time Text
    const progressContainer = document.createElement('div');
    progressContainer.style.display = 'flex';
    progressContainer.style.flexDirection = 'column';
    progressContainer.style.gap = '6px';

    this.progressBar = document.createElement('input');
    this.progressBar.id = 'player-slider';
    this.progressBar.type = 'range';
    this.progressBar.min = '0';
    this.progressBar.max = '100';
    this.progressBar.value = '0';
    this.progressBar.style.width = '100%';
    this.progressBar.style.accentColor = '#0a84ff';
    this.progressBar.style.cursor = 'pointer';

    this.progressBar.addEventListener('input', () => {
      this.isSeeking = true;
      const targetMs = parseInt(this.progressBar.value, 10);
      this.timeText.textContent = `${formatTime(targetMs)} / ${formatTime(this.currentDur)}`;
    });

    this.progressBar.addEventListener('change', () => {
      const targetMs = parseInt(this.progressBar.value, 10);
      this.conn.sendJson({ t: 'player_cmd', a: 'seek', ms: targetMs });
      this.isSeeking = false;
    });

    this.timeText = document.createElement('div');
    this.timeText.id = 'player-time';
    this.timeText.style.fontSize = '12px';
    this.timeText.style.color = '#8e8e93';
    this.timeText.style.textAlign = 'right';
    (this.timeText.style as any).fontVariantNumeric = 'tabular-nums';
    this.timeText.textContent = '0:00 / 0:00';

    progressContainer.appendChild(this.progressBar);
    progressContainer.appendChild(this.timeText);
    this.container.appendChild(progressContainer);

    // Controls Row: -10s, Play/Pause, +10s
    const controlsRow = document.createElement('div');
    controlsRow.style.display = 'flex';
    controlsRow.style.alignItems = 'center';
    controlsRow.style.justifyContent = 'center';
    controlsRow.style.gap = '24px';

    this.back10Btn = document.createElement('button');
    this.back10Btn.id = 'player-btn-back10';
    this.back10Btn.textContent = '-10s';
    this.styleSmallButton(this.back10Btn);
    this.back10Btn.onclick = () => {
      const target = Math.max(0, this.currentPos - 10000);
      this.conn.sendJson({ t: 'player_cmd', a: 'seek', ms: target });
    };

    this.playPauseBtn = document.createElement('button');
    this.playPauseBtn.id = 'player-btn-playpause';
    this.playPauseBtn.innerHTML = this.getPlayIcon();
    this.playPauseBtn.style.width = '52px';
    this.playPauseBtn.style.height = '52px';
    this.playPauseBtn.style.borderRadius = '50%';
    this.playPauseBtn.style.background = '#0a84ff';
    this.playPauseBtn.style.border = 'none';
    this.playPauseBtn.style.color = '#ffffff';
    this.playPauseBtn.style.display = 'flex';
    this.playPauseBtn.style.alignItems = 'center';
    this.playPauseBtn.style.justifyContent = 'center';
    this.playPauseBtn.style.cursor = 'pointer';
    this.playPauseBtn.onclick = () => {
      this.conn.sendJson({ t: 'player_cmd', a: this.isPlaying ? 'pause' : 'play' });
    };

    this.fwd10Btn = document.createElement('button');
    this.fwd10Btn.id = 'player-btn-fwd10';
    this.fwd10Btn.textContent = '+10s';
    this.styleSmallButton(this.fwd10Btn);
    this.fwd10Btn.onclick = () => {
      const maxDur = this.currentDur > 0 ? this.currentDur : LongMaxOrSafe();
      const target = Math.min(maxDur, this.currentPos + 10000);
      this.conn.sendJson({ t: 'player_cmd', a: 'seek', ms: target });
    };

    controlsRow.appendChild(this.back10Btn);
    controlsRow.appendChild(this.playPauseBtn);
    controlsRow.appendChild(this.fwd10Btn);
    this.container.appendChild(controlsRow);

    // Volume Controls Row in PlayerPanel (T45)
    const volRow = document.createElement('div');
    volRow.id = 'player-vol-row';
    volRow.style.display = 'flex';
    volRow.style.alignItems = 'center';
    volRow.style.justifyContent = 'center';
    volRow.style.gap = '20px';
    volRow.style.marginTop = '4px';

    const createPlayerVolBtn = (text: string, action: 'up' | 'down' | 'mute', id: string) => {
      const btn = document.createElement('button');
      btn.id = id;
      btn.textContent = text;
      this.styleSmallButton(btn);
      btn.onclick = () => {
        this.conn.sendJson({ t: 'vol', a: action });
      };
      return btn;
    };

    volRow.appendChild(createPlayerVolBtn('🔊+', 'up', 'player-vol-up'));
    volRow.appendChild(createPlayerVolBtn('🔉−', 'down', 'player-vol-down'));
    volRow.appendChild(createPlayerVolBtn('🔇', 'mute', 'player-vol-mute'));
    this.container.appendChild(volRow);

    // Tracks Container: Subtitles, Audio, Quality (T43)
    this.tracksContainer = document.createElement('div');
    this.tracksContainer.id = 'player-tracks-container';
    this.tracksContainer.style.display = 'none';
    this.tracksContainer.style.flexDirection = 'column';
    this.tracksContainer.style.gap = '8px';
    this.tracksContainer.style.marginTop = '6px';
    this.tracksContainer.style.borderTop = '1px solid rgba(255, 255, 255, 0.1)';
    this.tracksContainer.style.paddingTop = '8px';

    const createTrackRow = (label: string, selectEl: HTMLSelectElement, rowId: string) => {
      const row = document.createElement('div');
      row.id = rowId;
      row.style.display = 'none';
      row.style.alignItems = 'center';
      row.style.justifyContent = 'space-between';
      row.style.fontSize = '13px';
      row.style.color = '#8e8e93';

      const labelEl = document.createElement('span');
      labelEl.textContent = label;
      row.appendChild(labelEl);

      selectEl.style.background = 'rgba(255, 255, 255, 0.12)';
      selectEl.style.border = '1px solid rgba(255, 255, 255, 0.2)';
      selectEl.style.borderRadius = '8px';
      selectEl.style.color = '#ffffff';
      selectEl.style.padding = '4px 8px';
      selectEl.style.fontSize = '13px';
      selectEl.style.cursor = 'pointer';

      row.appendChild(selectEl);
      return row;
    };

    this.subSelect = document.createElement('select');
    this.subSelect.id = 'player-sub-select';
    this.subRow = createTrackRow('Altyazı', this.subSelect, 'player-sub-row');

    this.audioSelect = document.createElement('select');
    this.audioSelect.id = 'player-audio-select';
    this.audioRow = createTrackRow('Ses', this.audioSelect, 'player-audio-row');

    this.qualitySelect = document.createElement('select');
    this.qualitySelect.id = 'player-quality-select';
    this.qualityRow = createTrackRow('Kalite', this.qualitySelect, 'player-quality-row');

    this.subSelect.onchange = () => {
      this.conn.sendJson({ t: 'player_cmd', a: 'track', type: 'text', id: this.subSelect.value });
    };

    this.audioSelect.onchange = () => {
      this.conn.sendJson({ t: 'player_cmd', a: 'track', type: 'audio', id: this.audioSelect.value });
    };

    this.qualitySelect.onchange = () => {
      this.conn.sendJson({ t: 'player_cmd', a: 'track', type: 'video', id: this.qualitySelect.value });
    };

    this.tracksContainer.appendChild(this.subRow);
    this.tracksContainer.appendChild(this.audioRow);
    this.tracksContainer.appendChild(this.qualityRow);
    this.container.appendChild(this.tracksContainer);

    parent.appendChild(this.container);
  }

  private styleSmallButton(btn: HTMLButtonElement) {
    btn.style.background = 'rgba(255, 255, 255, 0.15)';
    btn.style.border = 'none';
    btn.style.borderRadius = '20px';
    btn.style.color = '#ffffff';
    btn.style.fontSize = '14px';
    btn.style.fontWeight = '600';
    btn.style.padding = '8px 14px';
    btn.style.cursor = 'pointer';
  }

  private getPlayIcon(): string {
    return '<svg width="24" height="24" viewBox="0 0 24 24" fill="#fff"><polygon points="6 3 20 12 6 21 6 3"></polygon></svg>';
  }

  private getPauseIcon(): string {
    return '<svg width="24" height="24" viewBox="0 0 24 24" fill="#fff"><rect x="6" y="4" width="4" height="16"></rect><rect x="14" y="4" width="4" height="16"></rect></svg>';
  }

  private hideResumeButton() {
    if (this.resumeTimeout) {
      clearTimeout(this.resumeTimeout);
      this.resumeTimeout = null;
    }
    this.resumeBtn.style.display = 'none';
    this.resumeDismissed = true;
  }

  public update(msg: {
    on: boolean;
    playing?: boolean;
    pos?: number;
    dur?: number;
    resume?: number;
    title?: string;
    tracks?: {
      audio?: TrackItem[];
      text?: TrackItem[];
      video?: TrackItem[];
    };
    sel?: {
      audio?: string;
      text?: string;
      video?: string;
    };
  }) {
    if (!msg.on) {
      this.container.style.display = 'none';
      this.hideResumeButton();
      this.lastResumeTarget = null;
      this.resumeDismissed = false;
      this.onVisibilityChange?.(false);
      return;
    }

    this.container.style.display = 'flex';
    this.onVisibilityChange?.(true);
    this.isPlaying = !!msg.playing;
    this.playPauseBtn.innerHTML = this.isPlaying ? this.getPauseIcon() : this.getPlayIcon();

    // Resume button handling (T44)
    if (msg.resume !== undefined && msg.resume > 0) {
      if (this.lastResumeTarget !== msg.resume && !this.resumeDismissed) {
        this.lastResumeTarget = msg.resume;
        this.currentResumeMs = msg.resume;
        this.resumeBtn.textContent = `${formatTime(msg.resume)}'den devam et`;
        this.resumeBtn.style.display = 'flex';

        if (this.resumeTimeout) {
          clearTimeout(this.resumeTimeout);
        }
        this.resumeTimeout = setTimeout(() => {
          this.hideResumeButton();
        }, 10000);
      }
    } else if (msg.resume === 0 || msg.resume === null) {
      this.hideResumeButton();
    }

    if (msg.title !== undefined && msg.title !== '') {
      this.titleEl.textContent = msg.title;
    }

    if (msg.pos !== undefined) {
      this.currentPos = msg.pos;
    }
    if (msg.dur !== undefined) {
      this.currentDur = msg.dur;
    }

    if (!this.isSeeking) {
      this.progressBar.max = this.currentDur > 0 ? this.currentDur.toString() : '100';
      this.progressBar.value = this.currentPos.toString();
      this.timeText.textContent = `${formatTime(this.currentPos)} / ${formatTime(this.currentDur)}`;
    }

    // Update track selectors (T43)
    if (msg.tracks) {
      let hasAnyTracks = false;

      // Selects are rebuilt only when their options change and never while focused:
      // state arrives every second and rebuilding resets an open iOS picker.
      const textItems = msg.tracks.text ?? [];
      if (textItems.length > 0) {
        const opts: Array<[string, string]> = [['off', 'Kapalı']];
        textItems.forEach((t, i) => opts.push([t.id, trackLabel(t, `Altyazı ${i + 1}`)]));
        fillSelect(this.subSelect, opts, msg.sel?.text);
        this.subRow.style.display = 'flex';
        hasAnyTracks = true;
      } else {
        this.subRow.style.display = 'none';
      }

      const audioItems = msg.tracks.audio ?? [];
      if (audioItems.length > 0) {
        fillSelect(this.audioSelect, audioItems.map((t, i) => [t.id, trackLabel(t, `Ses ${i + 1}`)]), msg.sel?.audio);
        this.audioRow.style.display = 'flex';
        hasAnyTracks = true;
      } else {
        this.audioRow.style.display = 'none';
      }

      const videoItems = msg.tracks.video ?? [];
      if (videoItems.length > 0) {
        fillSelect(this.qualitySelect, videoItems.map((t) => [t.id, t.height ? `${t.height}p` : t.id]), msg.sel?.video);
        this.qualityRow.style.display = 'flex';
        hasAnyTracks = true;
      } else {
        this.qualityRow.style.display = 'none';
      }

      this.tracksContainer.style.display = hasAnyTracks ? 'flex' : 'none';
    }
  }

  public getElement(): HTMLElement {
    return this.container;
  }
}

function LongMaxOrSafe(): number {
  return Number.MAX_SAFE_INTEGER;
}

function trackLabel(t: TrackItem, fallback: string): string {
  if (t.label) return t.label;
  const lang = (t.lang ?? '').trim();
  if (!lang || lang === '?' || lang.toLowerCase() === 'und') return fallback;
  return lang.toUpperCase();
}

const selectSigs = new WeakMap<HTMLSelectElement, string>();

function fillSelect(select: HTMLSelectElement, opts: Array<[string, string]>, selected?: string) {
  if (document.activeElement === select) return;
  const sig = JSON.stringify(opts);
  if (selectSigs.get(select) !== sig) {
    select.innerHTML = '';
    for (const [value, text] of opts) {
      const opt = document.createElement('option');
      opt.value = value;
      opt.textContent = text;
      select.appendChild(opt);
    }
    selectSigs.set(select, sig);
  }
  if (selected && select.value !== selected) select.value = selected;
}
