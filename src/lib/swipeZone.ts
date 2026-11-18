import type { SwipeZoneConfig } from '../types';

const SWIPE_ZONE_KEY = 'clicker_swipe_zone';

export const DEFAULT_SWIPE_ZONE: SwipeZoneConfig = {
  startX: 22.9,
  endX: 90.0,
  y: 72.0,
};

export function getSavedSwipeZone(): SwipeZoneConfig | null {
  try {
    const raw = localStorage.getItem(SWIPE_ZONE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<SwipeZoneConfig>;
    if (
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
  localStorage.setItem(SWIPE_ZONE_KEY, JSON.stringify(zone));
}

export function clearSwipeZone(): void {
  localStorage.removeItem(SWIPE_ZONE_KEY);
}
