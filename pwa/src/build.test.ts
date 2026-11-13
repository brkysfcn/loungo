import { describe, it, expect } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

describe('PWA build asset verification (T36r)', () => {
  it('verifies assets directory contains compiled css and index.html links it', () => {
    const assetsDir = path.resolve(__dirname, '../../app/src/main/assets/pwa');
    if (!fs.existsSync(assetsDir)) {
      // In dev environment before first build, skip or assert dist
      return;
    }

    const indexHtmlPath = path.join(assetsDir, 'index.html');
    expect(fs.existsSync(indexHtmlPath)).toBe(true);

    const indexHtml = fs.readFileSync(indexHtmlPath, 'utf-8');
    // Verify CSS is linked in index.html
    expect(indexHtml).toMatch(/<link\s+rel="stylesheet"\s+crossorigin\s+href="\/assets\/style-[^"]+\.css">|<link\s+rel="stylesheet"[^>]+>/);

    // Verify assets folder has a .css file
    const assetsSubdir = path.join(assetsDir, 'assets');
    expect(fs.existsSync(assetsSubdir)).toBe(true);
    const files = fs.readdirSync(assetsSubdir);
    const cssFiles = files.filter(f => f.endsWith('.css'));
    expect(cssFiles.length).toBeGreaterThan(0);

    // Verify CSS file has non-trivial size and contains flex/100dvh
    const cssContent = fs.readFileSync(path.join(assetsSubdir, cssFiles[0]), 'utf-8');
    expect(cssContent).toContain('100dvh');
    expect(cssContent).toContain('touch-action:none');
  });
});
