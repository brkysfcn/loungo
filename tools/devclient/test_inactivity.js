const WebSocket = require('ws');

const ip = process.argv[2] || '127.0.0.1';
const url = `ws://${ip}:8765/ws?k=test123`;
console.log(new Date().toISOString(), 'Connecting to', url);
const ws = new WebSocket(url);

ws.on('open', () => {
    console.log(new Date().toISOString(), 'OPEN. Sending hello...');
    ws.send(JSON.stringify({ t: 'hello', v: 1, client: 'inactivity-tester' }));
});

ws.on('message', (data) => {
    console.log(new Date().toISOString(), 'RECV:', data.toString());
    console.log(new Date().toISOString(), 'Waiting 22s for server inactivity timeout...');
});

ws.on('close', (code, reason) => {
    console.log(new Date().toISOString(), `CLOSED code=${code} reason=${reason.toString()}`);
    process.exit(0);
});

ws.on('error', (err) => {
    console.log(new Date().toISOString(), 'ERR:', err.message);
});
