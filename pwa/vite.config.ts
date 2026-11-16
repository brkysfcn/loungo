import { defineConfig } from 'vite';

// Build id baked into the bundle and written to build.txt; the TV app sends the served id in
// `hello`, so a phone still running an older cached bundle reloads itself.
const PWA_BUILD = Date.now().toString(36);

export default defineConfig({
  base: './',
  define: {
    __PWA_BUILD__: JSON.stringify(PWA_BUILD),
  },
  plugins: [
    {
      name: 'phantom-build-id',
      generateBundle() {
        this.emitFile({ type: 'asset', fileName: 'build.txt', source: PWA_BUILD });
      },
    },
  ],
  build: {
    outDir: '../app/src/main/assets/pwa',
    emptyOutDir: true,
    // Android 10 phones may still run WebView 74 (no `??` / `?.`); the remote app shows this page.
    target: ['es2019', 'chrome74', 'safari13'],
    cssTarget: ['chrome74', 'safari13'],
  },
});
