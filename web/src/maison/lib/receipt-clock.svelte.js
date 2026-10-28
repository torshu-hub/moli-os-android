import { onMount } from 'svelte';

// Display only: no reads, requests or changes to observation timestamps.
// Shared by the two Android card groups, stopped while hidden or unmounted.
const clock = $state({ now: null });
let users = 0;
let timer = null;
const tick = () => { clock.now = Date.now(); };
function visibility() {
  if (timer !== null) clearInterval(timer);
  timer = null;
  if (!document.hidden && users > 0) {
    tick();
    timer = setInterval(tick, 1000);
  }
}
export function useReceiptClock() {
  onMount(() => {
    if (++users === 1) {
      document.addEventListener('visibilitychange', visibility);
      visibility();
    }
    return () => {
      if (--users === 0) {
        document.removeEventListener('visibilitychange', visibility);
        if (timer !== null) clearInterval(timer);
        timer = null;
        clock.now = null;
      }
    };
  });
  return clock;
}
