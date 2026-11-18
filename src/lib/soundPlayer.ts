/**
 * Port of SoundPlayer.kt — plays a crisp 2-tone "cha-ching" order chime
 * using the Web Audio API whenever an order is accepted or swipe test completes.
 */
let sharedAudioCtx: AudioContext | null = null;

function getAudioContext(): AudioContext | null {
  if (typeof window === 'undefined') return null;
  const Ctx = window.AudioContext || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!Ctx) return null;
  if (!sharedAudioCtx) {
    sharedAudioCtx = new Ctx();
  }
  if (sharedAudioCtx.state === 'suspended') {
    sharedAudioCtx.resume().catch(() => {});
  }
  return sharedAudioCtx;
}

function playPip(ctx: AudioContext, startTime: number, freq: number, durationSec: number): void {
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.type = 'sine';
  osc.frequency.setValueAtTime(freq, startTime);

  gain.gain.setValueAtTime(0.001, startTime);
  gain.gain.exponentialRampToValueAtTime(0.22, startTime + 0.012);
  gain.gain.exponentialRampToValueAtTime(0.001, startTime + durationSec);

  osc.connect(gain);
  gain.connect(ctx.destination);

  osc.start(startTime);
  osc.stop(startTime + durationSec + 0.01);
}

export function playOrderSound(): void {
  try {
    const ctx = getAudioContext();
    if (!ctx) return;
    const now = ctx.currentTime;
    // Two-tone CDMA-pip inspired chime (~110ms apart)
    playPip(ctx, now, 1318.5, 0.09);
    playPip(ctx, now + 0.11, 1760.0, 0.14);
  } catch {
    // Ignore audio errors if blocked by browser autoplay policy
  }
}
