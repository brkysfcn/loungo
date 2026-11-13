/**
 * Inside the Loungo Android app (phone remote) the TV opens this page with `app=1`.
 * There the app owns install/fullscreen/back gestures and pairing, so the page:
 * - hides "Add to Home screen" and "Fullscreen",
 * - leaves the edge-swipe back to the system back gesture,
 * - tells the app when pairing is gone (it searches/pairs again natively, no QR on Android).
 */
export function isInApp(search: string): boolean {
  return new URLSearchParams(search).get('app') === '1';
}

export const inApp = typeof location !== 'undefined' && isInApp(location.search);

/** The app intercepts `loungo-app://` navigations (no JavaScript bridge). */
export function notifyApp(event: 'unpaired'): void {
  if (inApp) location.href = `loungo-app://${event}`;
}
