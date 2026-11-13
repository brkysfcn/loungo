import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { PhantomConnection, ConnectionState } from './conn';

class MockWebSocket {
  static OPEN = 1;
  static CLOSED = 3;
  static instances: MockWebSocket[] = [];
  url: string;
  binaryType = 'blob';
  readyState = 0; // CONNECTING
  onopen: (() => void) | null = null;
  onmessage: ((event: any) => void) | null = null;
  onclose: ((event: any) => void) | null = null;
  onerror: (() => void) | null = null;

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }

  send = vi.fn();
  close = vi.fn((code?: number, reason?: string) => {
    this.readyState = 3; // CLOSED
    this.onclose?.({ code: code ?? 1000, reason: reason ?? '' });
  });
}

class MockStorage {
  private store = new Map<string, string>();
  getItem(k: string) { return this.store.get(k) ?? null; }
  setItem(k: string, v: string) { this.store.set(k, v); }
  removeItem(k: string) { this.store.delete(k); }
  clear() { this.store.clear(); }
}

describe('PhantomConnection state machine (T36f)', () => {
  let mockStorage: MockStorage;

  beforeEach(() => {
    vi.useFakeTimers();
    mockStorage = new MockStorage();
    (globalThis as any).localStorage = mockStorage;
    (globalThis as any).window = {
      location: {
        search: '',
        protocol: 'http:',
        host: 'localhost:8765',
      },
    };
    MockWebSocket.instances = [];
    (globalThis as any).WebSocket = MockWebSocket;
    mockStorage.setItem('phantom_token', 'test-token-123');
  });

  afterEach(() => {
    vi.useRealTimers();
    mockStorage.clear();
  });

  it('connects and reaches connected state on hello', () => {
    let currentState: ConnectionState | null = null;
    const conn = new PhantomConnection({
      onStateChange: (st) => {
        currentState = st;
      },
    });

    expect(currentState).toBe('connecting');
    const ws = MockWebSocket.instances[0];
    expect(ws).toBeDefined();

    ws.readyState = 1; // OPEN
    ws.onopen?.();

    // TV sends hello
    ws.onmessage?.({ data: JSON.stringify({ t: 'hello', v: 1, w: 1920, h: 1080 }) });
    expect(currentState).toBe('connected');
    expect(conn.getState()).toBe('connected');
    expect(conn.isConnected()).toBe(true);
  });

  it('enters replaced state on 4001 and does NOT reconnect on timer or idle', () => {
    let currentState: ConnectionState | null = null;
    const conn = new PhantomConnection({
      onStateChange: (st) => {
        currentState = st;
      },
    });

    const ws = MockWebSocket.instances[0];
    ws.readyState = 1;
    ws.onopen?.();
    ws.onmessage?.({ data: JSON.stringify({ t: 'hello', v: 1 }) });
    expect(currentState).toBe('connected');

    // Simulate 4001 replaced (another client connected)
    ws.close(4001, 'replaced');

    expect(currentState).toBe('replaced');
    expect(conn.getState()).toBe('replaced');
    expect(conn.isConnected()).toBe(false);

    // Fast forward timers (reconnect timeouts would fire here if scheduled)
    const initialInstanceCount = MockWebSocket.instances.length;
    vi.advanceTimersByTime(30000);

    // No new connection should have been attempted
    expect(MockWebSocket.instances.length).toBe(initialInstanceCount);
    expect(conn.getState()).toBe('replaced');
  });

  it('reconnects when reconnect() button is clicked after 4001', () => {
    let currentState: ConnectionState | null = null;
    const conn = new PhantomConnection({
      onStateChange: (st) => {
        currentState = st;
      },
    });

    const ws1 = MockWebSocket.instances[0];
    ws1.close(4001, 'replaced');
    expect(conn.getState()).toBe('replaced');

    // User taps "Buraya bağlan"
    conn.reconnect();

    expect(currentState).toBe('connecting');
    expect(conn.getState()).toBe('connecting');
    expect(MockWebSocket.instances.length).toBe(2);
    const ws2 = MockWebSocket.instances[1];
    ws2.readyState = 1;
    ws2.onopen?.();
    ws2.onmessage?.({ data: JSON.stringify({ t: 'hello', v: 1 }) });

    expect(conn.getState()).toBe('connected');
  });

  it('removes token and sets no_token on 4003', () => {
    let currentState: ConnectionState | null = null;
    const conn = new PhantomConnection({
      onStateChange: (st) => {
        currentState = st;
      },
    });

    const ws = MockWebSocket.instances[0];
    ws.close(4003, 'reset_pair');

    expect(currentState).toBe('no_token');
    expect(conn.getState()).toBe('no_token');
    expect(mockStorage.getItem('phantom_token')).toBeNull();
  });

  it('enters invalid_token when closed before onopen and pair/check returns 401', async () => {
    let currentState: ConnectionState | null = null;
    (globalThis as any).fetch = vi.fn().mockResolvedValue({ status: 401 });

    const conn = new PhantomConnection({
      onStateChange: (st) => {
        currentState = st;
      },
    });

    const ws = MockWebSocket.instances[0];
    // Closed before onopen
    ws.close(1006, 'Abnormal Closure');

    // Wait for fetch microtask
    await vi.waitFor(() => {
      expect(currentState).toBe('invalid_token');
    });

    expect(conn.getState()).toBe('invalid_token');

    // Does NOT auto-reconnect on timers
    const initialInstanceCount = MockWebSocket.instances.length;
    vi.advanceTimersByTime(30000);
    expect(MockWebSocket.instances.length).toBe(initialInstanceCount);
  });

  it('preserves valid stored token if URL token check returns 401', async () => {
    mockStorage.setItem('phantom_token', 'valid-stored-token');
    (globalThis as any).window.location.search = '?k=old-stale-url-token';
    (globalThis as any).fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('old-stale-url-token')) {
        return Promise.resolve({ status: 401 });
      }
      return Promise.resolve({ status: 200 });
    });

    const conn = new PhantomConnection();
    expect(conn).toBeDefined();

    await vi.waitFor(() => {
      expect(MockWebSocket.instances.length).toBeGreaterThan(0);
    });

    // Stored token was preserved and used for WS connection
    expect(mockStorage.getItem('phantom_token')).toBe('valid-stored-token');
    expect(MockWebSocket.instances[0].url).toBe('ws://localhost:8765/ws');
    MockWebSocket.instances[0].onopen?.();
    expect(MockWebSocket.instances[0].send).toHaveBeenCalledWith(JSON.stringify({ t: 'hello', v: 1, client: 'pwa', token: 'valid-stored-token' }));
  });

  it('updates stored token if URL token check returns 200', async () => {
    mockStorage.setItem('phantom_token', 'old-stored-token');
    (globalThis as any).window.location.search = '?k=new-qr-token';
    (globalThis as any).fetch = vi.fn().mockResolvedValue({ status: 200 });

    const conn = new PhantomConnection();
    expect(conn).toBeDefined();

    await vi.waitFor(() => {
      expect(mockStorage.getItem('phantom_token')).toBe('new-qr-token');
    });

    expect(MockWebSocket.instances[0].url).toBe('ws://localhost:8765/ws');
    MockWebSocket.instances[0].onopen?.();
    expect(MockWebSocket.instances[0].send).toHaveBeenCalledWith(JSON.stringify({ t: 'hello', v: 1, client: 'pwa', token: 'new-qr-token' }));
  });
});
