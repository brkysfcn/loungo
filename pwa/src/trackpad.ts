import { TouchFsm, FsmMessage, TouchPoint } from './gesture';
import { PhantomConnection } from './conn';
import { EdgeSwipe } from './edgeswipe';
import { encodeMove, encodeScroll, encodeButton, splitDelta } from './protocol';

export class Trackpad {
  private element: HTMLElement;
  private conn: PhantomConnection;
  private fsm: TouchFsm;
  private onTap?: () => void;
  private pendingDx = 0;
  private pendingDy = 0;
  private pendingSx = 0;
  private pendingSy = 0;
  private rafId: number | null = null;
  private isDestroyed = false;
  private activeRings: Map<number, HTMLElement> = new Map();
  private edge = new EdgeSwipe();
  private onBack?: () => void;
  private heldDx = 0;
  private heldDy = 0;
  private edgeArrow: HTMLElement | null = null;
  private edgeReady = false;

  constructor(
    element: HTMLElement,
    conn: PhantomConnection,
    initialSensitivity = 1.0,
    onTap?: () => void,
    onBack?: () => void
  ) {
    this.element = element;
    this.conn = conn;
    this.fsm = new TouchFsm(initialSensitivity);
    this.onTap = onTap;
    this.onBack = onBack;

    this.bindEvents();
    this.startLoop();
  }

  public setSensitivity(s: number): void {
    this.fsm.setSensitivity(s);
  }

  public getSensitivity(): number {
    return this.fsm.getSensitivity();
  }

  private handleMessages(msgs: FsmMessage[]): void {
    for (const msg of msgs) {
      if (msg.type === 'button') {
        if (this.edge.tracking) this.cancelEdge();
        this.flushPending();
        this.conn.sendBinary(encodeButton(msg.down, msg.btn));
      } else if (msg.type === 'vibrate') {
        try {
          if (typeof navigator !== 'undefined' && navigator.vibrate) {
            navigator.vibrate(msg.ms);
          }
        } catch {
          // Vibration may not be supported or allowed
        }
      } else if (msg.type === 'move' && this.edge.tracking) {
        this.heldDx += msg.dx;
        this.heldDy += msg.dy;
      } else if (msg.type === 'move') {
        this.pendingDx += msg.dx;
        this.pendingDy += msg.dy;
      } else if (msg.type === 'scroll') {
        this.pendingSx += msg.sx;
        this.pendingSy += msg.sy;
      }
    }
  }

  private flushPending(): void {
    if (this.pendingDx !== 0 || this.pendingDy !== 0) {
      const chunksX = splitDelta(this.pendingDx);
      const chunksY = splitDelta(this.pendingDy);
      const maxLen = Math.max(chunksX.length, chunksY.length);
      for (let i = 0; i < maxLen; i++) {
        const dx = chunksX[i] ?? 0;
        const dy = chunksY[i] ?? 0;
        this.conn.sendBinary(encodeMove(dx, dy));
      }
      this.pendingDx = 0;
      this.pendingDy = 0;
    }

    if (this.pendingSx !== 0 || this.pendingSy !== 0) {
      const chunksX = splitDelta(this.pendingSx);
      const chunksY = splitDelta(this.pendingSy);
      const maxLen = Math.max(chunksX.length, chunksY.length);
      for (let i = 0; i < maxLen; i++) {
        const sx = chunksX[i] ?? 0;
        const sy = chunksY[i] ?? 0;
        this.conn.sendBinary(encodeScroll(sx, sy));
      }
      this.pendingSx = 0;
      this.pendingSy = 0;
    }
  }

  private startLoop(): void {
    const loop = (time: DOMHighResTimeStamp) => {
      if (this.isDestroyed) return;
      const tickMsgs = this.fsm.onTick(time);
      if (tickMsgs.length > 0) {
        this.handleMessages(tickMsgs);
      }
      this.flushPending();
      this.rafId = requestAnimationFrame(loop);
    };
    this.rafId = requestAnimationFrame(loop);
  }

  private showFeedback(x: number, y: number, id: number): void {
    const rect = this.element.getBoundingClientRect();
    const ring = document.createElement('div');
    ring.className = 'touch-ring';
    ring.style.position = 'absolute';
    ring.style.left = `${x - rect.left - 22}px`;
    ring.style.top = `${y - rect.top - 22}px`;
    ring.style.width = '44px';
    ring.style.height = '44px';
    ring.style.borderRadius = '50%';
    ring.style.backgroundColor = 'rgba(255, 255, 255, 0.25)';
    ring.style.pointerEvents = 'none';
    ring.style.zIndex = '100';
    ring.style.opacity = '1';
    ring.style.transition = 'opacity 200ms ease-out';
    this.element.appendChild(ring);
    this.activeRings.set(id, ring);
  }

  private updateFeedback(x: number, y: number, id: number): void {
    const ring = this.activeRings.get(id);
    if (!ring) return;
    const rect = this.element.getBoundingClientRect();
    ring.style.left = `${x - rect.left - 22}px`;
    ring.style.top = `${y - rect.top - 22}px`;
  }

  private hideFeedback(id: number): void {
    const ring = this.activeRings.get(id);
    if (!ring) return;
    this.activeRings.delete(id);
    ring.style.opacity = '0';
    setTimeout(() => {
      ring.remove();
    }, 200);
  }

  /** The touch is a normal cursor move after all: release the movement held back so far. */
  private cancelEdge(): void {
    this.edge.cancel();
    this.pendingDx += this.heldDx;
    this.pendingDy += this.heldDy;
    this.heldDx = 0;
    this.heldDy = 0;
    this.showEdgeArrow(-1, 0);
  }

  private showEdgeArrow(progress: number, y: number): void {
    if (progress < 0) {
      if (this.edgeArrow) this.edgeArrow.style.opacity = '0';
      return;
    }
    if (!this.edgeArrow) {
      const a = document.createElement('div');
      a.className = 'edge-back-arrow';
      a.textContent = '‹';
      Object.assign(a.style, {
        position: 'absolute', left: '0', width: '44px', height: '44px', marginTop: '-22px',
        borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: '32px', fontWeight: '700', lineHeight: '1', color: '#fff',
        pointerEvents: 'none', zIndex: '101', opacity: '0', transition: 'opacity 150ms',
      });
      this.element.appendChild(a);
      this.edgeArrow = a;
    }
    const rect = this.element.getBoundingClientRect();
    const a = this.edgeArrow;
    a.style.top = `${y - rect.top}px`;
    a.style.transform = `translateX(${4 + progress * 36}px) scale(${0.7 + progress * 0.3})`;
    a.style.background = progress >= 1 ? '#0a84ff' : 'rgba(255,255,255,0.25)';
    a.style.opacity = progress > 0.05 ? '1' : '0';
  }

  private bindEvents(): void {
    const toTouchPoints = (list: TouchList): TouchPoint[] => {
      const pts: TouchPoint[] = [];
      for (let i = 0; i < list.length; i++) {
        const t = list.item(i);
        if (t) {
          pts.push({ id: t.identifier, x: t.clientX, y: t.clientY });
        }
      }
      return pts;
    };

    // Touch events for mobile/iOS
    this.element.addEventListener(
      'touchstart',
      (e) => {
        e.preventDefault();
        for (let i = 0; i < e.changedTouches.length; i++) {
          const t = e.changedTouches.item(i);
          if (t) {
            this.showFeedback(t.clientX, t.clientY, t.identifier);
          }
        }
        const pts = toTouchPoints(e.touches);
        const now = performance.now();
        if (this.onBack) {
          if (pts.length === 1) {
            const left = this.element.getBoundingClientRect().left;
            this.heldDx = 0;
            this.heldDy = 0;
            this.edge.start(pts[0].x - left, pts[0].y, 1, now);
          } else if (this.edge.tracking) {
            this.cancelEdge();
          }
        }
        const msgs = this.fsm.onTouchStart(pts, now);
        this.handleMessages(msgs);
      },
      { passive: false }
    );

    this.element.addEventListener(
      'touchmove',
      (e) => {
        e.preventDefault();
        for (let i = 0; i < e.changedTouches.length; i++) {
          const t = e.changedTouches.item(i);
          if (t) {
            this.updateFeedback(t.clientX, t.clientY, t.identifier);
          }
        }
        const pts = toTouchPoints(e.touches);
        const now = performance.now();
        if (this.edge.tracking && pts.length > 0) {
          const left = this.element.getBoundingClientRect().left;
          const wasBelow = !this.edgeReady;
          const p = this.edge.move(pts[0].x - left, pts[0].y, pts.length, now);
          if (p < 0) {
            this.cancelEdge();
          } else {
            this.edgeReady = p >= 1;
            if (this.edgeReady && wasBelow) {
              try { navigator.vibrate?.(15); } catch { /* not supported */ }
            }
            this.showEdgeArrow(p, pts[0].y);
          }
        }
        const msgs = this.fsm.onTouchMove(pts, now);
        this.handleMessages(msgs);
      },
      { passive: false }
    );

    const onTouchEnd = (e: TouchEvent) => {
      e.preventDefault();
      for (let i = 0; i < e.changedTouches.length; i++) {
        const t = e.changedTouches.item(i);
        if (t) {
          this.hideFeedback(t.identifier);
        }
      }
      const pts = toTouchPoints(e.touches);
      const msgs = this.fsm.onTouchEnd(pts, performance.now());
      if (this.edge.tracking && pts.length === 0) {
        this.edgeReady = false;
        if (this.edge.end()) {
          // Back: the held-back movement and the FSM's end messages are dropped.
          this.heldDx = 0;
          this.heldDy = 0;
          this.showEdgeArrow(-1, 0);
          this.onBack?.();
          return;
        }
        this.cancelEdge();
      }
      const isTap =
        msgs.length === 2 &&
        msgs[0].type === 'button' &&
        msgs[0].down &&
        msgs[1].type === 'button' &&
        !msgs[1].down;
      if (isTap) {
        this.onTap?.();
      }
      this.handleMessages(msgs);
    };

    this.element.addEventListener('touchend', onTouchEnd, { passive: false });
    this.element.addEventListener('touchcancel', onTouchEnd, { passive: false });

    // Fallback mouse events for desktop debugging
    let isMouseDown = false;
    this.element.addEventListener('mousedown', (e) => {
      if (e.button !== 0) return;
      isMouseDown = true;
      this.showFeedback(e.clientX, e.clientY, 1);
      const msgs = this.fsm.onTouchStart(
        [{ id: 1, x: e.clientX, y: e.clientY }],
        performance.now()
      );
      this.handleMessages(msgs);
    });

    window.addEventListener('mousemove', (e) => {
      if (!isMouseDown) return;
      this.updateFeedback(e.clientX, e.clientY, 1);
      const msgs = this.fsm.onTouchMove(
        [{ id: 1, x: e.clientX, y: e.clientY }],
        performance.now()
      );
      this.handleMessages(msgs);
    });

    window.addEventListener('mouseup', () => {
      if (!isMouseDown) return;
      isMouseDown = false;
      this.hideFeedback(1);
      const msgs = this.fsm.onTouchEnd([], performance.now());
      const isTap =
        msgs.length === 2 &&
        msgs[0].type === 'button' &&
        msgs[0].down &&
        msgs[1].type === 'button' &&
        !msgs[1].down;
      if (isTap) {
        this.onTap?.();
      }
      this.handleMessages(msgs);
    });

    // Wheel event for desktop scroll debugging
    this.element.addEventListener(
      'wheel',
      (e) => {
        e.preventDefault();
        this.pendingSx += Math.trunc(e.deltaX);
        this.pendingSy += Math.trunc(e.deltaY);
      },
      { passive: false }
    );
  }

  public destroy(): void {
    this.isDestroyed = true;
    for (const [id] of this.activeRings) {
      this.hideFeedback(id);
    }
    this.activeRings.clear();
    if (this.rafId !== null) {
      cancelAnimationFrame(this.rafId);
      this.rafId = null;
    }
  }
}
