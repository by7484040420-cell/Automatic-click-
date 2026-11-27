import type {
  AdminDashboardData,
  AppControlConfig,
  LicenseState,
  PaymentOrderData,
  PaymentPollStatus,
  PublicPlansInfo,
  SubscriptionPlan,
} from '../types';

const LICENSE_URL =
  'https://raw.githubusercontent.com/by7484040420-cell/bipin-licenses/main/licenses.txt';
const MARKER = '#BIPIN-LICENSES';

const K_DEVICE_ID = 'swiftgate_device_id';
const K_EXPIRY = 'swiftgate_license_expiry';
const K_PREVIEW_BYPASS = 'swiftgate_preview_unlocked';

let lastError = '';
let debugInfo = '';
let remoteKilled = false;
let remoteKillMode: 'lock' | 'crash' = 'lock';
let remoteKillMessage = '';
let remoteBroadcastMessage = '';

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

export function applyLicenseExpiry(expiryMs: number): LicenseState {
  if (expiryMs > 0) {
    localStorage.setItem(K_EXPIRY, String(expiryMs));
  } else {
    localStorage.removeItem(K_EXPIRY);
  }
  return getLicenseState();
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
  if (expiryMs >= Number.MAX_SAFE_INTEGER) return 'Unlimited';
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
  const isUnlimited = expiryMs >= Number.MAX_SAFE_INTEGER;
  const activeFromServer = expiryMs > 0 && (isUnlimited || Date.now() < expiryMs);
  const isActive = activeFromServer || previewBypass;

  return {
    deviceId,
    expiryMs,
    isUnlimited,
    isActive,
    remainingText: activeFromServer
      ? formatRemainingText(expiryMs)
      : previewBypass
        ? 'Web Preview Mode Active (Bina Approval Lock)'
        : formatRemainingText(expiryMs),
    lastError,
    debugInfo,
    previewBypass,
    killed: remoteKilled,
    killMode: remoteKillMode,
    killMessage: remoteKillMessage,
    broadcastMessage: remoteBroadcastMessage,
  };
}

export async function refreshLicenseAsync(): Promise<LicenseState> {
  const id = getDeviceId();
  let serverExpiry = 0;
  let serverChecked = false;

  // 1) Check instant payment/license backend first (0ms delay after UPI settlement)
  try {
    const apiRes = await fetch(`/api/license/status?deviceId=${encodeURIComponent(id)}&t=${Date.now()}`, {
      cache: 'no-store',
    });
    if (apiRes.ok) {
      serverChecked = true;
      const data = (await apiRes.json()) as {
        active?: boolean;
        killed?: boolean;
        killMode?: 'lock' | 'crash';
        killMessage?: string;
        broadcastMessage?: string;
        expiryMs?: number;
        isUnlimited?: boolean;
      };
      remoteKilled = Boolean(data.killed);
      remoteKillMode = data.killMode === 'crash' ? 'crash' : 'lock';
      remoteKillMessage = typeof data.killMessage === 'string' ? data.killMessage : '';
      remoteBroadcastMessage = typeof data.broadcastMessage === 'string' ? data.broadcastMessage : '';
      if (data.active && typeof data.expiryMs === 'number' && data.expiryMs > 0) {
        serverExpiry = data.isUnlimited ? Number.MAX_SAFE_INTEGER : data.expiryMs;
      }
    }
  } catch {
    // Fallback cleanly to GitHub below
  }

  if (serverExpiry > 0) {
    localStorage.setItem(K_EXPIRY, String(serverExpiry));
    lastError = '';
    debugInfo = `Server OK | ID ${id} mili (${formatRemainingText(serverExpiry)})`;
    return getLicenseState();
  }

  // 2) Fallback: check GitHub licenses.txt
  try {
    const res = await fetch(`${LICENSE_URL}?t=${Date.now()}`, { cache: 'no-store' });
    if (!res.ok) {
      if (!serverChecked) {
        lastError = `Internet/server dikkat (RAW ${res.status})`;
      } else {
        lastError = '';
      }
      debugInfo = `Server=${serverChecked ? 'OK(0)' : 'err'} | RAW ${res.status} | ID ${id}`;
      return getLicenseState();
    }
    const body = await res.text();
    if (!body.includes(MARKER)) {
      lastError = serverChecked ? '' : 'License file marker nahi mila';
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
    } else if (serverChecked) {
      localStorage.removeItem(K_EXPIRY);
    }
    lastError = '';
    debugInfo = `Server=${serverChecked ? 'OK' : 'off'} | RAW 200 entries=${entries} | ID ${id} ${
      expiry > 0 ? `mili (${formatRemainingText(expiry)})` : 'NAHI mili'
    }`;
  } catch (err) {
    if (serverChecked) {
      localStorage.removeItem(K_EXPIRY);
      lastError = '';
      debugInfo = `Server OK (NAHI mili) | ID ${id}`;
    } else {
      const msg = err instanceof Error ? err.message : 'Network error';
      lastError = `Internet/server dikkat: ${msg}`;
      debugInfo = `RAW err | ${msg}`;
    }
  }
  return getLicenseState();
}

// ---------- Instant Business UPI Payment & Admin API Helpers ----------

export async function fetchPublicPlans(): Promise<PublicPlansInfo> {
  const res = await fetch(`/api/public/plans?t=${Date.now()}`, { cache: 'no-store' });
  if (!res.ok) {
    throw new Error('Payment plans load nahi ho paye');
  }
  return (await res.json()) as PublicPlansInfo;
}

export async function createPaymentOrder(deviceId: string, planId: string): Promise<PaymentOrderData> {
  const res = await fetch('/api/payments/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, planId }),
  });
  const data = (await res.json()) as PaymentOrderData & { error?: string };
  if (!res.ok) {
    throw new Error(data.error || 'QR generate nahi ho paya');
  }
  return data;
}

export async function pollPaymentOrderStatus(orderId: string): Promise<PaymentPollStatus> {
  const res = await fetch(`/api/payments/orders/${encodeURIComponent(orderId)}?t=${Date.now()}`, {
    cache: 'no-store',
  });
  const data = (await res.json()) as PaymentPollStatus & { error?: string };
  if (!res.ok) {
    throw new Error(data.error || 'Order check fail');
  }
  return data;
}

export async function getAdminAuthState(token: string | null): Promise<{
  authenticated: boolean;
  viaEnv: boolean;
  canClaimFirstRun: boolean;
}> {
  const res = await fetch('/api/admin/auth-state', {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    cache: 'no-store',
  });
  if (!res.ok) throw new Error('Auth status check fail');
  return (await res.json()) as {
    authenticated: boolean;
    viaEnv: boolean;
    canClaimFirstRun: boolean;
  };
}

export async function claimAdminPassword(password: string): Promise<{ token: string; expiresAt: number }> {
  const res = await fetch('/api/admin/claim', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  });
  const data = (await res.json()) as { token?: string; expiresAt?: number; error?: string };
  if (!res.ok || !data.token) {
    throw new Error(data.error || 'Admin setup fail');
  }
  return { token: data.token, expiresAt: data.expiresAt || 0 };
}

export async function loginAdmin(password: string): Promise<{ token: string; expiresAt: number }> {
  const res = await fetch('/api/admin/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  });
  const data = (await res.json()) as { token?: string; expiresAt?: number; error?: string };
  if (!res.ok || !data.token) {
    throw new Error(data.error || 'Login fail');
  }
  return { token: data.token, expiresAt: data.expiresAt || 0 };
}

export async function fetchAdminDashboard(token: string): Promise<AdminDashboardData> {
  const res = await fetch('/api/admin/dashboard', {
    headers: { Authorization: `Bearer ${token}` },
    cache: 'no-store',
  });
  const data = (await res.json()) as AdminDashboardData & { error?: string };
  if (!res.ok) throw new Error(data.error || 'Dashboard load fail');
  return data;
}

export async function updateAdminBusinessConfig(
  token: string,
  payload: { businessName: string; businessUpiVpa: string; plans: SubscriptionPlan[] }
): Promise<void> {
  const res = await fetch('/api/admin/config', {
    method: 'PUT',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = (await res.json()) as { error?: string };
  if (!res.ok) throw new Error(data.error || 'Settings save nahi hui');
}

export async function rotateMerchantWebhookSecret(token: string): Promise<{ webhookSecret: string }> {
  const res = await fetch('/api/admin/webhook-secret/rotate', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = (await res.json()) as { webhookSecret?: string; error?: string };
  if (!res.ok || !data.webhookSecret) throw new Error(data.error || 'Secret generate fail');
  return { webhookSecret: data.webhookSecret };
}

export async function adminConfirmOrder(token: string, orderId: string, utr?: string): Promise<void> {
  const res = await fetch(`/api/admin/orders/${encodeURIComponent(orderId)}/confirm`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ utr: utr || '' }),
  });
  const data = (await res.json()) as { error?: string };
  if (!res.ok) throw new Error(data.error || 'Confirm fail');
}

export async function adminGrantDevice(
  token: string,
  deviceId: string,
  days: number
): Promise<void> {
  const res = await fetch('/api/admin/licenses/grant', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ deviceId, days }),
  });
  const data = (await res.json()) as { error?: string };
  if (!res.ok) throw new Error(data.error || 'Approve nahi ho paya');
}

export async function adminRevokeDevice(token: string, deviceId: string): Promise<void> {
  const res = await fetch('/api/admin/licenses/revoke', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ deviceId }),
  });
  const data = (await res.json()) as { error?: string };
  if (!res.ok) throw new Error(data.error || 'Revoke fail');
}

export async function adminKillDevice(
  token: string,
  deviceId: string,
  killed: boolean,
  killMessage?: string
): Promise<void> {
  const res = await fetch(`/api/admin/devices/${encodeURIComponent(deviceId)}/kill`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ killed, killMessage: killMessage || '' }),
  });
  const data = (await res.json()) as { error?: string };
  if (!res.ok) throw new Error(data.error || 'Device action fail');
}

export async function adminDeleteDevice(token: string, deviceId: string): Promise<void> {
  const res = await fetch(`/api/admin/devices/${encodeURIComponent(deviceId)}`, {
    method: 'DELETE',
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });
  const data = (await res.json()) as { error?: string };
  if (!res.ok) throw new Error(data.error || 'Device remove fail');
}

export async function adminUpdateAppControl(
  token: string,
  payload: {
    globalKillSwitch?: boolean;
    killMode?: 'lock' | 'crash';
    globalKillMessage?: string;
    broadcastMessage?: string;
    pushToGitHub?: boolean;
    updateInfo?: AppControlConfig['updateInfo'];
  }
): Promise<{
  appControl: AppControlConfig;
  updateTxtContent: string;
  githubPushed: boolean;
  githubError?: string;
}> {
  const res = await fetch('/api/admin/app-control', {
    method: 'PUT',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });
  const data = (await res.json()) as {
    appControl?: AppControlConfig;
    updateTxtContent?: string;
    githubPushed?: boolean;
    githubError?: string;
    error?: string;
  };
  if (!res.ok || !data.appControl) throw new Error(data.error || 'Update publish fail');
  return {
    appControl: data.appControl,
    updateTxtContent: data.updateTxtContent || '',
    githubPushed: Boolean(data.githubPushed),
    githubError: data.githubError,
  };
}


