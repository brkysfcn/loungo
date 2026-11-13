const WebSocket = require('ws');

const args = process.argv.slice(2);
const ipArg = args[0] || '127.0.0.1';
const cmdString = args[1] || 'hello';
const token = args[2] || process.env.DEV_TOKEN || 'test123';
const port = args[3] || process.env.WS_PORT || (ipArg.includes(':') ? ipArg.split(':')[1] : '8765');
const host = ipArg.includes(':') ? ipArg.split(':')[0] : ipArg;

const url = `ws://${host}:${port}/ws?k=${token}`;
let ws = null;
let connected = false;
let retryCount = 0;
const MAX_RETRIES = 5;

let helloInfo = { w: 1920, h: 1080, dpr: 1.0 };
let lastMediaUrl = null;
let firstTextTrackId = null;

function parseCommands(str) {
    const delimiter = str.includes(';') ? ';' : ',';
    return str.split(delimiter).map(s => s.trim()).filter(s => s.length > 0);
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function connect() {
    ws = new WebSocket(url);

    ws.on('open', () => {
        connected = true;
        ws.send(JSON.stringify({ t: 'hello', v: 1, client: 'devclient' }));
    });

    ws.on('message', async (data) => {
        const text = data.toString();
        console.log('RECV:', text);
        try {
            const obj = JSON.parse(text);
            if (obj.t === 'media' && obj.url) {
                const u = obj.url.toLowerCase();
                if (!u.includes('/bn/') && !u.includes('cdnfly.net')) {
                    lastMediaUrl = obj.url;
                    console.log('CAPTURED_MEDIA:', lastMediaUrl);
                } else {
                    console.log('IGNORED_AD_MEDIA:', obj.url);
                }
            }
            if (obj.t === 'player' && obj.tracks && obj.tracks.text && obj.tracks.text.length > 0) {
                firstTextTrackId = obj.tracks.text[0].id;
                console.log('CAPTURED_TEXT_TRACK:', firstTextTrackId);
            }
            if (obj.t === 'hello') {
                if (obj.w) helloInfo.w = obj.w;
                if (obj.h) helloInfo.h = obj.h;
                if (obj.dpr) helloInfo.dpr = obj.dpr;

                if (cmdString !== 'hello') {
                    await runCommands(parseCommands(cmdString));
                } else {
                    setTimeout(() => ws.close(), 500);
                }
            }
        } catch (e) {
            // binary or non-json message
        }
    });

    ws.on('error', (err) => {
        // ws.on('close') will handle retry
    });

    ws.on('close', () => {
        if (!connected && retryCount < MAX_RETRIES) {
            retryCount++;
            setTimeout(connect, 1000);
        } else if (!connected) {
            console.log('WS connection failed after retries');
        }
    });
}

connect();

async function runCommands(cmds) {
    for (const c of cmds) {
        const parts = c.split(/\s+/);
        const name = parts[0];
        const subArgs = parts.slice(1);

        if (name === 'nav') {
            const targetUrl = subArgs[0] || 'https://www.google.com';
            ws.send(JSON.stringify({ t: 'nav', url: targetUrl }));
            await sleep(500);
        } else if (name === 'adblock') {
            const on = subArgs[0] === 'true' || subArgs[0] === '1' || subArgs[0] === 'on';
            ws.send(JSON.stringify({ t: 'adblock', on }));
            await sleep(500);
        } else if (name === 'wait') {
            const ms = parseInt(subArgs[0] || '1000', 10);
            await sleep(ms);
        } else if (name === 'at') {
            const cssX = parseFloat(subArgs[0] || '0');
            const cssY = parseFloat(subArgs[1] || '0');
            const x = (cssX * helloInfo.dpr) / helloInfo.w;
            const y = (cssY * helloInfo.dpr) / helloInfo.h;
            ws.send(JSON.stringify({ t: 'pointer', x, y }));
            await sleep(100);
        } else if (name === 'click') {
            // down btn=0
            sendButton(true, 0);
            await sleep(50);
            // up btn=0
            sendButton(false, 0);
            await sleep(100);
        } else if (name === 'down') {
            sendButton(true, 0);
            await sleep(50);
        } else if (name === 'up') {
            sendButton(false, 0);
            await sleep(50);
        } else if (name === 'move') {
            const dx = parseInt(subArgs[0] || '0', 10);
            const dy = parseInt(subArgs[1] || '0', 10);
            sendMove(dx, dy);
            await sleep(100);
        } else if (name === 'drag') {
            const dxTotal = parseInt(subArgs[0] || '0', 10);
            const dyTotal = parseInt(subArgs[1] || '0', 10);
            const n = parseInt(subArgs[2] || '10', 10);
            sendButton(true, 0);
            await sleep(50);
            const stepDx = Math.round(dxTotal / n);
            const stepDy = Math.round(dyTotal / n);
            for (let i = 0; i < n; i++) {
                sendMove(stepDx, stepDy);
                await sleep(20);
            }
            sendButton(false, 0);
            await sleep(100);
        } else if (name === 'scroll') {
            let sx = 0;
            let sy = 0;
            if (subArgs.length === 1) {
                sy = parseInt(subArgs[0] || '0', 10);
            } else {
                sx = parseInt(subArgs[0] || '0', 10);
                sy = parseInt(subArgs[1] || '0', 10);
            }
            sendScroll(sx, sy);
            await sleep(100);
        } else if (name === 'media_open') {
            let mediaUrl = subArgs[0];
            if (!mediaUrl || mediaUrl === 'auto') {
                if (lastMediaUrl) {
                    mediaUrl = lastMediaUrl;
                } else {
                    console.log('NO_MEDIA_DETECTED: auto media_open skipped');
                    await sleep(200);
                    continue;
                }
            }
            console.log('OPENING_MEDIA:', mediaUrl);
            ws.send(JSON.stringify({ t: 'media_open', url: mediaUrl }));
            await sleep(500);
        } else if (name === 'player_cmd') {
            const action = subArgs[0] || 'play';
            const msg = { t: 'player_cmd', a: action };
            if (action === 'seek') {
                const ms = subArgs[1] !== undefined ? parseInt(subArgs[1], 10) : 0;
                msg.ms = ms;
            } else if (action === 'track') {
                msg.type = subArgs[1] || 'text';
                msg.id = subArgs[2] === 'auto' ? (firstTextTrackId || '0') : (subArgs[2] || '0');
            } else if (subArgs[1] !== undefined && !isNaN(parseInt(subArgs[1], 10))) {
                msg.ms = parseInt(subArgs[1], 10);
            }
            console.log('SEND_PLAYER_CMD:', msg);
            ws.send(JSON.stringify(msg));
            await sleep(300);
        } else if (name === 'vol') {
            const action = subArgs[0] || 'up';
            ws.send(JSON.stringify({ t: 'vol', a: action }));
            await sleep(300);
        } else if (name === 'back') {
            ws.send(JSON.stringify({ t: 'back' }));
            await sleep(300);
        } else {
            console.log('Unknown command part:', c);
        }
    }
    setTimeout(() => ws.close(), 500);
}

function sendMove(dx, dy) {
    const buf = Buffer.alloc(5);
    buf.writeUInt8(0x01, 0);
    buf.writeInt16LE(dx, 1);
    buf.writeInt16LE(dy, 3);
    ws.send(buf);
}

function sendButton(down, btn) {
    console.log('SEND_BUTTON:', down, btn);
    const buf = Buffer.alloc(3);
    buf.writeUInt8(0x03, 0);
    buf.writeUInt8(down ? 1 : 0, 1);
    buf.writeUInt8(btn, 2);
    ws.send(buf);
}

function sendScroll(sx, sy) {
    const buf = Buffer.alloc(5);
    buf.writeUInt8(0x02, 0);
    buf.writeInt16LE(sx, 1);
    buf.writeInt16LE(sy, 3);
    ws.send(buf);
}
