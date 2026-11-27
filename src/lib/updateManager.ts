import type { AppControlStatus, UpdateInfo } from '../types';
import { getDeviceId } from './licenseManager';

const UPDATE_URL =
  'https://raw.githubusercontent.com/by7484040420-cell/bipin-licenses/main/update.txt';
const MARKER = '#BIPIN-UPDATE';
const CURRENT_VERSION_CODE = 3;
export const CURRENT_VERSION_NAME = '1.3';

const K_SNOOZE_CODE = 'swiftgate_update_snooze_code';
const K_SNOOZE_UNTIL = 'swiftgate_update_snooze_until';
const SNOOZE_MS = 6 * 60 * 60 * 1000;

export function parseUpdateBody(body: string): (UpdateInfo & {
  killSwitch?: boolean;
  killMode?: 'lock' | 'crash';
  killMessage?: string;
  broadcastMessage?: string;
}) | null {
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
  const killRaw = (m['KILL_SWITCH'] ?? '').trim().toLowerCase();
  const killSwitch = killRaw === 'true' || killRaw === 'on' || killRaw === '1' || killRaw === 'crash';
  const killMode =
    (m['KILL_MODE'] ?? (killRaw === 'crash' ? 'crash' : '')).trim().toLowerCase() === 'crash'
      ? 'crash'
      : 'lock';
  const killMessage = m['KILL_MESSAGE'] ?? '';
  const broadcastMessage = m['BROADCAST'] ?? '';

  if (!Number.isFinite(code) || !url.startsWith('https://')) {
    if (!killSwitch && !broadcastMessage) return null;
    return {
      code: Number.isFinite(code) ? code : CURRENT_VERSION_CODE,
      name: m['VERSION_NAME'] || CURRENT_VERSION_NAME,
      minCode: Number.parseInt(m['MIN_VERSION'] ?? '0', 10) || 0,
      url: url.startsWith('https://') ? url : '',
      notes: [],
      forced: false,
      killSwitch,
      killMode,
      killMessage,
      broadcastMessage,
    };
  }

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
    killSwitch,
    killMode,
    killMessage,
    broadcastMessage,
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

export async function fetchAppControlStatus(ignoreSnooze = false): Promise<{
  control: AppControlStatus;
  status: string;
}> {
  const deviceId = getDeviceId();
  let serverControl: AppControlStatus | null = null;
  try {
    const srvRes = await fetch(
      `/api/public/app-control?deviceId=${encodeURIComponent(deviceId)}&t=${Date.now()}`,
      { cache: 'no-store' }
    );
    if (srvRes.ok) {
      const data = (await srvRes.json()) as {
        killed?: boolean;
        globalKillSwitch?: boolean;
        deviceKilled?: boolean;
        killMode?: 'lock' | 'crash';
        killMessage?: string;
        broadcastMessage?: string;
        update?: {
          code?: number;
          name?: string;
          minCode?: number;
          url?: string;
          notes?: string[];
        };
      };
      const u = data.update;
      let updateInfo: UpdateInfo | null = null;
      if (u && typeof u.code === 'number' && u.code > CURRENT_VERSION_CODE && u.url?.startsWith('https://')) {
        const minCode = typeof u.minCode === 'number' ? u.minCode : 0;
        const forced = minCode > CURRENT_VERSION_CODE;
        if (ignoreSnooze || forced || !isUpdateSnoozed(u.code)) {
          updateInfo = {
            code: u.code,
            name: u.name || String(u.code),
            minCode,
            url: u.url,
            notes: Array.isArray(u.notes) ? u.notes : [],
            forced,
          };
        }
      }
      serverControl = {
        killed: Boolean(data.killed),
        globalKillSwitch: Boolean(data.globalKillSwitch),
        deviceKilled: Boolean(data.deviceKilled),
        killMode: data.killMode === 'crash' ? 'crash' : 'lock',
        killMessage: data.killMessage || '',
        broadcastMessage: data.broadcastMessage || '',
        update: updateInfo,
      };
    }
  } catch {
    // Fallback to GitHub below
  }

  let ghParsed: ReturnType<typeof parseUpdateBody> = null;
  try {
    const res = await fetch(`${UPDATE_URL}?t=${Date.now()}`, { cache: 'no-store' });
    if (res.ok) {
      ghParsed = parseUpdateBody(await res.text());
    }
  } catch {
    // Ignore GitHub network errors if server is available
  }

  let finalUpdate: UpdateInfo | null = serverControl?.update ?? null;
  if (ghParsed && ghParsed.code > CURRENT_VERSION_CODE && ghParsed.url.startsWith('https://')) {
    const isNewerThanServer = !finalUpdate || ghParsed.code > finalUpdate.code;
    if (isNewerThanServer && (ignoreSnooze || ghParsed.forced || !isUpdateSnoozed(ghParsed.code))) {
      finalUpdate = {
        code: ghParsed.code,
        name: ghParsed.name,
        minCode: ghParsed.minCode,
        url: ghParsed.url,
        notes: ghParsed.notes,
        forced: ghParsed.forced,
      };
    }
  }

  const killed = Boolean(serverControl?.killed || ghParsed?.killSwitch);
  const killMode = serverControl?.killed
    ? serverControl.killMode
    : ghParsed?.killMode || serverControl?.killMode || 'lock';
  const killMessage =
    serverControl?.killMessage ||
    ghParsed?.killMessage ||
    'App abhi Owner dwara temporarily band kiya gaya hai.';
  const broadcastMessage = serverControl?.broadcastMessage || ghParsed?.broadcastMessage || '';

  const control: AppControlStatus = {
    killed,
    globalKillSwitch: Boolean(serverControl?.globalKillSwitch || ghParsed?.killSwitch),
    deviceKilled: Boolean(serverControl?.deviceKilled),
    killMode,
    killMessage,
    broadcastMessage,
    update: finalUpdate,
  };

  if (finalUpdate) {
    return { control, status: `Naya version ${finalUpdate.name} available hai` };
  }
  return { control, status: `Aap latest version (${CURRENT_VERSION_NAME}) par hain` };
}

export async function checkForUpdate(ignoreSnooze = false): Promise<{
  info: UpdateInfo | null;
  status: string;
}> {
  try {
    const res = await fetchAppControlStatus(ignoreSnooze);
    return { info: res.control.update, status: res.status };
  } catch {
    return { info: null, status: 'Update server se connect nahi ho paya' };
  }
}

