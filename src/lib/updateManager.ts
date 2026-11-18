import type { UpdateInfo } from '../types';

const UPDATE_URL =
  'https://raw.githubusercontent.com/by7484040420-cell/bipin-licenses/main/update.txt';
const MARKER = '#BIPIN-UPDATE';
const CURRENT_VERSION_CODE = 3;
export const CURRENT_VERSION_NAME = '1.3';

const K_SNOOZE_CODE = 'swiftgate_update_snooze_code';
const K_SNOOZE_UNTIL = 'swiftgate_update_snooze_until';
const SNOOZE_MS = 6 * 60 * 60 * 1000;

export function parseUpdateBody(body: string): UpdateInfo | null {
  if (!body.includes(MARKER)) return null;
  const m: Record<string, string> = {};
  for (const raw of body.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const idx = line.indexOf('=');
    if (idx <= 0) continue;
    m[line.slice(0, idx).trim().toUpperCase()] = line.slice(idx + 1).trim();
  }
  const code = Number.parseInt(m['VERSION_CODE'] ?? '', 10);
  const url = m['URL'] ?? '';
  if (!Number.isFinite(code) || !url.startsWith('https://')) return null;

  const minCode = Number.parseInt(m['MIN_VERSION'] ?? '0', 10) || 0;
  const name = m['VERSION_NAME'] || String(code);
  const notes = (m['NOTES'] ?? '')
    .split('|')
    .map((s) => s.trim())
    .filter(Boolean);

  return {
    code,
    name,
    minCode,
    url,
    notes,
    forced: minCode > CURRENT_VERSION_CODE,
  };
}

export function isUpdateSnoozed(code: number): boolean {
  const savedCode = Number.parseInt(localStorage.getItem(K_SNOOZE_CODE) ?? '-1', 10);
  const until = Number.parseInt(localStorage.getItem(K_SNOOZE_UNTIL) ?? '0', 10);
  return savedCode === code && Date.now() < until;
}

export function snoozeUpdate(code: number): void {
  localStorage.setItem(K_SNOOZE_CODE, String(code));
  localStorage.setItem(K_SNOOZE_UNTIL, String(Date.now() + SNOOZE_MS));
}

export async function checkForUpdate(ignoreSnooze = false): Promise<{
  info: UpdateInfo | null;
  status: string;
}> {
  try {
    const res = await fetch(`${UPDATE_URL}?t=${Date.now()}`, { cache: 'no-store' });
    if (!res.ok) {
      return { info: null, status: `Update check failed (${res.status})` };
    }
    const body = await res.text();
    const parsed = parseUpdateBody(body);
    if (!parsed) {
      return { info: null, status: 'Koi naya update nahi mila' };
    }
    if (parsed.code <= CURRENT_VERSION_CODE) {
      return { info: null, status: `Aap latest version (${CURRENT_VERSION_NAME}) par hain` };
    }
    if (!ignoreSnooze && !parsed.forced && isUpdateSnoozed(parsed.code)) {
      return { info: null, status: `Version ${parsed.name} snoozed (6h)` };
    }
    return { info: parsed, status: `Naya version ${parsed.name} available hai` };
  } catch {
    return { info: null, status: 'Update server se connect nahi ho paya' };
  }
}
