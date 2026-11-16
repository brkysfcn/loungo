import { t } from './i18n';
import { shouldReplaceMedia } from './media';
const trStr = t();
import './style.css';
import { PhantomConnection, ConnectionState } from './conn';
import { Trackpad } from './trackpad';
import { KeyboardManager } from './keyboard';
import { FavoriteStore } from './favorites';
import { PlayerPanel } from './player';
import { createOnboarding, makeFullscreenButton, detectPlatform, isAndroid } from './onboarding';
import { inApp, notifyApp } from './app';

// Prevent iOS Safari gestures (pinch-zoom, multi-finger browser gestures)
document.addEventListener('gesturestart', (e) => e.preventDefault(), { passive: false });
document.addEventListener('gesturechange', (e) => e.preventDefault(), { passive: false });
document.addEventListener('gestureend', (e) => e.preventDefault(), { passive: false });

const app = document.getElementById('app') || document.body;
app.innerHTML = '';

const favoriteStore = new FavoriteStore();

// Load sensitivity from localStorage (0.5 to 3.0, default 1.0)
const savedSensStr = localStorage.getItem('phantom_sensitivity');
let sensitivity = savedSensStr ? parseFloat(savedSensStr) : 1.0;
if (isNaN(sensitivity) || sensitivity < 0.5 || sensitivity > 3.0) {
  sensitivity = 1.0;
}

// 1. Top Navigation Bar
const navBar = document.createElement('header');
navBar.id = 'nav-bar';
navBar.style.display = 'flex';
navBar.style.flexDirection = 'column';
navBar.style.background = '#1c1c1e';
navBar.style.borderBottom = '1px solid #28282b';
navBar.style.position = 'relative';
navBar.style.zIndex = '50';
navBar.style.padding = '8px';

const SVG_BACK = '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"></polyline></svg>';
const SVG_FWD = '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"></polyline></svg>';
const SVG_RELOAD = '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"></polyline><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path></svg>';
const SVG_STOP = '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>';
const SVG_GEAR = '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>';
const SVG_STAR_FILLED = '<svg width="22" height="22" viewBox="0 0 24 24" fill="#ffd60a" stroke="#ffd60a" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"></polygon></svg>';
const SVG_STAR_OUTLINE = '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#8e8e93" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"></polygon></svg>';

// Navigation Controls Row
const navRow = document.createElement('div');
navRow.style.display = 'flex';
navRow.style.alignItems = 'center';
navRow.style.gap = '6px';
navRow.style.width = '100%';

// Back button
const backBtn = document.createElement('button');
backBtn.id = 'btn-back';
backBtn.innerHTML = SVG_BACK;
backBtn.title = 'Geri';
backBtn.disabled = true;
styleNavButton(backBtn);

// Forward button
const fwdBtn = document.createElement('button');
fwdBtn.id = 'btn-fwd';
fwdBtn.innerHTML = SVG_FWD;
fwdBtn.title = 'İleri';
fwdBtn.disabled = true;
styleNavButton(fwdBtn);

// Reload / Stop button
const reloadStopBtn = document.createElement('button');
reloadStopBtn.id = 'btn-reload';
reloadStopBtn.innerHTML = SVG_RELOAD;
reloadStopBtn.title = 'Yenile';
styleNavButton(reloadStopBtn);

// URL input
const urlInput = document.createElement('input');
urlInput.id = 'url-input';
urlInput.type = 'search';
urlInput.setAttribute('inputmode', 'search');
urlInput.setAttribute('enterkeyhint', 'go');
urlInput.setAttribute('autocapitalize', 'off');
urlInput.setAttribute('autocorrect', 'off');
urlInput.setAttribute('spellcheck', 'false');
urlInput.placeholder = 'Adres veya arama yazın...';
urlInput.style.flex = '1';
urlInput.style.minWidth = '0';
urlInput.style.height = '34px';
urlInput.style.padding = '0 10px';
urlInput.style.background = '#222226';
urlInput.style.border = '1px solid #38383e';
urlInput.style.borderRadius = '8px';
urlInput.style.color = '#ffffff';
urlInput.style.fontSize = '16px';
urlInput.style.outline = 'none';
urlInput.style.webkitAppearance = 'none';
(urlInput.style as any).appearance = 'none';
urlInput.style.fontFamily = 'inherit';
urlInput.style.fontWeight = '500';
(urlInput.style as any).webkitFontSmoothing = 'antialiased';

// Favorite button (★)
const favBtn = document.createElement('button');
favBtn.id = 'btn-fav';
favBtn.title = 'Favorilere ekle';
favBtn.innerHTML = SVG_STAR_OUTLINE;
styleNavButton(favBtn);
favBtn.style.padding = '4px 6px';

// Status dot
const statusDot = document.createElement('span');
statusDot.id = 'status-dot';
statusDot.style.width = '8px';
statusDot.style.height = '8px';
statusDot.style.borderRadius = '50%';
statusDot.style.background = '#ff9800'; // connecting
statusDot.style.display = 'inline-block';
statusDot.style.flexShrink = '0';
statusDot.style.margin = '0 2px';
statusDot.title = 'Bağlantı durumu';

// Gear / Settings button
const gearBtn = document.createElement('button');
gearBtn.id = 'gear-btn';
gearBtn.innerHTML = SVG_GEAR;
gearBtn.title = 'Ayarlar';
styleNavButton(gearBtn);

navRow.appendChild(backBtn);
navRow.appendChild(fwdBtn);
navRow.appendChild(reloadStopBtn);
navRow.appendChild(urlInput);
navRow.appendChild(favBtn);
// Sekmeler kullanilmiyor (T36 iptal): sekme dugmesi gosterilmez.
navRow.appendChild(statusDot);
navRow.appendChild(gearBtn);
navBar.appendChild(navRow);

// Progress bar (thin line along bottom of nav bar)
const progressBar = document.createElement('div');
progressBar.id = 'progress-bar';
progressBar.style.position = 'absolute';
progressBar.style.bottom = '0';
progressBar.style.left = '0';
progressBar.style.height = '2px';
progressBar.style.width = '0%';
progressBar.style.background = '#0a84ff';
progressBar.style.opacity = '0';
progressBar.style.transition = 'width 0.15s ease, opacity 0.2s ease';
navBar.appendChild(progressBar);

// Status banner for disconnected state
const statusBanner = document.createElement('div');
statusBanner.id = 'status-banner';
statusBanner.style.background = '#ff3b30';
statusBanner.style.color = '#ffffff';
statusBanner.style.fontSize = '12px';
statusBanner.style.fontWeight = '600';
statusBanner.style.textAlign = 'center';
statusBanner.style.padding = '4px 8px';
statusBanner.style.display = 'none';
statusBanner.textContent = 'Bağlantı yok — yeniden deneniyor';
navBar.appendChild(statusBanner);

// Favorites Dropdown
const favDropdown = document.createElement('div');
favDropdown.id = 'fav-dropdown';
favDropdown.style.display = 'none';
favDropdown.style.position = 'absolute';
favDropdown.style.top = '100%';
favDropdown.style.left = '8px';
favDropdown.style.right = '8px';
favDropdown.style.maxHeight = '280px';
favDropdown.style.background = '#1c1c1e';
favDropdown.style.border = '1px solid #38383e';
favDropdown.style.borderRadius = '10px';
favDropdown.style.boxShadow = '0 8px 24px rgba(0, 0, 0, 0.7)';
favDropdown.style.overflowY = 'auto';
(favDropdown.style as any).webkitOverflowScrolling = 'touch';
favDropdown.style.touchAction = 'pan-y';
favDropdown.style.zIndex = '100';
favDropdown.style.padding = '6px 0';
favDropdown.style.marginTop = '4px';

favDropdown.addEventListener('mousedown', (e) => {
  e.preventDefault();
});

navBar.appendChild(favDropdown);

// Top Safe Spacer (T28e: avoids iOS standalone status bar frosted glass blur)
const isStandalone = !!(
  window.matchMedia('(display-mode: standalone)').matches ||
  (navigator as unknown as { standalone?: boolean }).standalone
);
const topSpacer = document.createElement('div');
topSpacer.id = 'top-safe-spacer';
topSpacer.style.width = '100%';
topSpacer.style.background = '#000000';
topSpacer.style.flexShrink = '0';
topSpacer.style.height = isStandalone
  ? 'max(env(safe-area-inset-top, 0px), 54px)'
  : 'env(safe-area-inset-top, 0px)';

app.appendChild(topSpacer);
app.appendChild(navBar);

function styleNavButton(btn: HTMLButtonElement) {
  btn.style.background = 'transparent';
  btn.style.border = 'none';
  btn.style.color = '#ffffff';
  btn.style.fontSize = '16px';
  btn.style.padding = '6px 8px';
  btn.style.cursor = 'pointer';
  btn.style.borderRadius = '6px';
  btn.style.display = 'flex';
  btn.style.alignItems = 'center';
  btn.style.justifyContent = 'center';
  btn.style.flexShrink = '0';
  btn.style.touchAction = 'manipulation';
  btn.style.userSelect = 'none';
  btn.style.transition = 'opacity 0.2s ease';
}

function updateButtonState(btn: HTMLButtonElement, enabled: boolean) {
  btn.disabled = !enabled;
  btn.style.opacity = enabled ? '1.0' : '0.4';
  btn.style.cursor = enabled ? 'pointer' : 'default';
}

updateButtonState(backBtn, false);
updateButtonState(fwdBtn, false);

// 2. Trackpad Area
const trackpadArea = document.createElement('main');
trackpadArea.id = 'trackpad';
trackpadArea.style.flex = '1';
trackpadArea.style.width = '100%';
trackpadArea.style.height = '100%';
trackpadArea.style.background = '#000000';
trackpadArea.style.touchAction = 'none';
trackpadArea.style.position = 'relative';
trackpadArea.style.display = 'flex';
trackpadArea.style.alignItems = 'center';
trackpadArea.style.justifyContent = 'center';

const trackpadHint = document.createElement('div');
trackpadHint.style.color = '#262626';
trackpadHint.style.fontSize = '13px';
trackpadHint.style.letterSpacing = '1px';
trackpadHint.style.pointerEvents = 'none';
trackpadHint.textContent = 'TRACKPAD';
trackpadArea.appendChild(trackpadHint);

// 2.2 Trackpad Volume Controls (T45)
const volControls = document.createElement('div');
volControls.id = 'vol-controls';
volControls.style.position = 'absolute';
volControls.style.right = '12px';
volControls.style.top = '50%';
volControls.style.transform = 'translateY(-50%)';
volControls.style.display = 'flex';
volControls.style.flexDirection = 'column';
volControls.style.gap = '14px';
volControls.style.zIndex = '25';

const stopVolBubble = (e: Event) => e.stopPropagation();
volControls.addEventListener('touchstart', stopVolBubble, { passive: true });
volControls.addEventListener('touchmove', stopVolBubble, { passive: true });
volControls.addEventListener('touchend', stopVolBubble, { passive: true });
volControls.addEventListener('mousedown', stopVolBubble);
volControls.addEventListener('mousemove', stopVolBubble);
volControls.addEventListener('mouseup', stopVolBubble);

const createTrackpadVolBtn = (text: string, action: 'up' | 'down' | 'mute', id: string) => {
  const btn = document.createElement('button');
  btn.id = id;
  btn.textContent = text;
  btn.style.width = '44px';
  btn.style.height = '44px';
  btn.style.borderRadius = '50%';
  btn.style.background = 'rgba(40, 40, 44, 0.85)';
  btn.style.border = '1px solid rgba(255, 255, 255, 0.2)';
  btn.style.color = '#ffffff';
  btn.style.fontSize = '15px';
  btn.style.fontWeight = '600';
  btn.style.display = 'flex';
  btn.style.alignItems = 'center';
  btn.style.justifyContent = 'center';
  btn.style.cursor = 'pointer';
  btn.style.userSelect = 'none';
  (btn.style as any).webkitUserSelect = 'none';
  btn.onclick = (e) => {
    e.stopPropagation();
    conn?.sendJson({ t: 'vol', a: action });
  };
  return btn;
};

const volUpBtn = createTrackpadVolBtn('🔊+', 'up', 'vol-btn-up');
const volDownBtn = createTrackpadVolBtn('🔉−', 'down', 'vol-btn-down');
const volMuteBtn = createTrackpadVolBtn('🔇', 'mute', 'vol-btn-mute');

volControls.appendChild(volUpBtn);
volControls.appendChild(volDownBtn);
volControls.appendChild(volMuteBtn);
trackpadArea.appendChild(volControls);

app.appendChild(trackpadArea);

// 2.5 Media Banner (T37)
const mediaBanner = document.createElement('div');
mediaBanner.id = 'media-banner';
mediaBanner.style.position = 'fixed';
mediaBanner.style.bottom = 'calc(env(safe-area-inset-bottom, 0px) + 84px)';
mediaBanner.style.left = '16px';
mediaBanner.style.right = '16px';
mediaBanner.style.background = 'linear-gradient(135deg, #0a84ff, #5e5ce6)';
mediaBanner.style.color = '#ffffff';
mediaBanner.style.border = 'none';
mediaBanner.style.borderRadius = '16px';
mediaBanner.style.padding = '16px 18px';
mediaBanner.style.fontSize = '17px';
mediaBanner.style.fontWeight = '700';
mediaBanner.style.textAlign = 'center';
mediaBanner.style.boxShadow = '0 6px 24px rgba(10,132,255,0.55)';
mediaBanner.style.animation = 'phantom-pulse 1.6s ease-in-out infinite';
{
  const st = document.createElement('style');
  st.textContent = '@keyframes phantom-pulse{0%,100%{transform:scale(1)}50%{transform:scale(1.03)}}';
  document.head.appendChild(st);
}
mediaBanner.style.cursor = 'pointer';
mediaBanner.style.display = 'none';
mediaBanner.style.zIndex = '55';
mediaBanner.style.touchAction = 'manipulation';
mediaBanner.textContent = '▶  Video bulundu — Tam ekran oynat';

let mediaBannerTimer: ReturnType<typeof setTimeout> | null = null;
let currentMediaUrl: string | null = null;
let currentMediaKind: string | null = null;
let mediaShownForCurrentPage = false;
let lastPageUrl = '';

mediaBanner.addEventListener('click', () => {
  if (currentMediaUrl) {
    conn.sendJson({ t: 'media_open', url: currentMediaUrl });
  }
  mediaBanner.style.display = 'none';
  if (mediaBannerTimer) clearTimeout(mediaBannerTimer);
});

app.appendChild(mediaBanner);

// 3.5 Invalid Token Modal (T29g)
const invalidTokenModal = document.createElement('div');
invalidTokenModal.id = 'invalid-token-modal';
invalidTokenModal.style.position = 'fixed';
invalidTokenModal.style.top = '0';
invalidTokenModal.style.left = '0';
invalidTokenModal.style.width = '100%';
invalidTokenModal.style.height = '100%';
invalidTokenModal.style.background = '#000000';
invalidTokenModal.style.display = 'none';
invalidTokenModal.style.alignItems = 'center';
invalidTokenModal.style.justifyContent = 'center';
invalidTokenModal.style.zIndex = '90';
invalidTokenModal.style.padding = '24px';

const invalidTokenCard = document.createElement('div');
invalidTokenCard.style.background = '#1c1c1e';
invalidTokenCard.style.border = '1px solid #333336';
invalidTokenCard.style.borderRadius = '14px';
invalidTokenCard.style.padding = '28px 24px';
invalidTokenCard.style.maxWidth = '360px';
invalidTokenCard.style.width = '100%';
invalidTokenCard.style.textAlign = 'center';
invalidTokenCard.style.color = '#ffffff';

const invalidIcon = document.createElement('div');
invalidIcon.textContent = '📱';
invalidIcon.style.fontSize = '40px';
invalidIcon.style.marginBottom = '14px';

const invalidTitle = document.createElement('div');
invalidTitle.textContent = 'Eşleşme Geçersiz';
invalidTitle.style.fontSize = '18px';
invalidTitle.style.fontWeight = 'bold';
invalidTitle.style.marginBottom = '10px';

const invalidText = document.createElement('div');
invalidText.textContent = "TV kumandasında OK tuşuna 1 sn basılı tutun ve çıkan QR'ı okutun.";
invalidText.style.fontSize = '14px';
invalidText.style.color = '#a1a1aa';
invalidText.style.lineHeight = '1.5';

invalidTokenCard.appendChild(invalidIcon);
invalidTokenCard.appendChild(invalidTitle);
invalidTokenCard.appendChild(invalidText);
invalidTokenModal.appendChild(invalidTokenCard);
app.appendChild(invalidTokenModal);

// 3. Settings Modal / Overlay
const settingsModal = document.createElement('div');
settingsModal.id = 'settings-modal';
settingsModal.style.position = 'fixed';
settingsModal.style.top = '0';
settingsModal.style.left = '0';
settingsModal.style.width = '100%';
settingsModal.style.height = '100%';
settingsModal.style.background = 'rgba(0, 0, 0, 0.85)';
settingsModal.style.display = 'none';
settingsModal.style.alignItems = 'center';
settingsModal.style.justifyContent = 'center';
settingsModal.style.zIndex = '100';

const modalCard = document.createElement('div');
modalCard.style.background = '#1c1c1e';
modalCard.style.border = '1px solid #333336';
modalCard.style.borderRadius = '14px';
modalCard.style.padding = '20px 24px';
modalCard.style.width = '88%';
modalCard.style.maxWidth = '360px';
modalCard.style.display = 'flex';
modalCard.style.flexDirection = 'column';
modalCard.style.gap = '16px';

const modalHeader = document.createElement('div');
modalHeader.style.display = 'flex';
modalHeader.style.justifyContent = 'space-between';
modalHeader.style.alignItems = 'center';

const modalTitle = document.createElement('h2');
modalTitle.textContent = trStr.settings;
modalTitle.style.margin = '0';
modalTitle.style.fontSize = '18px';
modalTitle.style.fontWeight = '600';
modalTitle.style.color = '#ffffff';

const closeBtn = document.createElement('button');
closeBtn.innerHTML = '&times;';
closeBtn.style.background = 'transparent';
closeBtn.style.border = 'none';
closeBtn.style.color = '#8e8e93';
closeBtn.style.fontSize = '24px';
closeBtn.style.cursor = 'pointer';
closeBtn.style.lineHeight = '1';
closeBtn.style.padding = '0 4px';

modalHeader.appendChild(modalTitle);
modalHeader.appendChild(closeBtn);
modalCard.appendChild(modalHeader);

// Sensitivity Slider Row
const sliderSection = document.createElement('div');
sliderSection.style.display = 'flex';
sliderSection.style.flexDirection = 'column';
sliderSection.style.gap = '8px';

const sliderLabelRow = document.createElement('div');
sliderLabelRow.style.display = 'flex';
sliderLabelRow.style.justifyContent = 'space-between';
sliderLabelRow.style.fontSize = '14px';
sliderLabelRow.style.color = '#cccccc';

const sliderTitle = document.createElement('span');
sliderTitle.textContent = trStr.cursorSensitivity;

const sliderValText = document.createElement('span');
sliderValText.textContent = `${sensitivity.toFixed(1)}×`;
sliderValText.style.fontWeight = 'bold';
sliderValText.style.color = '#0a84ff';

sliderLabelRow.appendChild(sliderTitle);
sliderLabelRow.appendChild(sliderValText);

const sliderInput = document.createElement('input');
sliderInput.type = 'range';
sliderInput.min = '0.5';
sliderInput.max = '3.0';
sliderInput.step = '0.1';
sliderInput.value = sensitivity.toString();
sliderInput.style.width = '100%';
sliderInput.style.cursor = 'pointer';

sliderSection.appendChild(sliderLabelRow);
sliderSection.appendChild(sliderInput);
modalCard.appendChild(sliderSection);

// Desktop Site Toggle Row
const desktopSection = document.createElement('div');
desktopSection.style.display = 'flex';
desktopSection.style.justifyContent = 'space-between';
desktopSection.style.alignItems = 'center';
desktopSection.style.padding = '4px 0';

const desktopLabel = document.createElement('span');
desktopLabel.textContent = trStr.desktopSite;
desktopLabel.style.fontSize = '14px';
desktopLabel.style.color = '#cccccc';

const desktopToggle = document.createElement('input');
desktopToggle.id = 'toggle-desktop';
desktopToggle.type = 'checkbox';
desktopToggle.style.width = '20px';
desktopToggle.style.height = '20px';
desktopToggle.style.cursor = 'pointer';

desktopSection.appendChild(desktopLabel);
desktopSection.appendChild(desktopToggle);
modalCard.appendChild(desktopSection);

desktopToggle.addEventListener('change', () => {
  const mode = desktopToggle.checked ? 'desktop' : 'mobile';
  conn.sendJson({ t: 'ua', mode });
});

// Light look: turns off background blur, which makes weak TVs stutter.
const liteSection = document.createElement('div');
liteSection.style.display = 'flex';
liteSection.style.justifyContent = 'space-between';
liteSection.style.alignItems = 'center';
liteSection.style.padding = '4px 0';
const liteLabel = document.createElement('span');
liteLabel.textContent = trStr.liteLook;
liteLabel.style.fontSize = '14px';
liteLabel.style.color = '#cccccc';
const liteSelect = document.createElement('select');
liteSelect.id = 'select-lite';
liteSelect.style.fontSize = '14px';
liteSelect.style.background = '#2c2c2e';
liteSelect.style.color = '#ffffff';
liteSelect.style.border = '1px solid #3a3a3c';
liteSelect.style.borderRadius = '6px';
liteSelect.style.padding = '4px 6px';
for (const [value, label] of [['auto', trStr.liteAuto], ['on', trStr.liteOn], ['off', trStr.liteOff]] as const) {
  const opt = document.createElement('option');
  opt.value = value;
  opt.textContent = label;
  liteSelect.appendChild(opt);
}
liteSection.appendChild(liteLabel);
liteSection.appendChild(liteSelect);
modalCard.appendChild(liteSection);
liteSelect.addEventListener('change', () => {
  conn.sendJson({ t: 'lite', mode: liteSelect.value as 'auto' | 'on' | 'off' });
});

// Adblock Section
const adblockSection = document.createElement('div');
adblockSection.style.display = 'flex';
adblockSection.style.justifyContent = 'space-between';
adblockSection.style.alignItems = 'center';
adblockSection.style.padding = '4px 0';

const adblockLabelCol = document.createElement('div');
adblockLabelCol.style.display = 'flex';
adblockLabelCol.style.flexDirection = 'column';
adblockLabelCol.style.gap = '2px';

const adblockLabel = document.createElement('span');
adblockLabel.textContent = trStr.adblockThisSite;
adblockLabel.style.fontSize = '14px';
adblockLabel.style.color = '#cccccc';

const adblockBlockedInfo = document.createElement('span');
adblockBlockedInfo.id = 'adblock-info';
adblockBlockedInfo.textContent = 'Bu sayfada 0 istek engellendi';
adblockBlockedInfo.style.fontSize = '11px';
adblockBlockedInfo.style.color = '#8e8e93';

adblockLabelCol.appendChild(adblockLabel);
adblockLabelCol.appendChild(adblockBlockedInfo);

const adblockUpdateBtn = document.createElement('button');
adblockUpdateBtn.id = 'btn-adblock-update';
adblockUpdateBtn.textContent = trStr.updateList;
adblockUpdateBtn.style.padding = '6px 10px';
adblockUpdateBtn.style.background = '#2c2c2e';
adblockUpdateBtn.style.color = '#0a84ff';
adblockUpdateBtn.style.border = '1px solid #333336';
adblockUpdateBtn.style.borderRadius = '6px';
adblockUpdateBtn.style.fontSize = '12px';
adblockUpdateBtn.style.fontWeight = '500';
adblockUpdateBtn.style.cursor = 'pointer';
adblockUpdateBtn.style.marginTop = '4px';
adblockUpdateBtn.style.alignSelf = 'flex-start';

adblockUpdateBtn.addEventListener('click', () => {
  conn.sendJson({ t: 'adblock_update' });
  adblockUpdateBtn.textContent = 'Güncelleme istendi...';
  setTimeout(() => {
    adblockUpdateBtn.textContent = trStr.updateList;
  }, 3000);
});

// The ad list updates itself on the TV every 7 days (AdBlockUpdater); no manual button.

const adblockToggle = document.createElement('input');
adblockToggle.id = 'toggle-adblock';
adblockToggle.type = 'checkbox';
adblockToggle.checked = true;
adblockToggle.style.width = '20px';
adblockToggle.style.height = '20px';
adblockToggle.style.cursor = 'pointer';

adblockToggle.addEventListener('change', () => {
  conn.sendJson({ t: 'adblock', on: adblockToggle.checked });
});

adblockSection.appendChild(adblockLabelCol);
adblockSection.appendChild(adblockToggle);
modalCard.appendChild(adblockSection);

// Text Zoom Slider Row
const zoomSection = document.createElement('div');
zoomSection.style.display = 'flex';
zoomSection.style.flexDirection = 'column';
zoomSection.style.gap = '8px';

const zoomLabelRow = document.createElement('div');
zoomLabelRow.style.display = 'flex';
zoomLabelRow.style.justifyContent = 'space-between';
zoomLabelRow.style.fontSize = '14px';
zoomLabelRow.style.color = '#cccccc';

const zoomTitle = document.createElement('span');
zoomTitle.textContent = trStr.fontSize;

const zoomValText = document.createElement('span');
zoomValText.id = 'zoom-val-text';
zoomValText.textContent = '100%';
zoomValText.style.fontWeight = 'bold';
zoomValText.style.color = '#0a84ff';

zoomLabelRow.appendChild(zoomTitle);
zoomLabelRow.appendChild(zoomValText);

const zoomInput = document.createElement('input');
zoomInput.id = 'zoom-slider';
zoomInput.type = 'range';
zoomInput.min = '50';
zoomInput.max = '300';
zoomInput.step = '10';
zoomInput.value = '100';
zoomInput.style.width = '100%';
zoomInput.style.cursor = 'pointer';

zoomSection.appendChild(zoomLabelRow);
zoomSection.appendChild(zoomInput);
modalCard.appendChild(zoomSection);

zoomInput.addEventListener('input', () => {
  zoomValText.textContent = `${zoomInput.value}%`;
});

zoomInput.addEventListener('change', () => {
  const text = parseInt(zoomInput.value, 10);
  if (!isNaN(text)) {
    conn.sendJson({ t: 'zoom', text });
  }
});

// Latency Diagnostic Section
const diagSection = document.createElement('div');
diagSection.style.display = 'flex';
diagSection.style.flexDirection = 'column';
diagSection.style.gap = '8px';
diagSection.style.paddingTop = '8px';
diagSection.style.borderTop = '1px solid #333336';

const diagBtn = document.createElement('button');
diagBtn.id = 'btn-diag';
diagBtn.textContent = trStr.latencyTest;
diagBtn.style.padding = '10px 14px';
diagBtn.style.background = '#0a84ff';
diagBtn.style.color = '#ffffff';
diagBtn.style.border = 'none';
diagBtn.style.borderRadius = '8px';
diagBtn.style.fontSize = '14px';
diagBtn.style.fontWeight = '600';
diagBtn.style.cursor = 'pointer';

const diagResult = document.createElement('div');
diagResult.id = 'diag-result';
diagResult.style.fontSize = '12px';
diagResult.style.color = '#a0a0a5';
diagResult.style.textAlign = 'center';
diagResult.style.minHeight = '18px';

diagSection.appendChild(diagBtn);
diagSection.appendChild(diagResult);
// Developer tools (latency / sharpness tests) are not shown to users.

let isRunningDiag = false;
let diagPendingPongCallback: (() => void) | null = null;

diagBtn.addEventListener('click', async () => {
  if (isRunningDiag) return;
  isRunningDiag = true;
  diagBtn.disabled = true;
  diagBtn.style.opacity = '0.6';
  diagResult.textContent = 'Test yapılıyor... (0 / 100)';

  const count = 100;
  const intervalMs = 50; // 20 Hz
  const rtts: number[] = [];
  const inFlightTimes: number[] = [];

  diagPendingPongCallback = () => {
    if (inFlightTimes.length > 0) {
      const sendTime = inFlightTimes.shift()!;
      const rtt = performance.now() - sendTime;
      rtts.push(rtt);
      diagResult.textContent = `Test yapılıyor... (${rtts.length} / ${count})`;
    }
  };

  for (let i = 0; i < count; i++) {
    inFlightTimes.push(performance.now());
    conn.sendJson({ t: 'ping' });
    await new Promise((r) => setTimeout(r, intervalMs));
  }

  // Wait 1 second for lingering pongs
  await new Promise((r) => setTimeout(r, 1000));
  diagPendingPongCallback = null;

  const lost = count - rtts.length;
  rtts.sort((a, b) => a - b);

  const p50 = rtts.length > 0 ? parseFloat(rtts[Math.floor(rtts.length * 0.50)].toFixed(1)) : 0;
  const p95 = rtts.length > 0 ? parseFloat(rtts[Math.floor(rtts.length * 0.95)].toFixed(1)) : 0;
  const max = rtts.length > 0 ? parseFloat(rtts[rtts.length - 1].toFixed(1)) : 0;

  diagResult.textContent = `p50: ${p50}ms | p95: ${p95}ms | max: ${max}ms | kayıp: ${lost}`;
  conn.sendJson({ t: 'diag', rtt: { p50, p95, max, lost } });

  isRunningDiag = false;
  diagBtn.disabled = false;
  diagBtn.style.opacity = '1.0';
});

// Reset Pair Section
const resetSection = document.createElement('div');
resetSection.style.display = 'flex';
resetSection.style.flexDirection = 'column';
resetSection.style.gap = '8px';
resetSection.style.paddingTop = '8px';
resetSection.style.borderTop = '1px solid #333336';

const resetBtn = document.createElement('button');
resetBtn.id = 'btn-reset-pair';
resetBtn.textContent = trStr.resetPair;
resetBtn.style.padding = '10px 14px';
resetBtn.style.background = '#3a1c1c';
resetBtn.style.color = '#ff453a';
resetBtn.style.border = '1px solid #ff453a';
resetBtn.style.borderRadius = '8px';
resetBtn.style.fontSize = '14px';
resetBtn.style.fontWeight = '600';
resetBtn.style.cursor = 'pointer';

resetBtn.addEventListener('click', () => {
  if (confirm('Eşleşmeyi sıfırlamak istiyor musunuz? Mevcut bağlantı kesilecek.')) {
    conn.sendJson({ t: 'reset_pair' });
    closeSettings();
  }
});

resetSection.appendChild(resetBtn);
modalCard.appendChild(resetSection);

// Sharpness Test Section (T28d)
const sharpSection = document.createElement('div');
sharpSection.style.display = 'flex';
sharpSection.style.flexDirection = 'column';
sharpSection.style.gap = '8px';
sharpSection.style.paddingTop = '8px';
sharpSection.style.borderTop = '1px solid #333336';

const sharpLink = document.createElement('a');
sharpLink.id = 'link-sharp-test';
sharpLink.href = '/test/sharp.html';
sharpLink.target = '_blank';
sharpLink.textContent = 'Netlik testi (/test/sharp.html)';
sharpLink.style.padding = '10px 14px';
sharpLink.style.background = '#1c1c1e';
sharpLink.style.color = '#0a84ff';
sharpLink.style.border = '1px solid #333336';
sharpLink.style.borderRadius = '8px';
sharpLink.style.fontSize = '14px';
sharpLink.style.fontWeight = '600';
sharpLink.style.textAlign = 'center';
sharpLink.style.textDecoration = 'none';

sharpSection.appendChild(sharpLink);

// Report Issue / Logs Section (B3)
const reportSection = document.createElement('div');
reportSection.style.display = 'flex';
reportSection.style.flexDirection = 'column';
reportSection.style.gap = '8px';
reportSection.style.paddingTop = '8px';
reportSection.style.borderTop = '1px solid #333336';

const reportBtn = document.createElement('button');
reportBtn.id = 'btn-report-issue';
reportBtn.textContent = trStr.reportIssue;
reportBtn.style.padding = '10px 14px';
reportBtn.style.background = '#2c2c2e';
reportBtn.style.color = '#0a84ff';
reportBtn.style.border = '1px solid #0a84ff';
reportBtn.style.borderRadius = '8px';
reportBtn.style.fontSize = '14px';
reportBtn.style.fontWeight = '600';
reportBtn.style.cursor = 'pointer';

reportBtn.addEventListener('click', () => {
  reportBtn.disabled = true;
  reportBtn.textContent = 'Kayıtlar alınıyor...';
  conn.sendJson({ t: 'get_logs' });
});

reportSection.appendChild(reportBtn);

const clearDataBtn = document.createElement('button');
clearDataBtn.id = 'btn-clear-data';
clearDataBtn.textContent = trStr.clearData;
clearDataBtn.style.padding = '10px 14px';
clearDataBtn.style.background = '#2c2c2e';
clearDataBtn.style.color = '#ff9f0a';
clearDataBtn.style.border = '1px solid #ff9f0a';
clearDataBtn.style.borderRadius = '8px';
clearDataBtn.style.fontSize = '14px';
clearDataBtn.style.fontWeight = '600';
clearDataBtn.style.cursor = 'pointer';
clearDataBtn.addEventListener('click', () => {
  if (!confirm(trStr.clearConfirm)) return;
  clearDataBtn.disabled = true;
  conn.sendJson({ t: 'clear_data' });
});
reportSection.appendChild(clearDataBtn);
modalCard.appendChild(reportSection);

// Usage tips row (replays onboarding) + Android fullscreen button
const onboarding = createOnboarding({ trackpad: trackpadArea, urlInput, volControls, gearBtn });
const tipsRow = document.createElement('div');
tipsRow.id = 'usage-tips-row';
tipsRow.style.display = 'flex';
tipsRow.style.flexDirection = 'column';
tipsRow.style.gap = '10px';
const tipsInner = document.createElement('div');
tipsInner.style.display = 'flex';
tipsInner.style.alignItems = 'center';
tipsInner.style.justifyContent = 'space-between';
tipsInner.style.gap = '12px';
const tipsLabel = document.createElement('span');
tipsLabel.textContent = trStr.onboarding.usageTips;
tipsLabel.style.color = '#ffffff';
tipsLabel.style.fontSize = '14px';
const tipsBtn = document.createElement('button');
tipsBtn.id = 'btn-usage-tips';
tipsBtn.textContent = trStr.onboarding.usageTipsBtn;
tipsBtn.style.cssText = 'padding:8px 14px;background:#2c2c2e;color:#0a84ff;border:1px solid #3a3a3c;border-radius:8px;font-size:14px;font-weight:600;cursor:pointer';
tipsBtn.addEventListener('click', () => {
  closeSettings();
  onboarding.replay();
});
tipsInner.appendChild(tipsLabel);
tipsInner.appendChild(tipsBtn);
tipsRow.appendChild(tipsInner);
if (!inApp && isAndroid(detectPlatform(navigator.userAgent || '', navigator.maxTouchPoints || 0))) {
  const fsBtn = makeFullscreenButton(trStr.onboarding.fullscreen);
  fsBtn.style.padding = '10px 14px';
  tipsRow.appendChild(fsBtn);
}
modalCard.appendChild(tipsRow);

settingsModal.appendChild(modalCard);
app.appendChild(settingsModal);

function openSettings() {
  settingsModal.style.display = 'flex';
}

function closeSettings() {
  settingsModal.style.display = 'none';
}

// System back gesture in the Android app: close the settings sheet first, otherwise Back on the TV.
// One gesture must never go back twice on the TV (seen as a duplicate call on the emulator).
let lastAppBack = 0;
(window as Window & { __loungoBack?: () => boolean }).__loungoBack = () => {
  const now = performance.now();
  if (now - lastAppBack < 350) return true;
  lastAppBack = now;
  if (settingsModal.style.display === 'flex') {
    closeSettings();
    return true;
  }
  conn.sendJson({ t: 'back' });
  return true;
};

gearBtn.addEventListener('click', openSettings);
closeBtn.addEventListener('click', closeSettings);
settingsModal.addEventListener('click', (e) => {
  if (e.target === settingsModal) {
    closeSettings();
  }
});

// 3b. Popup Bottom Sheet
const popupSheet = document.createElement('div');
popupSheet.id = 'popup-sheet';
popupSheet.style.position = 'fixed';
popupSheet.style.bottom = '0';
popupSheet.style.left = '0';
popupSheet.style.width = '100%';
popupSheet.style.background = '#1c1c1e';
popupSheet.style.borderTop = '1px solid #38383e';
popupSheet.style.borderTopLeftRadius = '16px';
popupSheet.style.borderTopRightRadius = '16px';
popupSheet.style.padding = '20px 20px max(20px, env(safe-area-inset-bottom, 20px)) 20px';
popupSheet.style.boxSizing = 'border-box';
popupSheet.style.display = 'none';
popupSheet.style.flexDirection = 'column';
popupSheet.style.gap = '12px';
popupSheet.style.zIndex = '90';
popupSheet.style.boxShadow = '0 -4px 20px rgba(0,0,0,0.5)';

const popupTitle = document.createElement('div');
popupTitle.style.fontSize = '14px';
popupTitle.style.color = '#8e8e93';
popupTitle.textContent = 'Pop-up Açılmak İstiyor:';

const popupHost = document.createElement('div');
popupHost.style.fontSize = '17px';
popupHost.style.fontWeight = 'bold';
popupHost.style.color = '#ffffff';
popupHost.style.wordBreak = 'break-all';

const popupBtnRow = document.createElement('div');
popupBtnRow.style.display = 'flex';
popupBtnRow.style.gap = '8px';
popupBtnRow.style.marginTop = '4px';

function createSheetBtn(label: string, bg: string, color: string) {
  const b = document.createElement('button');
  b.textContent = label;
  b.style.flex = '1';
  b.style.padding = '12px 8px';
  b.style.background = bg;
  b.style.color = color;
  b.style.border = 'none';
  b.style.borderRadius = '10px';
  b.style.fontSize = '15px';
  b.style.fontWeight = '600';
  b.style.cursor = 'pointer';
  return b;
}

const btnOpen = createSheetBtn('Aç', '#0a84ff', '#ffffff');
const btnDeny = createSheetBtn('Engelle', '#ff3b30', '#ffffff');

popupBtnRow.appendChild(btnOpen);
popupBtnRow.appendChild(btnDeny);

popupSheet.appendChild(popupTitle);
popupSheet.appendChild(popupHost);
popupSheet.appendChild(popupBtnRow);
app.appendChild(popupSheet);

let currentPopupId: string | null = null;

function showPopupSheet(id: string, url: string) {
  currentPopupId = id;
  let host = url;
  try {
    host = new URL(url).hostname || url;
  } catch {}
  popupHost.textContent = host;
  popupSheet.style.display = 'flex';
}

function hidePopupSheet() {
  currentPopupId = null;
  popupSheet.style.display = 'none';
}

btnOpen.addEventListener('click', () => {
  if (currentPopupId) {
    conn.sendJson({ t: 'popup', id: currentPopupId, action: 'open' });
    hidePopupSheet();
  }
});

btnDeny.addEventListener('click', () => {
  if (currentPopupId) {
    conn.sendJson({ t: 'popup', id: currentPopupId, action: 'deny' });
    hidePopupSheet();
  }
});

// 5. Connection & State Tracking
let isUrlFocused = false;
let currentUrl = '';
let currentTitle = '';
let isLoading = false;
let isPopupOpen = false;
let isFullscreen = false;
let canGoBack = false;

function updateConnectionStatus(state: ConnectionState) {
  if (state !== 'invalid_token') {
    invalidTokenModal.style.display = 'none';
  }
  switch (state) {
    case 'connecting':
      statusDot.style.background = '#ff3b30';
      statusDot.title = 'Bağlantı yok — yeniden deneniyor';
      statusBanner.style.display = 'block';
      statusBanner.textContent = 'Bağlantı yok — yeniden deneniyor';
      break;
    case 'connected':
      statusDot.style.background = '#34c759';
      statusDot.title = 'Bağlı';
      statusBanner.style.display = 'none';
      // Stale panel from a previous session: the TV re-sends player state if one is open.
      playerPanel.update({ on: false });
      volControls.style.display = 'flex';
      break;
    case 'no_token':
      statusDot.style.background = '#ff3b30';
      statusDot.title = 'Yeniden eşleştirin';
      statusBanner.style.display = 'block';
      statusBanner.textContent = 'Yeniden eşleştirin';
      break;
    case 'replaced':
      statusDot.style.background = '#ff3b30';
      statusDot.title = 'Başka bir cihaz bağlandı';
      statusBanner.style.display = 'block';
      statusBanner.innerHTML = '';
      const textSpan = document.createElement('span');
      textSpan.textContent = 'Başka bir cihaz bağlandı ';
      statusBanner.appendChild(textSpan);
      const reconnectBtn = document.createElement('button');
      reconnectBtn.textContent = 'Buraya bağlan';
      reconnectBtn.style.marginLeft = '8px';
      reconnectBtn.style.background = '#ffffff';
      reconnectBtn.style.color = '#000000';
      reconnectBtn.style.border = 'none';
      reconnectBtn.style.borderRadius = '4px';
      reconnectBtn.style.padding = '3px 8px';
      reconnectBtn.style.fontSize = '12px';
      reconnectBtn.style.fontWeight = 'bold';
      reconnectBtn.style.cursor = 'pointer';
      reconnectBtn.onclick = () => {
        conn.reconnect();
      };
      statusBanner.appendChild(reconnectBtn);
      break;
    case 'invalid_token':
      statusDot.style.background = '#ff3b30';
      statusDot.title = 'Eşleşme geçersiz';
      statusBanner.style.display = 'none';
      invalidTokenModal.style.display = 'flex';
      break;
  }
}

const conn = new PhantomConnection({
  onStateChange: (state) => {
    // The Android app pairs natively (no QR there): hand control back to it.
    if (inApp && (state === 'no_token' || state === 'invalid_token')) {
      notifyApp('unpaired');
      return;
    }
    updateConnectionStatus(state);
    if (state === 'connected') {
      sendViewDiag();
    }
  },
  onMessage: (msg) => {
    if (msg.t === 'hello' && msg.lite) {
      liteSelect.value = msg.lite;
    }
    if (msg.t === 'hello') {
      // While the TV shows its first-run tour, the phone shows its tips even if seen before.
      setTimeout(() => (msg.tour ? onboarding.fromTv() : onboarding.start()), 600);
    }
    if (msg.t === 'tips') {
      setTimeout(() => onboarding.fromTv(), 700);
    }
    if (msg.t === 'hello' && msg.pwa && msg.pwa !== __PWA_BUILD__) {
      // The TV app was updated: load the new phone UI once (guard against a reload loop).
      try {
        if (sessionStorage.getItem('phantom-reloaded-for') !== msg.pwa) {
          sessionStorage.setItem('phantom-reloaded-for', msg.pwa);
          location.reload();
          return;
        }
      } catch (e) {}
    }
    if (msg.t === 'state') {
      const newUrl = msg.url || '';
      if (newUrl !== lastPageUrl) {
        lastPageUrl = newUrl;
        mediaShownForCurrentPage = false;
        currentMediaKind = null;
        mediaBanner.style.display = 'none';
        if (mediaBannerTimer) clearTimeout(mediaBannerTimer);
      }
      currentUrl = newUrl;
      updateFavBtn();
      isLoading = !!msg.loading;
      canGoBack = !!msg.canBack;
      isPopupOpen = !!msg.popupOpen;
      updateButtonState(backBtn, canGoBack || isPopupOpen || isFullscreen);
      updateButtonState(fwdBtn, !!msg.canFwd);

      reloadStopBtn.innerHTML = isLoading ? SVG_STOP : SVG_RELOAD;
      reloadStopBtn.title = isLoading ? 'Durdur' : 'Yenile';

      if (!isUrlFocused) {
        urlInput.value = currentUrl;
      }
      if (currentUrl) {
        currentTitle = msg.title || currentTitle;
      }

      const progress = typeof msg.progress === 'number' ? msg.progress : (isLoading ? 10 : 100);
      if (isLoading && progress < 100) {
        progressBar.style.opacity = '1';
        progressBar.style.width = `${Math.max(5, progress)}%`;
      } else {
        progressBar.style.width = '100%';
        setTimeout(() => {
          if (!isLoading) {
            progressBar.style.opacity = '0';
            progressBar.style.width = '0%';
          }
        }, 200);
      }

      if (typeof msg.ua === 'string') {
        desktopToggle.checked = (msg.ua === 'desktop');
      }
      if (typeof msg.zoom === 'number') {
        if (document.activeElement !== zoomInput) {
          zoomInput.value = msg.zoom.toString();
          zoomValText.textContent = `${msg.zoom}%`;
        }
      }
      if (typeof msg.adblock === 'boolean') {
        adblockToggle.checked = msg.adblock;
      }
      if (typeof msg.blocked === 'number') {
        adblockBlockedInfo.textContent = `Bu sayfada ${msg.blocked} istek engellendi`;
      }
    } else if (msg.t === 'editable') {
      if (msg.src === 'hover') {
        keyboardManager.handleHover(!!msg.on);
      } else {
        keyboardManager.handleFocusMessage(msg);
      }
    } else if (msg.t === 'popup') {
      showPopupSheet(msg.id, msg.url);
    } else if (msg.t === 'popup_gone') {
      isPopupOpen = false;
      updateButtonState(backBtn, canGoBack || isPopupOpen || isFullscreen);
      hidePopupSheet();
    } else if (msg.t === 'fullscreen') {
      isFullscreen = !!msg.on;
      updateButtonState(backBtn, canGoBack || isPopupOpen || isFullscreen);
    } else if (msg.t === 'media') {
      const replace = shouldReplaceMedia(currentMediaKind, msg.kind);
      if (replace) {
        currentMediaUrl = msg.url;
        currentMediaKind = msg.kind;
      }
      if (!mediaShownForCurrentPage || replace) {
        mediaShownForCurrentPage = true;
        mediaBanner.style.display = 'block';
        if (mediaBannerTimer) clearTimeout(mediaBannerTimer);
        mediaBannerTimer = setTimeout(() => {
          mediaBanner.style.display = 'none';
        }, 15000);
      }
    } else if (msg.t === 'player') {
      playerPanel.update(msg);
      volControls.style.display = msg.on ? 'none' : 'flex';
    } else if (msg.t === 'logs') {
      reportBtn.disabled = false;
      reportBtn.textContent = trStr.reportIssue;
      handleReceivedLogs(msg.lines || []);
    } else if (msg.t === 'cleared') {
      clearDataBtn.textContent = '✓ ' + trStr.cleared;
      setTimeout(() => {
        clearDataBtn.disabled = false;
        clearDataBtn.textContent = trStr.clearData;
      }, 2500);
    } else if (msg.t === 'pong') {
      diagPendingPongCallback?.();
    }
  }
});

const playerPanel = new PlayerPanel(trackpadArea, conn, (visible) => {
  volControls.style.display = visible ? 'none' : 'flex';
});

// Global error handlers for diagnostics (T29g)
window.addEventListener('error', (event) => {
  try {
    conn?.sendJson({
      t: 'diag',
      js_err: {
        msg: event.message,
        file: event.filename,
        line: event.lineno,
        col: event.colno,
      },
    });
  } catch {}
});

window.addEventListener('unhandledrejection', (event) => {
  try {
    conn?.sendJson({
      t: 'diag',
      js_unhandled: {
        reason: event.reason?.message || String(event.reason),
      },
    });
  } catch {}
});

// Reconnection on visibility change
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') {
    if (conn.getState() === 'replaced' || conn.getState() === 'no_token') {
      return;
    }
    if (conn.getState() === 'invalid_token') {
      conn.reconnect();
      return;
    }
    const now = performance.now();
    const idleTime = now - conn.getLastMessageTime();
    if (!conn.isConnected() || idleTime > 3000) {
      conn.reconnect();
    }
  }
});

// Navigation actions
backBtn.addEventListener('click', () => {
  if (!backBtn.disabled) {
    conn.sendJson({ t: 'back' });
  }
});

fwdBtn.addEventListener('click', () => {
  if (!fwdBtn.disabled) {
    conn.sendJson({ t: 'fwd' });
  }
});

reloadStopBtn.addEventListener('click', () => {
  if (isLoading) {
    conn.sendJson({ t: 'stop' });
  } else {
    conn.sendJson({ t: 'reload' });
  }
});

function updateFavBtn(overrideUrl?: string) {
  const urlToCheck = overrideUrl !== undefined ? overrideUrl : currentUrl;
  if (urlToCheck && favoriteStore.isFavorite(urlToCheck)) {
    favBtn.innerHTML = SVG_STAR_FILLED;
    favBtn.title = 'Favorilerden çıkar';
    favBtn.style.opacity = '1.0';
  } else {
    favBtn.innerHTML = SVG_STAR_OUTLINE;
    favBtn.title = 'Favorilere ekle';
    favBtn.style.opacity = '0.6';
  }
}

favoriteStore.subscribe(() => {
  updateFavBtn();
  if (favDropdown.style.display !== 'none') {
    renderFavoritesList();
  }
});

favBtn.addEventListener('click', () => {
  const url = currentUrl || urlInput.value.trim();
  if (!url) return;
  const title = (currentTitle && currentTitle !== url) ? currentTitle : url;
  favoriteStore.toggleFavorite(url, title);
  updateFavBtn();
});

let favBlurTimeout: any = null;

function showFavoritesDropdown() {
  if (favBlurTimeout) {
    clearTimeout(favBlurTimeout);
    favBlurTimeout = null;
  }
  renderFavoritesList();
  favDropdown.style.display = 'block';
}

function hideFavoritesDropdown() {
  favDropdown.style.display = 'none';
}

function renderFavoritesList() {
  favDropdown.innerHTML = '';
  const favorites = favoriteStore.getFavorites();

  if (favorites.length === 0) {
    const emptyRow = document.createElement('div');
    emptyRow.style.padding = '12px 16px';
    emptyRow.style.fontSize = '13px';
    emptyRow.style.color = '#8e8e93';
    emptyRow.style.textAlign = 'center';
    emptyRow.textContent = 'Favori site bulunmuyor';
    favDropdown.appendChild(emptyRow);
    return;
  }

  const header = document.createElement('div');
  header.style.padding = '6px 14px 4px 14px';
  header.style.fontSize = '11px';
  header.style.fontWeight = '600';
  header.style.textTransform = 'uppercase';
  header.style.letterSpacing = '0.5px';
  header.style.color = '#8e8e93';
  header.textContent = 'Favoriler';
  favDropdown.appendChild(header);

  for (const item of favorites) {
    const row = document.createElement('div');
    row.className = 'fav-item';
    row.style.display = 'flex';
    row.style.alignItems = 'center';
    row.style.gap = '12px';
    row.style.padding = '10px 14px';
    row.style.cursor = 'pointer';
    row.style.borderBottom = '1px solid #28282b';

    row.addEventListener('pointerenter', () => {
      row.style.backgroundColor = '#2c2c2e';
    });
    row.addEventListener('pointerleave', () => {
      row.style.backgroundColor = 'transparent';
    });

    let host = '';
    try {
      const u = item.url.includes('://') ? item.url : `https://${item.url}`;
      host = new URL(u).hostname;
    } catch {
      host = item.url;
    }

    const iconContainer = document.createElement('div');
    iconContainer.style.width = '28px';
    iconContainer.style.height = '28px';
    iconContainer.style.borderRadius = '50%';
    iconContainer.style.background = '#2c2c2e';
    iconContainer.style.display = 'flex';
    iconContainer.style.alignItems = 'center';
    iconContainer.style.justifyContent = 'center';
    iconContainer.style.flexShrink = '0';
    iconContainer.style.overflow = 'hidden';

    const initial = (item.title || host || '?').trim().charAt(0).toUpperCase();

    const img = document.createElement('img');
    img.src = `https://${host}/favicon.ico`;
    img.alt = initial;
    img.style.width = '16px';
    img.style.height = '16px';
    img.style.objectFit = 'contain';

    img.onerror = () => {
      iconContainer.innerHTML = '';
      const badge = document.createElement('span');
      badge.textContent = initial;
      badge.style.fontSize = '12px';
      badge.style.fontWeight = 'bold';
      badge.style.color = '#ffffff';
      iconContainer.appendChild(badge);
    };

    iconContainer.appendChild(img);
    row.appendChild(iconContainer);

    const textCol = document.createElement('div');
    textCol.style.display = 'flex';
    textCol.style.flexDirection = 'column';
    textCol.style.minWidth = '0';
    textCol.style.flex = '1';

    const titleSpan = document.createElement('span');
    titleSpan.textContent = item.title || host || item.url;
    titleSpan.style.color = '#ffffff';
    titleSpan.style.fontSize = '14px';
    titleSpan.style.fontWeight = '500';
    titleSpan.style.overflow = 'hidden';
    titleSpan.style.textOverflow = 'ellipsis';
    titleSpan.style.whiteSpace = 'nowrap';
    textCol.appendChild(titleSpan);

    if (item.url) {
      const urlSpan = document.createElement('span');
      urlSpan.textContent = host || item.url;
      urlSpan.style.color = '#8e8e93';
      urlSpan.style.fontSize = '11px';
      urlSpan.style.overflow = 'hidden';
      urlSpan.style.textOverflow = 'ellipsis';
      urlSpan.style.whiteSpace = 'nowrap';
      textCol.appendChild(urlSpan);
    }

    row.appendChild(textCol);

    row.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (favBlurTimeout) {
        clearTimeout(favBlurTimeout);
        favBlurTimeout = null;
      }
      conn.sendJson({ t: 'nav', url: item.url });
      hideFavoritesDropdown();
      urlInput.value = item.url;
      currentUrl = item.url;
      currentTitle = item.title || item.url;
      updateFavBtn(item.url);
      urlInput.blur();
    });

    favDropdown.appendChild(row);
  }
}

urlInput.addEventListener('focus', () => {
  isUrlFocused = true;
  // Favorites open with the address bar: the field holds the current URL (selected, so typing
  // replaces it), which used to hide the list unless the user cleared it first.
  showFavoritesDropdown();
  urlInput.select();
});

urlInput.addEventListener('input', () => {
  if (urlInput.value.trim() === '') {
    showFavoritesDropdown();
  } else {
    hideFavoritesDropdown();
  }
});

urlInput.addEventListener('blur', () => {
  isUrlFocused = false;
  favBlurTimeout = setTimeout(() => {
    hideFavoritesDropdown();
    urlInput.value = currentUrl;
  }, 200);
});

urlInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    e.preventDefault();
    hideFavoritesDropdown();
    const val = urlInput.value.trim();
    if (val) {
      currentTitle = val;
      conn.sendJson({ t: 'nav', url: val });
    }
    urlInput.blur();
  }
});

document.addEventListener('pointerdown', (e) => {
  const target = e.target as Node;
  if (favDropdown.style.display !== 'none' && !favDropdown.contains(target) && target !== urlInput) {
    hideFavoritesDropdown();
  }
});

updateFavBtn();

// 5. Initialize Keyboard & Trackpad
const keyboardManager = new KeyboardManager(app, conn);
const trackpad = new Trackpad(
  trackpadArea,
  conn,
  sensitivity,
  () => {
    keyboardManager.handleTap();
  },
  // In the app the system back gesture does this; two handlers on one swipe would go back twice.
  inApp ? undefined : () => {
    conn.sendJson({ t: 'back' });
  }
);

sliderInput.addEventListener('input', () => {
  const newSens = parseFloat(sliderInput.value);
  sliderValText.textContent = `${newSens.toFixed(1)}×`;
  trackpad.setSensitivity(newSens);
  localStorage.setItem('phantom_sensitivity', newSens.toString());
});

// Viewport Diagnostic (T28d)
function sendViewDiag() {
  const vv = window.visualViewport;
  const isStandalone = !!(
    window.matchMedia('(display-mode: standalone)').matches ||
    (navigator as unknown as { standalone?: boolean }).standalone
  );
  conn.sendJson({
    t: 'diag',
    view: {
      dpr: window.devicePixelRatio || 1,
      innerW: window.innerWidth,
      innerH: window.innerHeight,
      screenW: window.screen.width,
      screenH: window.screen.height,
      vvScale: vv?.scale ?? 1,
      vvW: vv?.width ?? window.innerWidth,
      vvH: vv?.height ?? window.innerHeight,
      standalone: isStandalone,
    },
  });
}

window.addEventListener('resize', sendViewDiag);
window.visualViewport?.addEventListener('resize', sendViewDiag);


async function handleReceivedLogs(lines: string[]) {
  const text = lines.join('\n');
  if (navigator.share) {
    try {
      await navigator.share({
        title: 'Loungo Sorun Kaydı',
        text: text,
      });
      return;
    } catch {
      // User cancelled share or share failed
    }
  }
  showLogsModal(text);
}

function showLogsModal(text: string) {
  const overlay = document.createElement('div');
  overlay.id = 'logs-modal-overlay';
  overlay.style.position = 'fixed';
  overlay.style.inset = '0';
  overlay.style.background = 'rgba(0, 0, 0, 0.8)';
  overlay.style.display = 'flex';
  overlay.style.alignItems = 'center';
  overlay.style.justifyContent = 'center';
  overlay.style.zIndex = '9999';
  overlay.style.padding = '16px';

  const card = document.createElement('div');
  card.style.background = '#1c1c1e';
  card.style.borderRadius = '12px';
  card.style.padding = '16px';
  card.style.width = '100%';
  card.style.maxWidth = '500px';
  card.style.maxHeight = '80vh';
  card.style.display = 'flex';
  card.style.flexDirection = 'column';
  card.style.gap = '12px';

  const header = document.createElement('div');
  header.style.display = 'flex';
  header.style.justifyContent = 'space-between';
  header.style.alignItems = 'center';

  const title = document.createElement('h3');
  title.textContent = trStr.issueLogs;
  title.style.margin = '0';
  title.style.color = '#ffffff';

  const closeBtn = document.createElement('button');
  closeBtn.textContent = trStr.close;
  closeBtn.style.background = 'transparent';
  closeBtn.style.color = '#8e8e93';
  closeBtn.style.border = 'none';
  closeBtn.style.fontSize = '14px';
  closeBtn.style.cursor = 'pointer';
  closeBtn.addEventListener('click', () => overlay.remove());

  header.appendChild(title);
  header.appendChild(closeBtn);
  card.appendChild(header);

  const textarea = document.createElement('textarea');
  textarea.readOnly = true;
  textarea.value = text || trStr.noLogs;
  textarea.style.width = '100%';
  textarea.style.height = '300px';
  textarea.style.background = '#121212';
  textarea.style.color = '#a0a0a5';
  textarea.style.fontFamily = 'monospace';
  textarea.style.fontSize = '11px';
  textarea.style.padding = '8px';
  textarea.style.borderRadius = '6px';
  textarea.style.border = '1px solid #333336';
  textarea.style.boxSizing = 'border-box';
  card.appendChild(textarea);

  const copyBtn = document.createElement('button');
  copyBtn.textContent = trStr.copyClipboard;
  copyBtn.style.padding = '8px 12px';
  copyBtn.style.background = '#0a84ff';
  copyBtn.style.color = '#ffffff';
  copyBtn.style.border = 'none';
  copyBtn.style.borderRadius = '6px';
  copyBtn.style.cursor = 'pointer';
  copyBtn.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(text);
      copyBtn.textContent = trStr.copied;
      setTimeout(() => { copyBtn.textContent = trStr.copyClipboard; }, 2000);
    } catch {
      copyBtn.textContent = trStr.copyFailed;
    }
  });
  card.appendChild(copyBtn);

  overlay.appendChild(card);
  document.body.appendChild(overlay);
}
