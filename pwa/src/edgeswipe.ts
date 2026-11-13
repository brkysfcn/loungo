// Back gesture: a single finger that starts at the trackpad's left edge and is pulled to
// the right. While it is being tracked the cursor does not move; if the finger turns out to be
// a normal cursor move (vertical, slow, or a second finger) the held-back movement is released.

export type EdgeState = 'idle' | 'tracking';

export interface EdgeSwipeOptions {
  edgePx: number; // start zone width from the left edge
  triggerPx: number; // rightward travel that counts as Back
  maxMs: number; // longer than this is a cursor move, not a gesture
  slopPx: number; // vertical travel allowed before giving up
}

export const EDGE_DEFAULTS: EdgeSwipeOptions = { edgePx: 32, triggerPx: 90, maxMs: 700, slopPx: 30 };

export class EdgeSwipe {
  private state: EdgeState = 'idle';
  private startX = 0;
  private startY = 0;
  private startTime = 0;
  private progress = 0;

  constructor(private readonly opts: EdgeSwipeOptions = EDGE_DEFAULTS) {}

  get tracking(): boolean {
    return this.state === 'tracking';
  }

  /** `x` is relative to the trackpad's left edge. Returns true when the touch is tracked. */
  start(x: number, y: number, fingers: number, time: number): boolean {
    this.state = fingers === 1 && x >= 0 && x <= this.opts.edgePx ? 'tracking' : 'idle';
    this.startX = x;
    this.startY = y;
    this.startTime = time;
    this.progress = 0;
    return this.tracking;
  }

  /** Returns progress 0..1, or -1 when the touch stopped being a back gesture. */
  move(x: number, y: number, fingers: number, time: number): number {
    if (!this.tracking) return -1;
    const dx = x - this.startX;
    const dy = Math.abs(y - this.startY);
    if (fingers !== 1 || dy > this.opts.slopPx || dx < -this.opts.slopPx || time - this.startTime > this.opts.maxMs) {
      this.cancel();
      return -1;
    }
    this.progress = Math.max(0, Math.min(1, dx / this.opts.triggerPx));
    return this.progress;
  }

  /** Returns true when the gesture completed (finger lifted past the trigger distance). */
  end(): boolean {
    const done = this.tracking && this.progress >= 1;
    this.cancel();
    return done;
  }

  cancel(): void {
    this.state = 'idle';
    this.progress = 0;
  }
}
