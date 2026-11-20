import rawLocations from '../data/locations.json';
import type { CityLocalitiesMap, ZonePincodesMap } from '../types';

interface LocationsJsonShape {
  cities?: Record<string, string[]>;
  zone_pincodes?: Record<string, string[]>;
}

const data = rawLocations as LocationsJsonShape;

const CUSTOM_LOCALITIES_KEY = 'clicker_area_prefs_custom_localities';

let cachedVerified: CityLocalitiesMap | null = null;
let cachedPins: ZonePincodesMap | null = null;
let cachedMerged: CityLocalitiesMap | null = null;

const naturalCollator = new Intl.Collator(undefined, {
  numeric: true,
  sensitivity: 'base',
});

export function naturalCompareLocalities(a: string, b: string): number {
  return naturalCollator.compare(a, b);
}

export function loadVerifiedCities(): CityLocalitiesMap {
  if (cachedVerified) return cachedVerified;
  const result: CityLocalitiesMap = {};
  const cities = data.cities ?? {};
  for (const city of Object.keys(cities)) {
    const list = Array.isArray(cities[city]) ? [...cities[city]] : [];
    result[city] = list.sort(naturalCompareLocalities);
  }
  cachedVerified = result;
  return result;
}

export function loadZonePins(): ZonePincodesMap {
  if (cachedPins) return cachedPins;
  const result: ZonePincodesMap = {};
  const pins = data.zone_pincodes ?? {};
  for (const zone of Object.keys(pins)) {
    result[zone] = Array.isArray(pins[zone]) ? [...pins[zone]] : [];
  }
  cachedPins = result;
  return result;
}

export function getCustomLocalities(): CityLocalitiesMap {
  try {
    const raw = localStorage.getItem(CUSTOM_LOCALITIES_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const out: CityLocalitiesMap = {};
    for (const city of Object.keys(parsed)) {
      const val = parsed[city];
      if (Array.isArray(val)) {
        out[city] = val.filter((v): v is string => typeof v === 'string');
      }
    }
    return out;
  } catch {
    return {};
  }
}

export function saveCustomLocalities(map: CityLocalitiesMap): void {
  cachedMerged = null;
  localStorage.setItem(CUSTOM_LOCALITIES_KEY, JSON.stringify(map));
}

export function loadAllCitiesWithCustom(): CityLocalitiesMap {
  if (cachedMerged) return cachedMerged;
  const verified = loadVerifiedCities();
  const custom = getCustomLocalities();
  const merged: CityLocalitiesMap = {};

  const allCityNames = Array.from(new Set([...Object.keys(verified), ...Object.keys(custom)]));
  for (const city of allCityNames) {
    const base = verified[city] ?? [];
    const extra = custom[city] ?? [];
    const seen = new Set<string>();
    const combined: string[] = [];
    for (const item of [...base, ...extra]) {
      const key = item.trim().toLowerCase();
      if (key && !seen.has(key)) {
        seen.add(key);
        combined.push(item);
      }
    }
    combined.sort(naturalCompareLocalities);
    merged[city] = combined;
  }
  cachedMerged = merged;
  return merged;
}
