import { describe, it, expect } from 'vitest';
import { gain, Accum, momentum, TouchFsm } from './gesture';

describe('Gesture Engine', () => {
  describe('gain', () => {
    it('handles low velocities at sensitivity 1', () => {
      // v <= 0.2 -> clamp(v - 0.2, 0, 1.2) = 0 -> gain = 1
      expect(gain(0, 1)).toBe(1);
      expect(gain(0.1, 1)).toBe(1);
      expect(gain(0.2, 1)).toBe(1);
    });

    it('scales linearly between 0.2 and 1.4 px/ms', () => {
      // v = 0.8 -> clamp(0.6, 0, 1.2) -> 1 + 6 * 0.6 = 4.6
      expect(gain(0.8, 1)).toBeCloseTo(4.6, 4);
    });

    it('clamps to maximum gain at 1.4 px/ms and above', () => {
      // v >= 1.4 -> clamp = 1.2 -> 1 + 6 * 1.2 = 8.2
      expect(gain(1.4, 1)).toBeCloseTo(8.2, 4);
      expect(gain(2.5, 1)).toBeCloseTo(8.2, 4);
    });

    it('scales with sensitivity parameter s (0.5 to 3.0)', () => {
      expect(gain(0.2, 0.5)).toBeCloseTo(0.5, 4);
      expect(gain(1.4, 0.5)).toBeCloseTo(4.1, 4); // 8.2 * 0.5
      expect(gain(0.2, 2.0)).toBeCloseTo(2.0, 4);
      expect(gain(1.4, 3.0)).toBeCloseTo(24.6, 4); // 8.2 * 3.0
    });
  });

  describe('Accum', () => {
    it('does not lose fractional remainder (0.3 x 10 = 3)', () => {
      const accum = new Accum();
      let totalDx = 0;
      let totalDy = 0;

      for (let i = 0; i < 10; i++) {
        accum.add(0.3, 0.3);
        const taken = accum.take();
        totalDx += taken.dx;
        totalDy += taken.dy;
      }

      // 0.3 * 10 = 3.0
      expect(totalDx).toBe(3);
      expect(totalDy).toBe(3);
    });

    it('handles negative fractional remainders', () => {
      const accum = new Accum();
      let totalDx = 0;
      for (let i = 0; i < 5; i++) {
        accum.add(-0.4, 0);
        totalDx += accum.take().dx;
      }
      // -0.4 * 5 = -2.0
      expect(totalDx).toBe(-2);
    });
  });

  describe('momentum', () => {
    it('terminates and produces positive sum for positive v0', () => {
      const deltas = momentum(20);
      expect(deltas.length).toBeGreaterThan(0);
      // Last delta should be >= 0.5
      const last = deltas[deltas.length - 1];
      expect(Math.abs(last)).toBeGreaterThanOrEqual(0.5);
      // Next iteration would be < 0.5
      expect(Math.abs(last * 0.95)).toBeLessThan(0.5);

      const sum = deltas.reduce((acc, d) => acc + d, 0);
      expect(sum).toBeGreaterThan(0);
      // Geometric series: v0 / (1 - 0.95) = 20 / 0.05 = ~400
      expect(sum).toBeGreaterThan(350);
    });

    it('terminates immediately if initial velocity < 0.5', () => {
      const deltas = momentum(0.4);
      expect(deltas.length).toBe(0);
    });
  });

  describe('TouchFsm', () => {
    it('detects tap (< 200 ms, movement < 8 px)', () => {
      const fsm = new TouchFsm(1.0);
      // Touch down at t=100
      const startOut = fsm.onTouchStart([{ id: 1, x: 100, y: 100 }], 100);
      expect(startOut).toEqual([]);

      // Small jitter (3 px) at t=150
      const moveOut = fsm.onTouchMove([{ id: 1, x: 103, y: 100 }], 150);
      expect(moveOut).toEqual([]);

      // Touch up at t=180 (< 200ms elapsed, 3px total dist)
      const endOut = fsm.onTouchEnd([], 180);
      expect(endOut).toEqual([
        { type: 'button', down: true, btn: 0 },
        { type: 'button', down: false, btn: 0 }
      ]);
    });

    it('detects long press (350 ms, < 8 px), drags, then releases', () => {
      const fsm = new TouchFsm(1.0);
      // Touch down at t=0
      fsm.onTouchStart([{ id: 1, x: 50, y: 50 }], 0);

      // Jitter < 8px
      fsm.onTouchMove([{ id: 1, x: 52, y: 50 }], 100);

      // Tick at 350 ms triggers long press
      const tickOut = fsm.onTick(350);
      expect(tickOut).toEqual([
        { type: 'button', down: true, btn: 0 },
        { type: 'vibrate', ms: 10 }
      ]);

      // Drag movement while button is down
      const dragOut = fsm.onTouchMove([{ id: 1, x: 80, y: 50 }], 400);
      expect(dragOut.some((m) => m.type === 'move' && m.dx > 0)).toBe(true);

      // Release finger
      const endOut = fsm.onTouchEnd([], 450);
      expect(endOut).toEqual([{ type: 'button', down: false, btn: 0 }]);
    });

    it('handles 2-finger scroll with natural direction and momentum', () => {
      const fsm = new TouchFsm(1.0);
      // Touch 2 fingers at t=0
      fsm.onTouchStart([
        { id: 1, x: 100, y: 200 },
        { id: 2, x: 200, y: 200 }
      ], 0);

      // Move fingers upward (midY from 200 to 150 -> dy = -50 -> sy > 0 natural scroll down)
      const moveOut = fsm.onTouchMove([
        { id: 1, x: 100, y: 150 },
        { id: 2, x: 200, y: 150 }
      ], 50);

      expect(moveOut.some((m) => m.type === 'scroll' && m.sy > 0)).toBe(true);

      // Release fingers
      fsm.onTouchEnd([], 60);
      expect(fsm.isMomentumActive()).toBe(true);

      // Ticks generate momentum scroll frames
      const tickOut = fsm.onTick(76);
      expect(tickOut.some((m) => m.type === 'scroll' && m.sy > 0)).toBe(true);
    });

    it('immediately stops momentum when a new touch arrives', () => {
      const fsm = new TouchFsm(1.0);
      // Initiate 2-finger scroll
      fsm.onTouchStart([
        { id: 1, x: 100, y: 200 },
        { id: 2, x: 200, y: 200 }
      ], 0);
      fsm.onTouchMove([
        { id: 1, x: 100, y: 100 },
        { id: 2, x: 200, y: 100 }
      ], 30);
      fsm.onTouchEnd([], 40);
      expect(fsm.isMomentumActive()).toBe(true);

      // New touch arrives
      fsm.onTouchStart([{ id: 3, x: 150, y: 150 }], 50);
      expect(fsm.isMomentumActive()).toBe(false);

      // Tick produces no momentum scroll
      const tickOut = fsm.onTick(66);
      expect(tickOut).toEqual([]);
    });

    it('fast flick: 300 px / 200 ms produces >= 1920 px MOVE (s = 1)', () => {
      const fsm = new TouchFsm(1.0);
      // Start touch
      fsm.onTouchStart([{ id: 1, x: 0, y: 0 }], 0);

      // 10 steps of 30 px over 200 ms (20 ms per step -> v = 30 / 20 = 1.5 px/ms)
      let totalDx = 0;
      for (let i = 1; i <= 10; i++) {
        const time = i * 20;
        const currentX = i * 30;
        const out = fsm.onTouchMove([{ id: 1, x: currentX, y: 0 }], time);
        for (const m of out) {
          if (m.type === 'move') {
            totalDx += m.dx;
          }
        }
      }

      // Fast flick requirement: total MOVE >= 1920 px
      expect(totalDx).toBeGreaterThanOrEqual(1920);
    });
  });
});
