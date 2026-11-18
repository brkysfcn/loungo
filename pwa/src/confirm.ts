/**
 * Two-tap confirmation inside the app instead of the browser's confirm() box, which shows the
 * TV's address as its title. First tap arms the button (its text asks to tap again); a second
 * tap within `timeoutMs` runs the action; otherwise the button returns to normal.
 */
export function armConfirm(
  btn: HTMLButtonElement,
  armedText: string,
  action: () => void,
  timeoutMs = 4000,
): void {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let label = '';
  const disarm = () => {
    if (timer) clearTimeout(timer);
    timer = null;
    btn.textContent = label;
    btn.removeAttribute('data-armed');
  };
  btn.addEventListener('click', (e) => {
    e.preventDefault();
    e.stopPropagation();
    if (timer) {
      disarm();
      action();
      return;
    }
    label = btn.textContent || '';
    btn.textContent = armedText;
    btn.setAttribute('data-armed', '1');
    timer = setTimeout(disarm, timeoutMs);
  });
}
