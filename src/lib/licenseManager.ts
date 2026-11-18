import type { LicenseState } from '../types';

const LICENSE_URL =
  'https://raw.githubusercontent.com/by7484040420-cell/bipin-licenses/main/licenses.txt';
const MARKER = '#BIPIN-LICENSES';

const K_DEVICE_ID = 'swiftgate_device_id';
const K_EXPIRY = 'swiftgate_license_expiry';
const K_PREVIEW_BYPASS = 'swiftgate_preview_unlocked';

let lastError = '';
let debugInfo = '';

export function getDeviceId(): string {
  let existing = localStorage.getItem(K_DEVICE_ID);
  if (!existing || existing.length < 8) {
    const raw = crypto.randomUUID ? crypto.randomUUID().replace(/-/g, '') : `${Date.now()}ABCDEF123456`;
    existing = raw.toUpperCase().slice(0, 12);
    localStorage.setItem(K_DEVICE_ID, existing);
  }
  return existing;
}

export function setCustomDeviceId(id: string): void {
  const clean = id.trim().toUpperCase().slice(0, 12);
  if (clean) {
    localStorage.setItem(K_DEVICE_ID, clean);
  }
}

export function isPreviewUnlocked(): boolean {
  // Default to unlocked in web preview unless explicitly locked by the user to test the License gate
  const v = localStorage.getItem(K_PREVIEW_BYPASS);
  return v !== 'false';
}

export function setPreviewUnlocked(unlocked: boolean): void {
  localStorage.setItem(K_PREVIEW_BYPASS, unlocked ? 'true' : 'false');
}

function getStoredExpiry(): number {
  const raw = localStorage.getItem(K_EXPIRY);
  if (!raw) return 0;
  const n = Number(raw);
  return Number.isFinite(n) ? n : 0;
}

export function revokeLicense(): void {
  localStorage.removeItem(K_EXPIRY);
  setPreviewUnlocked(false);
}

/**
 * Parses lines of format:
 *   DEVICE_ID  2026-10-10 18:00  NAAM
 *   DEVICE_ID  NEVER
 *   DEVICE_ID  2026-10-10  (defaults to 23:59 IST)
 */
export function parseExpiryFromBody(body: string, myId: string): number {
  for (const raw of body.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const parts = line.split(/\s+/);
    if (parts[0].toUpperCase() !== myId.toUpperCase()) continue;
    if (
      parts.length >= 2 &&
      (parts[1].toUpperCase() === 'NEVER' || parts[1].toUpperCase() === 'LIFETIME')
    ) {
      return Number.MAX_SAFE_INTEGER;
    }
    const datePart = parts[1];
    const timePart = parts.length >= 3 ? parts[2] : '23:59';
    if (!datePart) continue;
    // Interpret as IST (+05:30)
    const iso = `${datePart}T${timePart}:00+05:30`;
    const parsed = Date.parse(iso);
    if (!Number.isNaN(parsed)) return parsed;
  }
  return 0;
}

export function formatRemainingText(expiryMs: number): string {
  if (expiryMs <= 0) return 'Approval abhi nahi mila';
  if (expiryMs === Number.MAX_SAFE_INTEGER) return 'Unlimited';
  const left = expiryMs - Date.now();
  if (left <= 0) return 'Aapka time khatam ho gaya';
  const mins = Math.floor(left / 60000);
  const d = Math.floor(mins / 1440);
  const h = Math.floor((mins % 1440) / 60);
  const m = mins % 60;
  if (d > 0) return `${d} din ${h} ghanta baaki`;
  if (h > 0) return `${h} ghanta ${m} minute baaki`;
  return `${m} minute baaki`;
}

export function getLicenseState(): LicenseState {
  const deviceId = getDeviceId();
  const expiryMs = getStoredExpiry();
  const previewBypass = isPreviewUnlocked();
  const isUnlimited = expiryMs === Number.MAX_SAFE_INTEGER;
  const activeFromServer = expiryMs > 0 && Date.now() < expiryMs;
  const isActive = activeFromServer || previewBypass;

  return {
    deviceId,
    expiryMs,
    isUnlimited,
    isActive,
    remainingText:
      activeFromServer
        ? formatRemainingText(expiryMs)
        : previewBypass
          ? 'Web Preview Mode Active (Bina Approval Lock)'
          : formatRemainingText(expiryMs),
    lastError,
    debugInfo,
    previewBypass,
  };
}

export async function refreshLicenseAsync(): Promise<LicenseState> {
  const id = getDeviceId();
  try {
    const res = await fetch(`${LICENSE_URL}?t=${Date.now()}`, { cache: 'no-store' });
    if (!res.ok) {
      lastError = `Internet/server dikkat (RAW ${res.status})`;
      debugInfo = `RAW ${res.status} | ID ${id}`;
      return getLicenseState();
    }
    const body = await res.text();
    if (!body.includes(MARKER)) {
      lastError = 'License file marker nahi mila';
      debugInfo = `RAW 200 (invalid format) | ID ${id}`;
      return getLicenseState();
    }
    const entries = body
      .split(/\r?\n/)
      .filter((l) => {
        const t = l.trim();
        return t.length > 0 && !t.startsWith('#');
      }).length;
    const expiry = parseExpiryFromBody(body, id);
    if (expiry > 0) {
      localStorage.setItem(K_EXPIRY, String(expiry));
    } else {
      localStorage.removeItem(K_EXPIRY);
    }
    lastError = '';
    debugInfo = `RAW 200 entries=${entries} | ID ${id} ${
      expiry > 0 ? `mili (${formatRemainingText(expiry)})` : 'NAHI mili'
    }`;
  } catch (err) {
    const msg = err instanceof Error ? err.message : 'Network error';
    lastError = `Internet/server dikkat: ${msg}`;
    debugInfo = `RAW err | ${msg}`;
  }
  return getLicenseState();
}
