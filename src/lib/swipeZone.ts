import type { SwipeZoneConfig } from '../types';

const SWIPE_ZONE_KEY = 'clicker_swipe_zone';
const AUTO_ZONE_VERSION = 2;

export const DEFAULT_SWIPE_ZONE: SwipeZoneConfig = {
  startX: 16.5,
  endX: 90.0,
  y: 43.1,
};

interface StoredSwipeZone extends SwipeZoneConfig {
  ver?: number;
}

export function computeAutoSwipeZone(width?: number, height?: number): SwipeZoneConfig {
  const w =
    typeof width === 'number' && width > 0
      ? width
      : typeof window !== 'undefined'
        ? Math.max(320, window.innerWidth)
        : 360;
  const h =
    typeof height === 'number' && height > 0
      ? height
      : typeof window !== 'undefined'
        ? Math.max(560, window.innerHeight)
        : 802;

  const isWideTablet = w > 600;
  let startX: number;
  let endX: number;
  if (isWideTablet) {
    const cardW = Math.min(w * 0.92, 440);
    const cardLeft = (w - cardW) / 2;
    startX = ((cardLeft + cardW * 0.155) / w) * 100;
    endX = ((cardLeft + cardW * 0.92) / w) * 100;
  } else {
    startX = Math.max(14.5, Math.min(18.5, (60 / w) * 100));
    endX = Math.max(87.0, Math.min(91.0, ((w - 36) / w) * 100));
  }

  const minSide = Math.min(w, h);
  const maxSide = Math.max(w, h);
  const aspect = maxSide / Math.max(1, minSide);
  const dpBasedY = (346 / h) * 100;
  const ratioBasedY = aspect >= 2.0 ? 43.0 : aspect >= 1.85 ? 44.5 : 48.0;
  const y = Math.max(40.0, Math.min(55.0, (dpBasedY + ratioBasedY) * 0.5));

  return {
    startX: Number(startX.toFixed(1)),
    endX: Number(endX.toFixed(1)),
    y: Number(y.toFixed(1)),
  };
}

export function getSavedSwipeZone(): SwipeZoneConfig | null {
  try {
    const raw = localStorage.getItem(SWIPE_ZONE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<StoredSwipeZone>;
    if (
      parsed.ver === AUTO_ZONE_VERSION &&
      typeof parsed.startX === 'number' &&
      typeof parsed.endX === 'number' &&
      typeof parsed.y === 'number'
    ) {
      return {
        startX: parsed.startX,
        endX: parsed.endX,
        y: parsed.y,
      };
    }
    return null;
  } catch {
    return null;
  }
}

export function isSwipeZoneSet(): boolean {
  return getSavedSwipeZone() !== null;
}

export function saveSwipeZone(zone: SwipeZoneConfig): void {
  const payload: StoredSwipeZone = { ...zone, ver: AUTO_ZONE_VERSION };
  localStorage.setItem(SWIPE_ZONE_KEY, JSON.stringify(payload));
}

export function autoCalibrateSwipeZone(width?: number, height?: number): SwipeZoneConfig {
  const zone = computeAutoSwipeZone(width, height);
  saveSwipeZone(zone);
  return zone;
}

export function ensureAutoSwipeZoneCalibrated(): SwipeZoneConfig {
  return getSavedSwipeZone() ?? autoCalibrateSwipeZone();
}

export function clearSwipeZone(): void {
  localStorage.removeItem(SWIPE_ZONE_KEY);
}
