export type ConnectionState = 'connecting' | 'connected' | 'no_token' | 'replaced' | 'invalid_token';

export interface ConnectionOptions {
  onStateChange?: (state: ConnectionState) => void;
  onMessage?: (msg: any) => void;
  onBinary?: (buf: ArrayBuffer) => void;
}

export class PhantomConnection {
  private ws: WebSocket | null = null;
  private pingInterval: any = null;
  private timeoutTimer: any = null;
  private reconnectAttempt = 0;
  private reconnectTimeout: any = null;
  private token: string | null = null;
  private options: ConnectionOptions;
  private lastMessageTime = performance.now();
  private currentState: ConnectionState = 'connecting';
  private hasOpened = false;

  constructor(options: ConnectionOptions = {}) {
    this.options = options;
    this.initTokenAndConnect();
  }

  public async checkPairToken(token: string): Promise<boolean> {
    try {
      const resp = await fetch(`/pair/check?k=${encodeURIComponent(token)}`);
      return resp.status === 200;
    } catch {
      return true; // network error, don't assume invalid
    }
  }

  private async initTokenAndConnect() {
    const params = new URLSearchParams(window.location.search);
    const urlToken = params.get('k');
    const fallbackIp = params.get('ip');
    if (fallbackIp) {
      localStorage.setItem('phantom_fallback_ip', fallbackIp);
    }
    const storedToken = localStorage.getItem('phantom_token');

    if (urlToken && storedToken && urlToken !== storedToken) {
      const isValid = await this.checkPairToken(urlToken);
      if (isValid) {
        localStorage.setItem('phantom_token', urlToken);
        this.token = urlToken;
      } else {
        this.token = storedToken;
      }
    } else if (urlToken) {
      const isValid = await this.checkPairToken(urlToken);
      if (isValid) {
        localStorage.setItem('phantom_token', urlToken);
        this.token = urlToken;
      } else {
        this.token = urlToken;
        this.currentState = 'invalid_token';
        this.options.onStateChange?.('invalid_token');
        return;
      }
    } else {
      this.token = storedToken;
    }

    if (!this.token) {
      this.currentState = 'no_token';
      this.options.onStateChange?.('no_token');
      return;
    }

    this.connect();
  }

  private connect() {
    if (!this.token) return;
    this.hasOpened = false;
    this.currentState = 'connecting';
    this.options.onStateChange?.('connecting');
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${proto}//${window.location.host}/ws`;
    this.ws = new WebSocket(wsUrl);
    this.ws.binaryType = 'arraybuffer';

    this.ws.onopen = () => {
      this.hasOpened = true;
      this.reconnectAttempt = 0;
      this.ws?.send(JSON.stringify({ t: 'hello', v: 1, client: 'pwa', token: this.token }));
      this.startPing();
      this.resetTimeout();
    };

    this.ws.onmessage = (event) => {
      this.lastMessageTime = performance.now();
      this.resetTimeout();
      if (typeof event.data === 'string') {
        try {
          const obj = JSON.parse(event.data);
          if (obj.t === 'err' && obj.code === 'unauthorized') {
            // The TV no longer accepts this token (pairing was reset). Retrying with it would loop
            // every ~0.6 s and get this phone banned by the TV's rate limiter: stop and re-pair.
            localStorage.removeItem('phantom_token');
            this.token = null;
            this.cleanup();
            if (this.ws) {
              this.ws.onclose = null;
              this.ws.close();
            }
            this.currentState = 'no_token';
            this.options.onStateChange?.('no_token');
            return;
          }
          if (obj.t === 'hello') {
            this.currentState = 'connected';
            this.options.onStateChange?.('connected');
          }
          this.options.onMessage?.(obj);
        } catch (e) {}
      } else if (event.data instanceof ArrayBuffer) {
        this.options.onBinary?.(event.data);
      }
    };

    this.ws.onclose = (event: CloseEvent) => {
      if (event.code === 4003) {
        localStorage.removeItem('phantom_token');
        this.token = null;
        this.currentState = 'no_token';
        this.options.onStateChange?.('no_token');
        this.cleanup();
        return;
      }
      if (event.code === 4001) {
        this.currentState = 'replaced';
        this.options.onStateChange?.('replaced');
        this.cleanup();
        return;
      }

      if (!this.hasOpened && this.token) {
        this.cleanup();
        this.checkPairToken(this.token).then((isValid) => {
          if (!isValid) {
            this.currentState = 'invalid_token';
            this.options.onStateChange?.('invalid_token');
          } else {
            this.currentState = 'connecting';
            this.options.onStateChange?.('connecting');
            this.scheduleReconnect();
          }
        });
        return;
      }

      this.currentState = 'connecting';
      this.options.onStateChange?.('connecting');
      this.cleanup();
      this.scheduleReconnect();
    };

    this.ws.onerror = () => {
      this.ws?.close();
    };
  }

  private startPing() {
    this.pingInterval = setInterval(() => {
      this.ws?.send(JSON.stringify({ t: 'ping' }));
    }, 5000);
  }

  private resetTimeout() {
    if (this.timeoutTimer) clearTimeout(this.timeoutTimer);
    this.timeoutTimer = setTimeout(() => {
      this.ws?.close();
    }, 10000);
  }

  private cleanup() {
    if (this.pingInterval) clearInterval(this.pingInterval);
    if (this.timeoutTimer) clearTimeout(this.timeoutTimer);
    if (this.reconnectTimeout) clearTimeout(this.reconnectTimeout);
    this.pingInterval = null;
    this.timeoutTimer = null;
    this.reconnectTimeout = null;
  }

  private scheduleReconnect() {
    if (this.reconnectAttempt >= 3 && typeof window !== 'undefined' && window.location.hostname.endsWith('.local')) {
      const fallbackIp = localStorage.getItem('phantom_fallback_ip');
      if (fallbackIp && fallbackIp !== window.location.hostname) {
        const port = window.location.port ? `:${window.location.port}` : '';
        const token = this.token ? `?k=${encodeURIComponent(this.token)}` : '';
        window.location.href = `http://${fallbackIp}${port}/${token}`;
        return;
      }
    }

    const delays = [500, 1000, 2000, 5000];
    const delay = delays[Math.min(this.reconnectAttempt, delays.length - 1)];
    this.reconnectAttempt++;
    if (this.reconnectTimeout) clearTimeout(this.reconnectTimeout);
    this.reconnectTimeout = setTimeout(() => {
      this.connect();
    }, delay);
  }

  public getLastMessageTime(): number {
    return this.lastMessageTime;
  }

  public isConnected(): boolean {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN && this.currentState === 'connected';
  }

  public getState(): ConnectionState {
    return this.currentState;
  }

  public reconnect() {
    this.cleanup();
    if (this.ws) {
      try { this.ws.close(); } catch {}
      this.ws = null;
    }
    this.reconnectAttempt = 0;
    this.connect();
  }

  public sendJson(obj: any) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(obj));
    }
  }

  public sendBinary(buf: ArrayBuffer) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(buf);
    }
  }
}
