import http from 'http';
import fs from 'fs';
import path from 'path';
import { chromium } from 'playwright';

const pwaDist = path.resolve('../app/src/main/assets/pwa');

const server = http.createServer((req, res) => {
  let reqPath = req.url.split('?')[0];
  if (reqPath === '/' || reqPath === '/index.html') {
    let html = fs.readFileSync(path.join(pwaDist, 'index.html'), 'utf8');
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(html);
    return;
  }

  const filePath = path.join(pwaDist, reqPath);
  if (fs.existsSync(filePath)) {
    const ext = path.extname(filePath);
    const contentType = ext === '.css' ? 'text/css' : ext === '.js' ? 'application/javascript' : 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': contentType });
    res.end(fs.readFileSync(filePath));
  } else {
    res.writeHead(404);
    res.end('Not found');
  }
});

server.listen(4173, '127.0.0.1', async () => {
  console.log('Server started on 4173');
  try {
    const browser = await chromium.launch({
      channel: 'msedge',
      headless: true,
    });
    const context = await browser.newContext({
      viewport: { width: 390, height: 844 },
      deviceScaleFactor: 3,
      isMobile: true,
      hasTouch: true,
    });
    const page = await context.newPage();
    await page.goto('http://127.0.0.1:4173/');

    // 1. docs/son_panel.png: Oynatıcı paneli açık (#player-panel display: flex, oynatıcı kontrolleri görünür). Yüzen ses düğmeleri (#vol-controls) GİZLİ (display: none).
    await page.evaluate(() => {
      const panel = document.getElementById('player-panel');
      if (panel) {
        panel.style.display = 'flex';
        // fill sample title if empty
        const title = document.getElementById('player-title');
        if (title) title.textContent = 'Örnek Film — 1080p';
      }
      const vol = document.getElementById('vol-controls');
      if (vol) vol.style.display = 'none';
      const banner = document.getElementById('media-banner');
      if (banner) banner.style.display = 'none';
      const yaz = document.getElementById('yaz-button');
      if (yaz) yaz.style.display = 'none';
    });

    await page.waitForTimeout(500);
    const panelPath = path.resolve('../docs/son_panel.png');
    await page.screenshot({ path: panelPath });
    console.log('Saved son_panel.png to:', panelPath);

    // 2. docs/son_banner.png: "Video bulundu" bildirimi (#media-banner) "Yaz" düğmesinin (#yaz-button) üstünde çakışmadan duruyor.
    await page.evaluate(() => {
      const panel = document.getElementById('player-panel');
      if (panel) panel.style.display = 'none';
      const vol = document.getElementById('vol-controls');
      if (vol) vol.style.display = 'flex';

      const banner = document.getElementById('media-banner');
      if (banner) {
        banner.style.display = 'block';
        banner.textContent = '🎬 Video bulundu — Native oynatıcıda aç';
      }
      const yaz = document.getElementById('yaz-button');
      if (yaz) {
        yaz.style.display = 'block';
      }
    });

    await page.waitForTimeout(500);
    const bannerPath = path.resolve('../docs/son_banner.png');
    await page.screenshot({ path: bannerPath });
    console.log('Saved son_banner.png to:', bannerPath);

    await browser.close();
  } catch (err) {
    console.error('Playwright error:', err);
  } finally {
    server.close();
  }
});
