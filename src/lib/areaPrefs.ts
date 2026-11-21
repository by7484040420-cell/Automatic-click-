import {
  getCustomLocalities,
  loadVerifiedCities,
  loadZonePins,
  saveCustomLocalities,
} from './locationRepository';
import type { MatchAnalysis, SelectionMap } from '../types';

export const ALL_MARKER = '__ALL__';

const KEY_PICKUP_JSON = 'clicker_area_prefs_pickup_json';
const KEY_DROP_JSON = 'clicker_area_prefs_drop_json';
const KEY_REJECT_PICKUP_JSON = 'clicker_area_prefs_reject_pickup_json';
const KEY_REJECT_DROP_JSON = 'clicker_area_prefs_reject_drop_json';
const KEY_PAUSED = 'clicker_area_prefs_paused';
const KEY_PICKUP_KEYWORDS = 'clicker_area_prefs_pickup_keywords';
const KEY_MAX_PICKUP_KM = 'clicker_area_prefs_max_pickup_km';
const KEY_ALLOW_MISSING_KM = 'clicker_area_prefs_allow_missing_km';

const WHITESPACE_RE = /\s+/g;
const ACCEPT_IN_RE = /accept\s*in/i;
const KM_HEADER_RE = /(?:\(\s*)?\d+(?:[.,]\d+)?\s*(?:kms?|kilometers?|meters?|mtrs?|m)(?:\s*away|\s*from\s*you)?(?:\s*\))?/i;
const TOTAL_TRIP_TAG_RE = /\b(?:total|trip|fare|est(?:imated)?|overall)\b/i;
const NON_PICKUP_DIST_TAG_RE = /\b(?:drop(?:[\s-]*off)?|destination|deliver(?:y)?|total|trip|fare|est(?:imated)?|overall)\b/i;
const DIST_TOKEN_RE = /(\d+(?:[.,]\d+)?)\s*(kms?|kilometers?|meters?|mtrs?|m)\b/gi;
const PIN_LINE_RE = /(?<![0-9])\d{6}(?![0-9])|,\s*india\b|\b(?:gurugram|gurgaon|new delhi|delhi|noida|greater noida|faridabad|ghaziabad|haryana|uttar pradesh)\b/i;
const PICKUP_LABEL_RE = /\bpick[\s-]*up\b/i;
const DROP_LABEL_RE = /\bdrop(?:[\s-]*off)?\b/i;
const GREATER_NOIDA_RE = /greater[\s-]*noida|gr\.?\s*noida/g;
const SECTOR_SPLIT_RE = /^(.+?)\s+(sector\s+[a-z0-9]+)$/;
const PIN_RE = /(?<![0-9])(110\d{3})(?![0-9])/g;
const ZONE_PREFIX = 'delhi - ';

const CITY_ALIASES: Record<string, string[]> = {
  gurugram: ['gurugram', 'gurgaon', 'ggn'],
  delhi: ['delhi', 'new delhi', 'ncr'],
  'greater noida': ['greater noida', 'gr. noida', 'gr noida', 'greaternoida'],
  noida: ['noida', 'new okhla', 'noida extension'],
  ghaziabad: ['ghaziabad', 'gzb'],
  faridabad: ['faridabad', 'fbd'],
};

const ACCEPT_WORDS = ['accept', 'confirm', 'take order', 'accept order', 'accept now'];

const wordRegexCache = new Map<string, RegExp>();

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function normalize(s: string): string {
  return s.trim().toLowerCase().replace(WHITESPACE_RE, ' ');
}

function wordRegex(phrase: string): RegExp {
  const existing = wordRegexCache.get(phrase);
  if (existing) return existing;
  const body = phrase
    .trim()
    .split(/[\s-]+/)
    .filter((p) => p.length > 0)
    .map((p) => escapeRegExp(p))
    .join('[\\s\\-]*');
  const re = new RegExp(`(?<![a-z0-9])${body}(?![a-z0-9])`);
  wordRegexCache.set(phrase, re);
  return re;
}

function containsWord(text: string, phrase: string): boolean {
  if (!phrase.trim()) return false;
  return wordRegex(phrase).test(text);
}

function cityAliases(normalizedCity: string): string[] {
  return CITY_ALIASES[normalizedCity] ?? [normalizedCity];
}

function cityMatches(cityNorm: string, text: string): boolean {
  const t = cityNorm === 'noida' ? text.replace(GREATER_NOIDA_RE, ' ') : text;
  return cityAliases(cityNorm).some((alias) => containsWord(t, alias));
}

function localityMatches(text: string, locality: string): boolean {
  const loc = normalize(locality);
  if (!loc) return false;
  if (containsWord(text, loc)) return true;
  const m = SECTOR_SPLIT_RE.exec(loc);
  if (m) {
    return containsWord(text, m[1]) && containsWord(text, m[2]);
  }
  return false;
}

function isZone(cityNorm: string): boolean {
  return cityNorm.startsWith(ZONE_PREFIX);
}

function zoneAnyMatch(zoneNorm: string, text: string): string | null {
  const verified = loadVerifiedCities();
  for (const [cityName, localities] of Object.entries(verified)) {
    if (normalize(cityName) === zoneNorm) {
      const hit = localities.find((loc) => localityMatches(text, loc));
      if (hit) return `${cityName}: ${hit}`;
    }
  }
  const pins = loadZonePins();
  for (const [zoneName, pinList] of Object.entries(pins)) {
    if (normalize(zoneName) === zoneNorm) {
      const pinSet = new Set(pinList);
      const matches = text.match(PIN_RE) ?? [];
      const pinHit = matches.find((pin) => pinSet.has(pin));
      if (pinHit) return `${zoneName} (PIN ${pinHit})`;
    }
  }
  return null;
}

function findMatchingSelection(selections: SelectionMap, normalizedText: string): string | null {
  for (const [city, localities] of Object.entries(selections)) {
    const cityNorm = normalize(city);
    if (!cityNorm) continue;
    const zone = isZone(cityNorm);
    const cityMatched = cityMatches(zone ? 'delhi' : cityNorm, normalizedText);

    if (localities.includes(ALL_MARKER)) {
      if (!cityMatched) continue;
      if (!zone) return `All ${city}`;
      const zHit = zoneAnyMatch(cityNorm, normalizedText);
      if (zHit) return zHit;
      continue;
    }

    if (!cityMatched) continue;
    for (const locality of localities) {
      if (localityMatches(normalizedText, locality)) {
        return `${city}: ${locality}`;
      }
    }
  }
  return null;
}

export function splitOrder(rawText: string): [string, string] {
  let t = rawText;
  const accMatch = ACCEPT_IN_RE.exec(t);
  if (accMatch && accMatch.index >= 0) {
    t = t.slice(0, accMatch.index);
  }

  const lines = t.split(/\s*\|\s*|\n+/).map((l) => l.trim()).filter(Boolean);

  // 1. Distance header splitting (e.g. Sector 34 (2.9 km) ... Sector 52 (6.9 km))
  const allDistIdx = lines
    .map((line, i) => (KM_HEADER_RE.test(line) ? i : -1))
    .filter((i) => i >= 0);

  const legDistIdx = allDistIdx.filter((i) => !TOTAL_TRIP_TAG_RE.test(lines[i]));

  if (legDistIdx.length >= 2) {
    const startOf = (i: number, prevIdx: number) => {
      if (i <= 0) return i;
      const trimmed = lines[i].trim();
      if (trimmed.startsWith('(') && i - 1 > prevIdx) {
        return i - 1;
      }
      return i;
    };
    const s1 = startOf(legDistIdx[0], -1);
    const s2 = startOf(legDistIdx[1], legDistIdx[0]);
    if (s2 > s1) {
      const pick = lines.slice(s1, s2).join(' | ');
      const drop = lines.slice(s2).join(' | ');
      return [normalize(pick), normalize(drop)];
    }
  }

  // 2. Explicit PICKUP / DROP markers (when card has no KM shown)
  const pLabelIdx = lines.findIndex((l) => PICKUP_LABEL_RE.test(l) && !DROP_LABEL_RE.test(l));
  const dLabelIdx = lines.findIndex(
    (l, i) => i > Math.max(0, pLabelIdx) && DROP_LABEL_RE.test(l) && !PICKUP_LABEL_RE.test(l)
  );
  if (pLabelIdx >= 0 && dLabelIdx > pLabelIdx) {
    const pick = lines.slice(pLabelIdx, dLabelIdx).join(' | ');
    const drop = lines.slice(dLabelIdx).join(' | ');
    return [normalize(pick), normalize(drop)];
  }
  if (pLabelIdx < 0 && dLabelIdx > 0) {
    const firstLineIsFare = /^(?:₹|rs\.?|inr)\s*\d+/i.test(lines[0]);
    const startPick = firstLineIsFare && dLabelIdx > 1 ? 1 : 0;
    const pick = lines.slice(startPick, dLabelIdx).join(' | ');
    const drop = lines.slice(dLabelIdx).join(' | ');
    return [normalize(pick), normalize(drop)];
  }

  // 3. Fallback: two address blocks ending with 6-digit PIN, ", India", or city+address
  const addrEndIdx = lines
    .map((line, i) => (line.length >= 16 && PIN_LINE_RE.test(line) ? i : -1))
    .filter((i) => i >= 0);
  if (addrEndIdx.length >= 2) {
    const splitAfterFirstAddr = addrEndIdx[0] + 1;
    if (splitAfterFirstAddr < lines.length) {
      const firstLineIsFare = /^(?:₹|rs\.?|inr)\s*\d+/i.test(lines[0]);
      const startPick = firstLineIsFare && splitAfterFirstAddr > 1 ? 1 : 0;
      const pick = lines.slice(startPick, splitAfterFirstAddr).join(' | ');
      const drop = lines.slice(splitAfterFirstAddr).join(' | ');
      return [normalize(pick), normalize(drop)];
    }
  }

  const all = normalize(t);
  return [all, all];
}

export function isTripDetailsScreen(screenText: string): boolean {
  const raw = screenText.toLowerCase();
  const strongMarkers = [
    'cash to collect',
    'trip fare',
    'porter support',
    'call customer',
    'avoid calling the customer',
    'this fare may change',
    'flat no./floor no./shop no.',
    'flat no./shop name',
    "customer's flat no",
    'end trip',
    'start trip',
    'complete trip',
    'arrived at pickup',
    'arrived at drop',
    'reached pickup',
    'reached drop',
    'collect cash',
  ];
  if (strongMarkers.some((m) => raw.includes(m))) return true;
  const softMarkers = ['view details', 'drop at', 'pickup from', 'trip started'];
  return softMarkers.filter((m) => raw.includes(m)).length >= 2;
}

export function screenHasAcceptWord(screenText: string): boolean {
  const raw = screenText.toLowerCase();
  if (
    raw.includes('missed this order') ||
    raw.includes('order missed') ||
    raw.includes('already accepted') ||
    raw.includes('order accepted')
  ) {
    return false;
  }
  const compact = raw.replace(/[^a-z0-9]+/g, '');
  return ACCEPT_WORDS.some(
    (word) => raw.includes(word) || compact.includes(word.replace(/[^a-z0-9]+/g, ''))
  );
}

// ---------- Persistence ----------

function readSelections(key: string): SelectionMap {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const out: SelectionMap = {};
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

function writeSelections(key: string, selections: SelectionMap): void {
  localStorage.setItem(key, JSON.stringify(selections));
}

export function isPaused(): boolean {
  return localStorage.getItem(KEY_PAUSED) === 'true';
}

export function setPaused(paused: boolean): void {
  localStorage.setItem(KEY_PAUSED, paused ? 'true' : 'false');
}

export function getPickupSelections(): SelectionMap {
  return readSelections(KEY_PICKUP_JSON);
}

export function setPickupSelections(selections: SelectionMap): void {
  writeSelections(KEY_PICKUP_JSON, selections);
}

export function getDropSelections(): SelectionMap {
  return readSelections(KEY_DROP_JSON);
}

export function setDropSelections(selections: SelectionMap): void {
  writeSelections(KEY_DROP_JSON, selections);
}

export function getRejectPickupSelections(): SelectionMap {
  return readSelections(KEY_REJECT_PICKUP_JSON);
}

export function setRejectPickupSelections(selections: SelectionMap): void {
  writeSelections(KEY_REJECT_PICKUP_JSON, selections);
}

export function getRejectDropSelections(): SelectionMap {
  return readSelections(KEY_REJECT_DROP_JSON);
}

export function setRejectDropSelections(selections: SelectionMap): void {
  writeSelections(KEY_REJECT_DROP_JSON, selections);
}

export function getPickupKeywords(): string[] {
  const raw = localStorage.getItem(KEY_PICKUP_KEYWORDS) ?? '';
  return raw
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);
}

export function setPickupKeywords(words: string[]): void {
  localStorage.setItem(KEY_PICKUP_KEYWORDS, words.join('\n'));
}

export function getMaxPickupKm(): number {
  const raw = localStorage.getItem(KEY_MAX_PICKUP_KM);
  if (!raw) return 0;
  const num = Number(raw);
  return Number.isFinite(num) && num > 0 ? num : 0;
}

export function setMaxPickupKm(km: number): void {
  const clean = Number.isFinite(km) && km > 0 ? km : 0;
  localStorage.setItem(KEY_MAX_PICKUP_KM, String(clean));
}

export function getAllowPickupWhenKmMissing(): boolean {
  const raw = localStorage.getItem(KEY_ALLOW_MISSING_KM);
  // Default true so popups without KM still get picked up
  return raw !== 'false';
}

export function setAllowPickupWhenKmMissing(allow: boolean): void {
  localStorage.setItem(KEY_ALLOW_MISSING_KM, allow ? 'true' : 'false');
}

export function extractDistanceKm(text: string): number | null {
  DIST_TOKEN_RE.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = DIST_TOKEN_RE.exec(text)) !== null) {
    const rawNum = match[1]?.replace(',', '.') ?? '';
    const unit = (match[2] ?? 'km').toLowerCase();
    const val = Number(rawNum);
    if (!Number.isFinite(val)) continue;
    if (unit === 'm' && val < 15) continue;
    const km = unit.startsWith('m') ? val / 1000 : val;

    // Never treat Drop / Total / Trip / Fare distances as Pickup distance
    const ctxStart = Math.max(0, match.index - 24);
    const ctxEnd = Math.min(text.length, match.index + match[0].length + 24);
    const around = text.slice(ctxStart, ctxEnd);
    if (!NON_PICKUP_DIST_TAG_RE.test(around)) {
      return km;
    }
  }
  return null;
}

export function addCustomLocality(city: string, locality: string): void {
  addCustomLocalities(city, [locality]);
}

export function addCustomLocalities(city: string, localities: string[]): void {
  const cleanCity = city.trim();
  if (!cleanCity) return;
  const clean = localities.map((l) => l.trim()).filter(Boolean);
  if (clean.length === 0) return;

  const current = getCustomLocalities();
  const existing = [...(current[cleanCity] ?? [])];
  const seen = new Set(existing.map((l) => l.toLowerCase()));
  for (const loc of clean) {
    const k = loc.toLowerCase();
    if (!seen.has(k)) {
      existing.push(loc);
      seen.add(k);
    }
  }
  current[cleanCity] = existing;
  saveCustomLocalities(current);
}

export function removeCustomCity(city: string): void {
  const cleanCity = city.trim();
  const current = getCustomLocalities();
  if (!(cleanCity in current)) return;
  delete current[cleanCity];
  saveCustomLocalities(current);

  const pickup = getPickupSelections();
  if (cleanCity in pickup) {
    delete pickup[cleanCity];
    setPickupSelections(pickup);
  }
  const drop = getDropSelections();
  if (cleanCity in drop) {
    delete drop[cleanCity];
    setDropSelections(drop);
  }
  const rejectPickup = getRejectPickupSelections();
  if (cleanCity in rejectPickup) {
    delete rejectPickup[cleanCity];
    setRejectPickupSelections(rejectPickup);
  }
  const rejectDrop = getRejectDropSelections();
  if (cleanCity in rejectDrop) {
    delete rejectDrop[cleanCity];
    setRejectDropSelections(rejectDrop);
  }
}

export function analyzeScreenText(rawScreenText: string): MatchAnalysis {
  const dropSelections = getDropSelections();
  const pickupSelections = getPickupSelections();
  const rejectPickupSelections = getRejectPickupSelections();
  const rejectDropSelections = getRejectDropSelections();
  const pickupKeywords = getPickupKeywords();
  const maxPickupKm = getMaxPickupKm();
  const allowMissingKm = getAllowPickupWhenKmMissing();
  const [pickupText, dropText] = splitOrder(rawScreenText);
  const pickupDistanceKm = extractDistanceKm(pickupText);
  const tripDetails = isTripDetailsScreen(rawScreenText);
  const hasAcceptWord = screenHasAcceptWord(rawScreenText);

  if (tripDetails) {
    return {
      matched: false,
      rejected: false,
      rejectedBy: null,
      reason: 'Trip Details screen — order pehle hi accepted hai',
      pickupText,
      dropText,
      pickupDistanceKm,
      isTripDetails: true,
      hasAcceptWord,
      pickupMatchedBy: null,
      dropMatchedBy: null,
    };
  }

  // 1. Check Reject (R) rules FIRST for Pickup & Drop
  const rejectPickupHit = findMatchingSelection(rejectPickupSelections, pickupText);
  if (rejectPickupHit) {
    return {
      matched: false,
      rejected: true,
      rejectedBy: `Pickup R: ${rejectPickupHit}`,
      reason: `REJECT (R) — Pickup "${rejectPickupHit}" par R set hai`,
      pickupText,
      dropText,
      pickupDistanceKm,
      isTripDetails: false,
      hasAcceptWord,
      pickupMatchedBy: null,
      dropMatchedBy: null,
    };
  }

  const rejectDropHit = findMatchingSelection(rejectDropSelections, dropText);
  if (rejectDropHit) {
    return {
      matched: false,
      rejected: true,
      rejectedBy: `Drop R: ${rejectDropHit}`,
      reason: `REJECT (R) — Drop "${rejectDropHit}" par R set hai`,
      pickupText,
      dropText,
      pickupDistanceKm,
      isTripDetails: false,
      hasAcceptWord,
      pickupMatchedBy: null,
      dropMatchedBy: null,
    };
  }

  // 2. Check Drop Accept (A)
  if (Object.keys(dropSelections).length === 0) {
    return {
      matched: false,
      rejected: false,
      rejectedBy: null,
      reason: 'Drop: ABHI SET NAHI HAI (koi bhi order accept nahi hoga)',
      pickupText,
      dropText,
      pickupDistanceKm,
      isTripDetails: false,
      hasAcceptWord,
      pickupMatchedBy: null,
      dropMatchedBy: null,
    };
  }

  const dropHit = findMatchingSelection(dropSelections, dropText);
  if (!dropHit) {
    return {
      matched: false,
      rejected: false,
      rejectedBy: null,
      reason: 'Drop area (A) screen text me match nahi hua',
      pickupText,
      dropText,
      pickupDistanceKm,
      isTripDetails: false,
      hasAcceptWord,
      pickupMatchedBy: null,
      dropMatchedBy: null,
    };
  }

  // 3. Check Pickup Accept (A), Keywords, or Max Pickup KM
  const hasPickupCities = Object.keys(pickupSelections).length > 0;
  const hasKeywords = pickupKeywords.length > 0;
  const hasDistanceRule = maxPickupKm > 0;

  if (!hasPickupCities && !hasKeywords && !hasDistanceRule) {
    return {
      matched: true,
      rejected: false,
      rejectedBy: null,
      reason: `ACCEPT (A) — Drop (${dropHit}) · Pickup: koi restriction nahi`,
      pickupText,
      dropText,
      pickupDistanceKm,
      isTripDetails: false,
      hasAcceptWord,
      pickupMatchedBy: 'No restriction',
      dropMatchedBy: dropHit,
    };
  }

  if (hasPickupCities) {
    const pickupHit = findMatchingSelection(pickupSelections, pickupText);
    if (pickupHit) {
      return {
        matched: true,
        rejected: false,
        rejectedBy: null,
        reason: `ACCEPT (A) — Pickup (${pickupHit}) -> Drop (${dropHit})`,
        pickupText,
        dropText,
        pickupDistanceKm,
        isTripDetails: false,
        hasAcceptWord,
        pickupMatchedBy: pickupHit,
        dropMatchedBy: dropHit,
      };
    }
  }

  const keywordHit = pickupKeywords.find((kw) => containsWord(pickupText, normalize(kw)));
  if (keywordHit) {
    return {
      matched: true,
      rejected: false,
      rejectedBy: null,
      reason: `ACCEPT (A) — Pickup Keyword ("${keywordHit}") -> Drop (${dropHit})`,
      pickupText,
      dropText,
      pickupDistanceKm,
      isTripDetails: false,
      hasAcceptWord,
      pickupMatchedBy: `Keyword: ${keywordHit}`,
      dropMatchedBy: dropHit,
    };
  }

  if (hasDistanceRule) {
    if (pickupDistanceKm !== null && pickupDistanceKm <= maxPickupKm + 0.0001) {
      return {
        matched: true,
        rejected: false,
        rejectedBy: null,
        reason: `ACCEPT (A) — Pickup Distance (${pickupDistanceKm} km <= ${maxPickupKm} km) -> Drop (${dropHit})`,
        pickupText,
        dropText,
        pickupDistanceKm,
        isTripDetails: false,
        hasAcceptWord,
        pickupMatchedBy: `<= ${maxPickupKm} km (${pickupDistanceKm} km)`,
        dropMatchedBy: dropHit,
      };
    }
    if (pickupDistanceKm === null && allowMissingKm && !hasPickupCities && !hasKeywords) {
      return {
        matched: true,
        rejected: false,
        rejectedBy: null,
        reason: `ACCEPT (A) — Card par Pickup KM nahi likha (<= ${maxPickupKm} km fallback) -> Drop (${dropHit})`,
        pickupText,
        dropText,
        pickupDistanceKm,
        isTripDetails: false,
        hasAcceptWord,
        pickupMatchedBy: 'Pickup KM missing (allowed)',
        dropMatchedBy: dropHit,
      };
    }
  }

  return {
    matched: false,
    rejected: false,
    rejectedBy: null,
    reason: hasDistanceRule
      ? `Drop (${dropHit}) mila, par Pickup location / ${maxPickupKm} km limit match nahi hui`
      : `Drop (${dropHit}) mila, par Pickup (A) / khaas naam match nahi hua`,
    pickupText,
    dropText,
    pickupDistanceKm,
    isTripDetails: false,
    hasAcceptWord,
    pickupMatchedBy: null,
    dropMatchedBy: dropHit,
  };
}
