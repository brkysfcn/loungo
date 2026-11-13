export function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}

/**
 * Velocity-dependent gain function.
 * v: velocity in px/ms
 * s: sensitivity factor (0.5 to 3.0, default 1.0)
 * Formula: s * (1 + 6 * clamp(v - 0.2, 0, 1.2))
 */
export function gain(v: number, s: number = 1): number {
  const c = clamp(v - 0.2, 0, 1.2);
  return s * (1 + 6 * c);
}

/**
 * Accumulator for carrying fractional delta remainders between frames.
 */
export class Accum {
  private remX = 0;
  private remY = 0;

  add(dx: number, dy: number): void {
    this.remX += dx;
    this.remY += dy;
  }

  take(): { dx: number; dy: number } {
    const eps = 1e-6;
    let rx = this.remX;
    let ry = this.remY;
    if (Math.abs(Math.round(rx) - rx) < eps) {
      rx = Math.round(rx);
    }
    if (Math.abs(Math.round(ry) - ry) < eps) {
      ry = Math.round(ry);
    }
    const intX = Math.trunc(rx);
    const intY = Math.trunc(ry);
    this.remX = rx - intX;
    this.remY = ry - intY;
    return { dx: intX, dy: intY };
  }

  reset(): void {
    this.remX = 0;
    this.remY = 0;
  }
}

/**
 * Simulates momentum decay for scroll.
 * Every 16 ms frame: v *= 0.95 until |v| < 0.5 px/frame.
 * Returns array of deltas for each frame.
 */
export function momentum(v0: number): number[] {
  const deltas: number[] = [];
  let v = v0;
  while (Math.abs(v) >= 0.5) {
    deltas.push(v);
    v *= 0.95;
  }
  return deltas;
}

export type FsmMessage =
  | { type: 'move'; dx: number; dy: number }
  | { type: 'scroll'; sx: number; sy: number }
  | { type: 'button'; down: boolean; btn: number }
  | { type: 'vibrate'; ms: number };

export interface TouchPoint {
  id: number;
  x: number;
  y: number;
}

type FsmState =
  | 'idle'
  | 'touch_down'    // 1 finger down, waiting for tap vs move vs long-press
  | 'moving'        // 1 finger moving (cursor move)
  | 'dragging'      // 1 finger dragging after long-press (button down)
  | 'scrolling';    // 2 fingers scrolling

/**
 * Pure Touch State Machine for trackpad gesture interpretation.
 */
export class TouchFsm {
  private state: FsmState = 'idle';
  private sensitivity = 1.0;
  private accum = new Accum();
  private scrollAccum = new Accum();

  // 1-finger tracking
  private touchId = -1;
  private startX = 0;
  private startY = 0;
  private startTime = 0;
  private lastX = 0;
  private lastY = 0;
  private lastTime = 0;
  private longPressTriggered = false;

  // 2-finger tracking
  private touchId1 = -1;
  private touchId2 = -1;
  private lastScrollX = 0;
  private lastScrollY = 0;
  private lastScrollTime = 0;
  private scrollVelY = 0;
  private scrollVelX = 0;

  // Momentum active state
  private momentumActive = false;
  private momentumVx = 0;
  private momentumVy = 0;

  constructor(sensitivity = 1.0) {
    this.sensitivity = sensitivity;
  }

  setSensitivity(s: number): void {
    this.sensitivity = s;
  }

  getSensitivity(): number {
    return this.sensitivity;
  }

  isMomentumActive(): boolean {
    return this.momentumActive;
  }

  stopMomentum(): void {
    this.momentumActive = false;
    this.momentumVx = 0;
    this.momentumVy = 0;
  }

  onTouchStart(touches: TouchPoint[], time: number): FsmMessage[] {
    const out: FsmMessage[] = [];

    // Any new touch immediately cancels active momentum
    if (this.momentumActive) {
      this.stopMomentum();
    }

    if (touches.length === 1) {
      const t = touches[0];
      this.state = 'touch_down';
      this.touchId = t.id;
      this.startX = t.x;
      this.startY = t.y;
      this.lastX = t.x;
      this.lastY = t.y;
      this.startTime = time;
      this.lastTime = time;
      this.longPressTriggered = false;
      this.accum.reset();
    } else if (touches.length >= 2) {
      // If we were in dragging state, release button
      if (this.state === 'dragging') {
        out.push({ type: 'button', down: false, btn: 0 });
      }
      this.state = 'scrolling';
      this.touchId1 = touches[0].id;
      this.touchId2 = touches[1].id;
      const midX = (touches[0].x + touches[1].x) / 2;
      const midY = (touches[0].y + touches[1].y) / 2;
      this.lastScrollX = midX;
      this.lastScrollY = midY;
      this.lastScrollTime = time;
      this.scrollVelX = 0;
      this.scrollVelY = 0;
      this.scrollAccum.reset();
    }

    return out;
  }

  onTouchMove(touches: TouchPoint[], time: number): FsmMessage[] {
    const out: FsmMessage[] = [];

    if (this.state === 'touch_down') {
      const t = touches.find((pt) => pt.id === this.touchId);
      if (!t) return out;

      const totalDist = Math.hypot(t.x - this.startX, t.y - this.startY);
      const elapsed = time - this.startTime;

      // Check long press (350 ms, < 8 px movement)
      if (!this.longPressTriggered && elapsed >= 350 && totalDist < 8) {
        this.longPressTriggered = true;
        this.state = 'dragging';
        out.push({ type: 'button', down: true, btn: 0 });
        out.push({ type: 'vibrate', ms: 10 });
        this.lastX = t.x;
        this.lastY = t.y;
        this.lastTime = time;
        return out;
      }

      // If movement exceeds 8 px, transition to moving
      if (totalDist >= 8) {
        this.state = 'moving';
        // Process move from start to current
        const dt = Math.max(1, time - this.startTime);
        const dx = t.x - this.startX;
        const dy = t.y - this.startY;
        const v = Math.hypot(dx, dy) / dt;
        const g = gain(v, this.sensitivity);
        this.accum.add(dx * g, dy * g);
        const taken = this.accum.take();
        if (taken.dx !== 0 || taken.dy !== 0) {
          out.push({ type: 'move', dx: taken.dx, dy: taken.dy });
        }
        this.lastX = t.x;
        this.lastY = t.y;
        this.lastTime = time;
        return out;
      }
    } else if (this.state === 'moving' || this.state === 'dragging') {
      const t = touches.find((pt) => pt.id === this.touchId);
      if (!t) return out;

      const dt = Math.max(1, time - this.lastTime);
      const dx = t.x - this.lastX;
      const dy = t.y - this.lastY;
      const dist = Math.hypot(dx, dy);
      const v = dist / dt;
      const g = gain(v, this.sensitivity);

      this.accum.add(dx * g, dy * g);
      const taken = this.accum.take();
      if (taken.dx !== 0 || taken.dy !== 0) {
        out.push({ type: 'move', dx: taken.dx, dy: taken.dy });
      }

      this.lastX = t.x;
      this.lastY = t.y;
      this.lastTime = time;
    } else if (this.state === 'scrolling') {
      const t1 = touches.find((pt) => pt.id === this.touchId1);
      const t2 = touches.find((pt) => pt.id === this.touchId2);
      if (!t1 || !t2) return out;

      const midX = (t1.x + t2.x) / 2;
      const midY = (t1.y + t2.y) / 2;
      const dt = Math.max(1, time - this.lastScrollTime);

      // Natural direction: finger up -> sy > 0 (scroll page down)
      const rawDeltaX = -(midX - this.lastScrollX);
      const rawDeltaY = -(midY - this.lastScrollY);

      // 1:1 CSS px
      this.scrollAccum.add(rawDeltaX, rawDeltaY);
      const taken = this.scrollAccum.take();
      if (taken.dx !== 0 || taken.dy !== 0) {
        out.push({ type: 'scroll', sx: taken.dx, sy: taken.dy });
      }

      // Track velocity in px/frame (~16ms frame) for momentum
      const vx = (rawDeltaX / dt) * 16;
      const vy = (rawDeltaY / dt) * 16;
      // Exponential moving average for smooth release velocity
      this.scrollVelX = this.scrollVelX * 0.3 + vx * 0.7;
      this.scrollVelY = this.scrollVelY * 0.3 + vy * 0.7;

      this.lastScrollX = midX;
      this.lastScrollY = midY;
      this.lastScrollTime = time;
    }

    return out;
  }

  onTouchEnd(touches: TouchPoint[], time: number): FsmMessage[] {
    const out: FsmMessage[] = [];

    if (this.state === 'touch_down') {
      const elapsed = time - this.startTime;
      const totalDist = Math.hypot(this.lastX - this.startX, this.lastY - this.startY);
      // Tap: < 200 ms and total movement < 8 px
      if (elapsed < 200 && totalDist < 8) {
        out.push({ type: 'button', down: true, btn: 0 });
        out.push({ type: 'button', down: false, btn: 0 });
      }
      this.state = 'idle';
    } else if (this.state === 'moving') {
      this.state = 'idle';
    } else if (this.state === 'dragging') {
      out.push({ type: 'button', down: false, btn: 0 });
      this.state = 'idle';
    } else if (this.state === 'scrolling') {
      if (touches.length < 2) {
        this.state = 'idle';
        // Check if release velocity qualifies for momentum
        const speed = Math.hypot(this.scrollVelX, this.scrollVelY);
        if (speed >= 0.5) {
          this.momentumActive = true;
          this.momentumVx = this.scrollVelX;
          this.momentumVy = this.scrollVelY;
        }
      }
    }

    return out;
  }

  onTick(time: number): FsmMessage[] {
    const out: FsmMessage[] = [];

    // Check long press timeout while still in touch_down
    if (this.state === 'touch_down' && !this.longPressTriggered) {
      const elapsed = time - this.startTime;
      const totalDist = Math.hypot(this.lastX - this.startX, this.lastY - this.startY);
      if (elapsed >= 350 && totalDist < 8) {
        this.longPressTriggered = true;
        this.state = 'dragging';
        out.push({ type: 'button', down: true, btn: 0 });
        out.push({ type: 'vibrate', ms: 10 });
      }
    }

    // Process momentum frame
    if (this.momentumActive) {
      if (Math.hypot(this.momentumVx, this.momentumVy) >= 0.5) {
        this.scrollAccum.add(this.momentumVx, this.momentumVy);
        const taken = this.scrollAccum.take();
        if (taken.dx !== 0 || taken.dy !== 0) {
          out.push({ type: 'scroll', sx: taken.dx, sy: taken.dy });
        }
        this.momentumVx *= 0.95;
        this.momentumVy *= 0.95;
      } else {
        this.stopMomentum();
      }
    }

    return out;
  }
}
