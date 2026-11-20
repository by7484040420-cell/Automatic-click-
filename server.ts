import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import express from 'express';
import type { NextFunction, Request, Response } from 'express';
import QRCode from 'qrcode';
import { createServer as createViteServer } from 'vite';

const PORT = Number(process.env.PORT || 3000);
const IS_PROD = process.env.NODE_ENV === 'production';

// Data directory confined inside workspace root
const DATA_DIR = path.resolve(process.cwd(), 'data');
const STORE_PATH = path.resolve(DATA_DIR, 'payments-store.json');

// Signing secret for in-memory Admin Bearer tokens (uses SESSION_SECRET env or ephemeral 32-byte key)
const SESSION_SIGNING_SECRET =
  process.env.SESSION_SECRET && process.env.SESSION_SECRET.trim().length >= 16
    ? process.env.SESSION_SECRET.trim()
    : crypto.randomBytes(32).toString('hex');

const GITHUB_OWNER = 'by7484040420-cell';
const GITHUB_REPO = 'bipin-licenses';
const GITHUB_LICENSE_PATH = 'licenses.txt';
const GITHUB_MARKER = '#BIPIN-LICENSES';

const DEVICE_ID_RE = /^[A-Z0-9]{6,24}$/;
const UPI_VPA_RE = /^[a-zA-Z0-9._-]{2,64}@[a-zA-Z0-9.-]{2,32}$/;
const ORDER_ID_RE = /^SG[A-F0-9]{10}$/;

export interface SubscriptionPlan {
  id: string;
  name: string;
  durationHours: number; // 0 = lifetime (NEVER)
  priceInr: number;
  badge?: string;
}

export interface PaymentOrder {
  orderId: string;
  deviceId: string;
  planId: string;
  planName: string;
  durationHours: number;
  amountInr: number;
  amountPaise: number;
  upiUri: string;
  mode: 'razorpay' | 'business_upi';
  razorpayQrId?: string;
  razorpayQrImageUrl?: string;
  status: 'PENDING' | 'PAID' | 'EXPIRED';
  utr?: string;
  verifiedBy?: string;
  createdAt: number;
  expiresAt: number;
  paidAt?: number;
  lastGatewayCheckAt?: number;
}

export interface LicenseRecord {
  deviceId: string;
  expiryMs: number; // Number.MAX_SAFE_INTEGER for unlimited
  isUnlimited: boolean;
  planName: string;
  lastOrderId?: string;
  updatedAt: number;
}

interface StoreState {
  adminPasswordHash?: string;
  webhookSecretHash?: string;
  config: {
    businessName: string;
    businessUpiVpa: string;
  };
  plans: SubscriptionPlan[];
  orders: Record<string, PaymentOrder>;
  licenses: Record<string, LicenseRecord>;
  usedUtrs: Record<string, string>; // utr -> orderId
}

const DEFAULT_PLANS: SubscriptionPlan[] = [
  { id: 'plan_1d', name: '1 Day Pass', durationHours: 24, priceInr: 30 },
  { id: 'plan_7d', name: '7 Days Pass', durationHours: 24 * 7, priceInr: 180, badge: 'Popular' },
  { id: 'plan_15d', name: '15 Days Pass', durationHours: 24 * 15, priceInr: 350, badge: 'Best Value' },
];

const DEFAULT_BUSINESS_NAME = 'BIPIN KUMAR YADAV';
const DEFAULT_BUSINESS_UPI_VPA = 'paytmqr5i4hko@ptys';

function createDefaultStore(): StoreState {
  return {
    config: {
      businessName: (process.env.BUSINESS_NAME || DEFAULT_BUSINESS_NAME).trim(),
      businessUpiVpa: (process.env.BUSINESS_UPI_VPA || DEFAULT_BUSINESS_UPI_VPA).trim(),
    },
    plans: DEFAULT_PLANS,
    orders: {},
    licenses: {},
    usedUtrs: {},
  };
}

function loadStore(): StoreState {
  try {
    if (!fs.existsSync(STORE_PATH)) {
      return createDefaultStore();
    }
    const raw = fs.readFileSync(STORE_PATH, 'utf8');
    const parsed = JSON.parse(raw) as Partial<StoreState>;
    const def = createDefaultStore();
    const envVpa = (process.env.BUSINESS_UPI_VPA || '').trim();
    const envName = (process.env.BUSINESS_NAME || '').trim();
    return {
      adminPasswordHash: typeof parsed.adminPasswordHash === 'string' ? parsed.adminPasswordHash : undefined,
      webhookSecretHash: typeof parsed.webhookSecretHash === 'string' ? parsed.webhookSecretHash : undefined,
      config: {
        businessName: parsed.config?.businessName || envName || def.config.businessName,
        businessUpiVpa: parsed.config?.businessUpiVpa || envVpa || def.config.businessUpiVpa,
      },
      plans: Array.isArray(parsed.plans) && parsed.plans.length > 0 ? parsed.plans : def.plans,
      orders: parsed.orders && typeof parsed.orders === 'object' ? parsed.orders : {},
      licenses: parsed.licenses && typeof parsed.licenses === 'object' ? parsed.licenses : {},
      usedUtrs: parsed.usedUtrs && typeof parsed.usedUtrs === 'object' ? parsed.usedUtrs : {},
    };
  } catch {
    return createDefaultStore();
  }
}

let store: StoreState = loadStore();

function saveStore(): void {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const tmpPath = path.resolve(DATA_DIR, `payments-store.${process.pid}.tmp`);
  if (!tmpPath.startsWith(DATA_DIR + path.sep)) {
    throw new Error('Invalid store path');
  }
  fs.writeFileSync(tmpPath, JSON.stringify(store, null, 2), 'utf8');
  fs.renameSync(tmpPath, STORE_PATH);
}

// ---------- Crypto & Auth Helpers ----------

function safeEqualText(a: string, b: string): boolean {
  const ha = crypto.createHash('sha256').update(a, 'utf8').digest();
  const hb = crypto.createHash('sha256').update(b, 'utf8').digest();
  return crypto.timingSafeEqual(ha, hb);
}

function safeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length || a.length === 0) return false;
  try {
    const ba = Buffer.from(a, 'hex');
    const bb = Buffer.from(b, 'hex');
    if (ba.length === 0 || ba.length !== bb.length) return false;
    return crypto.timingSafeEqual(ba, bb);
  } catch {
    return false;
  }
}

function hashPasswordScrypt(password: string): string {
  const salt = crypto.randomBytes(16).toString('hex');
  const derived = crypto.scryptSync(password, salt, 64).toString('hex');
  return `${salt}:${derived}`;
}

function verifyPasswordScrypt(password: string, storedHash: string): boolean {
  const parts = storedHash.split(':');
  if (parts.length !== 2) return false;
  const [salt, expectedHex] = parts;
  if (!salt || !expectedHex) return false;
  const derivedHex = crypto.scryptSync(password, salt, 64).toString('hex');
  return safeEqualHex(derivedHex, expectedHex);
}

function createAdminSessionToken(): { token: string; expiresAt: number } {
  const expiresAt = Date.now() + 24 * 60 * 60 * 1000;
  const payload = Buffer.from(JSON.stringify({ role: 'admin', exp: expiresAt }), 'utf8').toString('base64url');
  const sig = crypto.createHmac('sha256', SESSION_SIGNING_SECRET).update(payload).digest('base64url');
  return { token: `${payload}.${sig}`, expiresAt };
}

function verifyAdminSessionToken(token: string): boolean {
  const parts = token.split('.');
  if (parts.length !== 2) return false;
  const [payload, sig] = parts;
  if (!payload || !sig) return false;
  const expectedSig = crypto.createHmac('sha256', SESSION_SIGNING_SECRET).update(payload).digest('base64url');
  if (!safeEqualText(sig, expectedSig)) return false;
  try {
    const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')) as { role?: string; exp?: number };
    return decoded.role === 'admin' && typeof decoded.exp === 'number' && decoded.exp > Date.now();
  } catch {
    return false;
  }
}

function isEnvAdminPasscodeConfigured(): boolean {
  return Boolean(process.env.ADMIN_PASSCODE && process.env.ADMIN_PASSCODE.trim().length > 0);
}

function requireAdminAuth(req: Request, res: Response, next: NextFunction): void {
  const authHeader = req.headers.authorization || '';
  if (!authHeader.startsWith('Bearer ')) {
    res.status(401).json({ error: 'Admin authentication required' });
    return;
  }
  const token = authHeader.slice(7).trim();
  if (!verifyAdminSessionToken(token)) {
    res.status(401).json({ error: 'Admin session expired or invalid' });
    return;
  }
  next();
}

function verifyMerchantWebhookSecret(provided: string): boolean {
  const envSecret = (process.env.MERCHANT_WEBHOOK_SECRET || '').trim();
  if (envSecret.length > 0) {
    return safeEqualText(provided, envSecret);
  }
  if (store.webhookSecretHash) {
    const providedHash = crypto.createHash('sha256').update(provided, 'utf8').digest('hex');
    return safeEqualHex(providedHash, store.webhookSecretHash);
  }
  return false;
}

// ---------- Razorpay Helpers ----------

function getRazorpayCreds(): { keyId: string; keySecret: string } | null {
  const keyId = (process.env.RAZORPAY_KEY_ID || '').trim();
  const keySecret = (process.env.RAZORPAY_KEY_SECRET || '').trim();
  if (!keyId || !keySecret) return null;
  return { keyId, keySecret };
}

function isSafeHttpsUrl(raw: unknown): raw is string {
  if (typeof raw !== 'string') return false;
  try {
    const u = new URL(raw);
    return u.protocol === 'https:';
  } catch {
    return false;
  }
}

async function createRazorpayUpiQr(
  order: PaymentOrder
): Promise<{ id: string; imageUrl?: string; qrString?: string } | null> {
  const creds = getRazorpayCreds();
  if (!creds) return null;
  const auth = Buffer.from(`${creds.keyId}:${creds.keySecret}`, 'utf8').toString('base64');
  const closeBy = Math.floor(order.expiresAt / 1000);
  const resp = await fetch('https://api.razorpay.com/v1/payments/qr_codes', {
    method: 'POST',
    headers: {
      Authorization: `Basic ${auth}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      type: 'upi_qr',
      name: `SwiftGate ${order.deviceId}`,
      usage: 'single_use',
      fixed_amount: true,
      payment_amount: order.amountPaise,
      description: `${order.planName} (${order.deviceId})`,
      close_by: closeBy,
      notes: {
        orderId: order.orderId,
        deviceId: order.deviceId,
        planId: order.planId,
      },
    }),
  });
  if (!resp.ok) {
    const errText = await resp.text().catch(() => '');
    throw new Error(`Razorpay QR error ${resp.status}: ${errText.slice(0, 120)}`);
  }
  const data = (await resp.json()) as {
    id?: string;
    image_url?: string;
    qr_code?: string;
  };
  if (!data.id) return null;
  return {
    id: data.id,
    imageUrl: isSafeHttpsUrl(data.image_url) ? data.image_url : undefined,
    qrString: typeof data.qr_code === 'string' && data.qr_code.startsWith('upi://') ? data.qr_code : undefined,
  };
}

async function pollRazorpayQrPaid(order: PaymentOrder): Promise<void> {
  if (order.status !== 'PENDING' || !order.razorpayQrId) return;
  const now = Date.now();
  if (order.lastGatewayCheckAt && now - order.lastGatewayCheckAt < 1200) return;
  order.lastGatewayCheckAt = now;

  const creds = getRazorpayCreds();
  if (!creds) return;
  const auth = Buffer.from(`${creds.keyId}:${creds.keySecret}`, 'utf8').toString('base64');
  try {
    const resp = await fetch(
      `https://api.razorpay.com/v1/payments/qr_codes/${encodeURIComponent(order.razorpayQrId)}/payments?count=5`,
      { headers: { Authorization: `Basic ${auth}` } }
    );
    if (!resp.ok) return;
    const data = (await resp.json()) as {
      items?: Array<{
        id?: string;
        status?: string;
        amount?: number;
        acquirer_data?: { rrn?: string; upi_transaction_id?: string };
      }>;
    };
    const captured = data.items?.find(
      (item) => item.status === 'captured' && (item.amount ?? 0) >= order.amountPaise
    );
    if (captured) {
      const utr = captured.acquirer_data?.rrn || captured.acquirer_data?.upi_transaction_id || captured.id || '';
      await approveOrderPayment(order, utr, 'razorpay_poll');
    }
  } catch {
    // Transient network glitch; next poll will retry
  }
}

// ---------- License Fulfillment & GitHub Auto-Sync ----------

function formatIstForGitHub(expiryMs: number): string {
  if (expiryMs >= Number.MAX_SAFE_INTEGER) return 'NEVER';
  const fmt = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
  const parts = fmt.formatToParts(new Date(expiryMs));
  const get = (t: string) => parts.find((p) => p.type === t)?.value || '00';
  return `${get('year')}-${get('month')}-${get('day')} ${get('hour')}:${get('minute')}`;
}

async function syncDeviceToGitHub(deviceId: string, expiryMs: number, label: string): Promise<void> {
  const token = (process.env.GITHUB_LICENSE_TOKEN || '').trim();
  if (!token) return;
  const apiUrl = `https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/contents/${GITHUB_LICENSE_PATH}`;
  try {
    const getResp = await fetch(apiUrl, {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'application/vnd.github+json',
        'User-Agent': 'SwiftGate-Server',
      },
    });
    if (!getResp.ok) return;
    const fileData = (await getResp.json()) as { sha?: string; content?: string };
    const existingRaw = fileData.content
      ? Buffer.from(fileData.content.replace(/\n/g, ''), 'base64').toString('utf8')
      : `${GITHUB_MARKER}\n`;

    const lines = existingRaw.split(/\r?\n/);
    if (!lines.some((l) => l.includes(GITHUB_MARKER))) {
      lines.unshift(GITHUB_MARKER);
    }
    const expiryStr = formatIstForGitHub(expiryMs);
    const cleanLabel = label.replace(/\s+/g, '_').slice(0, 24);
    const newEntry = `${deviceId}  ${expiryStr}  ${cleanLabel}`;

    const filtered = lines.filter((line) => {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('#')) return true;
      const firstToken = trimmed.split(/\s+/)[0]?.toUpperCase();
      return firstToken !== deviceId.toUpperCase();
    });
    filtered.push(newEntry);

    const updatedText = filtered.join('\n').replace(/\n+$/, '') + '\n';
    await fetch(apiUrl, {
      method: 'PUT',
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'application/vnd.github+json',
        'Content-Type': 'application/json',
        'User-Agent': 'SwiftGate-Server',
      },
      body: JSON.stringify({
        message: `Auto-approve ${deviceId} (${expiryStr})`,
        content: Buffer.from(updatedText, 'utf8').toString('base64'),
        sha: fileData.sha,
      }),
    });
  } catch {
    // Non-blocking best-effort GitHub sync
  }
}

async function approveOrderPayment(order: PaymentOrder, utr: string, verifiedBy: string): Promise<LicenseRecord> {
  if (order.status === 'PAID') {
    const existing = store.licenses[order.deviceId];
    if (existing) return existing;
  }

  const cleanUtr = utr.trim().toUpperCase();
  if (cleanUtr && store.usedUtrs[cleanUtr] && store.usedUtrs[cleanUtr] !== order.orderId) {
    throw new Error('UTR already redeemed on another order');
  }

  const now = Date.now();
  order.status = 'PAID';
  order.paidAt = now;
  order.verifiedBy = verifiedBy;
  if (cleanUtr) {
    order.utr = cleanUtr;
    store.usedUtrs[cleanUtr] = order.orderId;
  }

  const prev = store.licenses[order.deviceId];
  let newExpiryMs: number;
  if (order.durationHours <= 0 || prev?.isUnlimited) {
    newExpiryMs = Number.MAX_SAFE_INTEGER;
  } else {
    const baseTime = prev && prev.expiryMs > now ? prev.expiryMs : now;
    newExpiryMs = baseTime + order.durationHours * 3600 * 1000;
  }

  const license: LicenseRecord = {
    deviceId: order.deviceId,
    expiryMs: newExpiryMs,
    isUnlimited: newExpiryMs >= Number.MAX_SAFE_INTEGER,
    planName: order.planName,
    lastOrderId: order.orderId,
    updatedAt: now,
  };
  store.licenses[order.deviceId] = license;
  saveStore();

  void syncDeviceToGitHub(order.deviceId, newExpiryMs, order.planName);
  return license;
}

// Simple per-IP rate limiter for order creation & login
const rateBuckets = new Map<string, { count: number; resetAt: number }>();

function checkRateLimit(key: string, maxRequests: number, windowMs: number): boolean {
  const now = Date.now();
  const entry = rateBuckets.get(key);
  if (!entry || entry.resetAt <= now) {
    rateBuckets.set(key, { count: 1, resetAt: now + windowMs });
    return true;
  }
  if (entry.count >= maxRequests) return false;
  entry.count += 1;
  return true;
}

function allocateUniqueOrderPaise(basePaise: number, now: number): number {
  const activePendingPaise = new Set(
    Object.values(store.orders)
      .filter((o) => o.status === 'PENDING' && o.expiresAt > now)
      .map((o) => o.amountPaise)
  );
  if (!activePendingPaise.has(basePaise)) {
    return basePaise;
  }
  for (let offset = 1; offset <= 99; offset++) {
    const candidate = basePaise + offset;
    if (!activePendingPaise.has(candidate)) {
      return candidate;
    }
  }
  return basePaise;
}

function buildUpiUri(pa: string, pn: string, amountInr: number, orderId: string, note: string): string {
  const qs = new URLSearchParams({
    pa,
    pn: pn.slice(0, 40),
    am: amountInr.toFixed(2),
    cu: 'INR',
    tr: orderId,
    tn: note.slice(0, 60),
  });
  return `upi://pay?${qs.toString()}`;
}

// ---------- Express Server Setup ----------

async function startServer(): Promise<void> {
  const app = express();
  app.set('trust proxy', 1);

  // Raw body webhook for Razorpay HMAC signature verification (must run BEFORE express.json)
  app.post('/api/payments/razorpay/webhook', express.raw({ type: 'application/json', limit: '256kb' }), async (req: Request, res: Response) => {
    const secret = (process.env.RAZORPAY_WEBHOOK_SECRET || '').trim();
    if (!secret) {
      res.status(503).json({ error: 'Razorpay webhook secret not configured' });
      return;
    }
    const sigHeader = String(req.headers['x-razorpay-signature'] || '');
    const rawBuf = Buffer.isBuffer(req.body) ? req.body : Buffer.from(String(req.body || ''), 'utf8');
    const expectedHex = crypto.createHmac('sha256', secret).update(rawBuf).digest('hex');
    if (!safeEqualHex(sigHeader.trim(), expectedHex)) {
      res.status(401).json({ error: 'Invalid signature' });
      return;
    }

    try {
      const payload = JSON.parse(rawBuf.toString('utf8')) as {
        event?: string;
        payload?: {
          payment?: {
            entity?: {
              id?: string;
              amount?: number;
              status?: string;
              notes?: Record<string, string>;
              acquirer_data?: { rrn?: string; upi_transaction_id?: string };
            };
          };
          qr_code?: {
            entity?: {
              id?: string;
              notes?: Record<string, string>;
            };
          };
        };
      };

      const paymentEntity = payload.payload?.payment?.entity;
      const qrEntity = payload.payload?.qr_code?.entity;
      const orderId =
        paymentEntity?.notes?.orderId ||
        qrEntity?.notes?.orderId ||
        '';
      let order = orderId ? store.orders[orderId] : undefined;
      if (!order && qrEntity?.id) {
        order = Object.values(store.orders).find((o) => o.razorpayQrId === qrEntity.id);
      }
      if (!order) {
        res.json({ ok: true, ignored: true });
        return;
      }

      const paidPaise = paymentEntity?.amount ?? 0;
      const isCaptured =
        payload.event === 'qr_code.credited' ||
        payload.event === 'payment_link.paid' ||
        paymentEntity?.status === 'captured';
      if (isCaptured && (paidPaise === 0 || paidPaise >= order.amountPaise)) {
        const utr =
          paymentEntity?.acquirer_data?.rrn ||
          paymentEntity?.acquirer_data?.upi_transaction_id ||
          paymentEntity?.id ||
          '';
        await approveOrderPayment(order, utr, 'razorpay_webhook');
      }
      res.json({ ok: true });
    } catch (err) {
      res.status(400).json({ error: err instanceof Error ? err.message : 'Invalid payload' });
    }
  });

  app.use(express.json({ limit: '128kb' }));

  // ---------- Public APIs ----------

  app.get('/api/health', (_req: Request, res: Response) => {
    const rzp = Boolean(getRazorpayCreds());
    const vpa = Boolean(store.config.businessUpiVpa);
    res.json({
      ok: true,
      razorpayEnabled: rzp,
      businessUpiConfigured: vpa,
      serverTimeMs: Date.now(),
    });
  });

  app.get('/api/public/plans', (_req: Request, res: Response) => {
    const rzp = Boolean(getRazorpayCreds());
    const vpa = store.config.businessUpiVpa.trim();
    const webhookConfigured = Boolean((process.env.MERCHANT_WEBHOOK_SECRET || '').trim() || store.webhookSecretHash);
    res.json({
      businessName: store.config.businessName,
      businessUpiVpa: vpa,
      businessUpiConfigured: Boolean(vpa),
      razorpayEnabled: rzp,
      merchantWebhookConfigured: webhookConfigured,
      instantAutoApproveReady: rzp || webhookConfigured,
      plans: store.plans,
      serverTimeMs: Date.now(),
    });
  });

  app.get('/api/license/status', (req: Request, res: Response) => {
    const deviceId = String(req.query.deviceId || '').trim().toUpperCase();
    if (!DEVICE_ID_RE.test(deviceId)) {
      res.status(400).json({ error: 'Valid Device ID (6-24 alphanumeric) required' });
      return;
    }
    const rec = store.licenses[deviceId];
    const now = Date.now();
    const active = Boolean(rec && (rec.isUnlimited || rec.expiryMs > now));
    res.json({
      deviceId,
      active,
      expiryMs: rec ? rec.expiryMs : 0,
      isUnlimited: Boolean(rec?.isUnlimited),
      planName: rec?.planName || '',
      serverTimeMs: now,
    });
  });

  app.post('/api/payments/orders', async (req: Request, res: Response) => {
    const clientIp = req.ip || 'unknown';
    if (!checkRateLimit(`order:${clientIp}`, 30, 10 * 60 * 1000)) {
      res.status(429).json({ error: 'Bahut zyada requests — 1 minute baad try karein' });
      return;
    }

    const body = (req.body || {}) as { deviceId?: unknown; planId?: unknown };
    const deviceId = typeof body.deviceId === 'string' ? body.deviceId.trim().toUpperCase() : '';
    const planId = typeof body.planId === 'string' ? body.planId.trim() : '';

    if (!DEVICE_ID_RE.test(deviceId)) {
      res.status(400).json({ error: 'Invalid Device ID' });
      return;
    }
    const plan = store.plans.find((p) => p.id === planId);
    if (!plan) {
      res.status(400).json({ error: 'Invalid plan selected' });
      return;
    }

    const rzpCreds = getRazorpayCreds();
    const vpa = store.config.businessUpiVpa.trim();
    if (!rzpCreds && !vpa) {
      res.status(400).json({
        error: 'Business UPI ID ya Razorpay abhi configure nahi hai — Owner Panel me set karein',
      });
      return;
    }

    const now = Date.now();
    const orderId = 'SG' + crypto.randomBytes(5).toString('hex').toUpperCase();
    const expiresAt = now + 15 * 60 * 1000;
    const basePaise = Math.round(plan.priceInr * 100);
    const finalPaise = rzpCreds ? basePaise : allocateUniqueOrderPaise(basePaise, now);
    const finalAmountInr = Number((finalPaise / 100).toFixed(2));
    const order: PaymentOrder = {
      orderId,
      deviceId,
      planId: plan.id,
      planName: plan.name,
      durationHours: plan.durationHours,
      amountInr: finalAmountInr,
      amountPaise: finalPaise,
      upiUri: vpa ? buildUpiUri(vpa, store.config.businessName, finalAmountInr, orderId, `SwiftGate ${plan.name} ${deviceId}`) : '',
      mode: rzpCreds ? 'razorpay' : 'business_upi',
      status: 'PENDING',
      createdAt: now,
      expiresAt,
    };

    try {
      if (rzpCreds) {
        const qr = await createRazorpayUpiQr(order);
        if (qr) {
          order.razorpayQrId = qr.id;
          order.razorpayQrImageUrl = qr.imageUrl;
          if (qr.qrString) order.upiUri = qr.qrString;
        }
      }
      store.orders[orderId] = order;
      saveStore();

      const qrDataUrl = order.upiUri
        ? await QRCode.toDataURL(order.upiUri, { width: 320, margin: 2, errorCorrectionLevel: 'M' })
        : '';

      res.json({
        orderId: order.orderId,
        deviceId: order.deviceId,
        plan,
        amountInr: order.amountInr,
        mode: order.mode,
        upiUri: order.upiUri,
        qrDataUrl,
        razorpayQrImageUrl: order.razorpayQrImageUrl || '',
        qrPngUrl: `/api/payments/orders/${order.orderId}/qr.png`,
        status: order.status,
        expiresAt: order.expiresAt,
        serverTimeMs: now,
      });
    } catch (err) {
      res.status(502).json({
        error: err instanceof Error ? err.message : 'Payment QR create nahi ho paya',
      });
    }
  });

  app.get('/api/payments/orders/:orderId/qr.png', async (req: Request, res: Response) => {
    const orderId = String(req.params.orderId || '').trim().toUpperCase();
    if (!ORDER_ID_RE.test(orderId)) {
      res.status(400).send('Invalid order ID');
      return;
    }
    const order = store.orders[orderId];
    if (!order) {
      res.status(404).send('QR not found');
      return;
    }
    if (order.upiUri) {
      const png = await QRCode.toBuffer(order.upiUri, { width: 360, margin: 2, errorCorrectionLevel: 'M' });
      res.setHeader('Content-Type', 'image/png');
      res.setHeader('Cache-Control', 'no-store');
      res.send(png);
      return;
    }
    if (order.razorpayQrImageUrl && isSafeHttpsUrl(order.razorpayQrImageUrl)) {
      try {
        const parsed = new URL(order.razorpayQrImageUrl);
        if (
          parsed.hostname === 'rzp.io' ||
          parsed.hostname === 'api.razorpay.com' ||
          parsed.hostname.endsWith('.razorpay.com')
        ) {
          const upstream = await fetch(parsed.toString());
          if (upstream.ok) {
            const buf = Buffer.from(await upstream.arrayBuffer());
            res.setHeader('Content-Type', upstream.headers.get('content-type') || 'image/png');
            res.setHeader('Cache-Control', 'no-store');
            res.send(buf);
            return;
          }
        }
      } catch {
        // Fall through to 404
      }
    }
    res.status(404).send('QR not found');
  });

  app.get('/api/payments/orders/:orderId', async (req: Request, res: Response) => {
    const orderId = String(req.params.orderId || '').trim().toUpperCase();
    if (!ORDER_ID_RE.test(orderId)) {
      res.status(400).json({ error: 'Invalid order ID' });
      return;
    }
    const order = store.orders[orderId];
    if (!order) {
      res.status(404).json({ error: 'Order not found' });
      return;
    }

    const now = Date.now();
    if (order.status === 'PENDING' && now > order.expiresAt) {
      order.status = 'EXPIRED';
      saveStore();
    } else if (order.status === 'PENDING' && order.razorpayQrId) {
      await pollRazorpayQrPaid(order);
    }

    const lic = store.licenses[order.deviceId];
    res.json({
      orderId: order.orderId,
      deviceId: order.deviceId,
      planName: order.planName,
      amountInr: order.amountInr,
      status: order.status,
      utr: order.utr || '',
      paidAt: order.paidAt || 0,
      expiresAt: order.expiresAt,
      licenseActive: Boolean(lic && (lic.isUnlimited || lic.expiryMs > now)),
      licenseExpiryMs: lic?.expiryMs || 0,
      isUnlimited: Boolean(lic?.isUnlimited),
      serverTimeMs: now,
    });
  });

  // Zero-fee Business Account Notification / SMS Auto-Forwarder Webhook (1-second instant approval)
  app.post('/api/payments/merchant-webhook', async (req: Request, res: Response) => {
    const providedSecret = String(req.headers['x-webhook-secret'] || '').trim();
    if (!providedSecret || !verifyMerchantWebhookSecret(providedSecret)) {
      res.status(401).json({ error: 'Invalid merchant webhook secret' });
      return;
    }

    const body = (req.body || {}) as {
      text?: unknown;
      amount?: unknown;
      utr?: unknown;
      orderId?: unknown;
    };
    const rawText = typeof body.text === 'string' ? body.text : '';
    if (
      rawText &&
      /\b(?:paid\s+to|sent\s+to|debited|failed|declined|payment\s+request|requested\s+money)\b/i.test(rawText) &&
      !/\b(?:received|credited|paid\s+you|added\s+to)\b/i.test(rawText)
    ) {
      res.status(200).json({ ok: true, ignored: true, reason: 'Non-credit notification ignored' });
      return;
    }

    let orderId = typeof body.orderId === 'string' ? body.orderId.trim().toUpperCase() : '';
    if (!ORDER_ID_RE.test(orderId)) {
      const m = /\b(SG[A-F0-9]{10})\b/i.exec(rawText);
      orderId = m ? m[1].toUpperCase() : '';
    }

    let amountNum = typeof body.amount === 'number' ? body.amount : Number(body.amount);
    if (!Number.isFinite(amountNum) || amountNum <= 0) {
      const am = /(?:rs\.?|inr|₹)\s*([0-9,]+(?:\.[0-9]{1,2})?)/i.exec(rawText);
      if (am) {
        amountNum = Number(am[1].replace(/,/g, ''));
      } else if (/\b(?:received|credited|paid\s+you)\b/i.test(rawText)) {
        const bare = /\b([0-9,]+\.[0-9]{1,2})\b/.exec(rawText);
        amountNum = bare ? Number(bare[1].replace(/,/g, '')) : 0;
      } else {
        amountNum = 0;
      }
    }

    let utr = typeof body.utr === 'string' ? body.utr.trim().toUpperCase() : '';
    if (!utr) {
      const um = /\b([0-9]{12})\b/.exec(rawText);
      utr = um ? um[1] : '';
    }
    if (utr && store.usedUtrs[utr]) {
      res.status(409).json({ error: 'UTR already processed', orderId: store.usedUtrs[utr] });
      return;
    }

    const now = Date.now();
    let targetOrder: PaymentOrder | undefined = orderId ? store.orders[orderId] : undefined;

    // Fallback: match unique PENDING order by exact INR amount inside active 15-min window
    if (!targetOrder && amountNum > 0) {
      const targetPaise = Math.round(amountNum * 100);
      const candidates = Object.values(store.orders).filter(
        (o) =>
          o.status === 'PENDING' &&
          o.expiresAt > now &&
          o.amountPaise === targetPaise
      );
      if (candidates.length === 1) {
        targetOrder = candidates[0];
      } else if (candidates.length > 1) {
        res.status(409).json({
          error: 'Multiple pending orders with same amount — include orderId (SG...) in reference',
        });
        return;
      }
    }

    if (!targetOrder) {
      res.status(404).json({ error: 'No matching pending order found' });
      return;
    }
    if (amountNum > 0 && Math.round(amountNum * 100) < targetOrder.amountPaise) {
      res.status(400).json({ error: 'Paid amount is less than order amount' });
      return;
    }

    const license = await approveOrderPayment(targetOrder, utr, 'merchant_webhook');
    res.json({
      ok: true,
      orderId: targetOrder.orderId,
      deviceId: targetOrder.deviceId,
      expiryMs: license.expiryMs,
    });
  });

  // ---------- Admin Auth & Settings Routes ----------

  app.get('/api/admin/auth-state', (req: Request, res: Response) => {
    const authHeader = req.headers.authorization || '';
    const authenticated = authHeader.startsWith('Bearer ') && verifyAdminSessionToken(authHeader.slice(7).trim());
    const viaEnv = isEnvAdminPasscodeConfigured();
    const canClaimFirstRun = !viaEnv && !store.adminPasswordHash;
    res.json({
      authenticated,
      viaEnv,
      canClaimFirstRun,
    });
  });

  app.post('/api/admin/claim', (req: Request, res: Response) => {
    if (isEnvAdminPasscodeConfigured() || store.adminPasswordHash) {
      res.status(403).json({ error: 'Admin password already configured' });
      return;
    }
    const body = (req.body || {}) as { password?: unknown };
    const password = typeof body.password === 'string' ? body.password : '';
    if (password.length < 6 || password.length > 128) {
      res.status(400).json({ error: 'Password kam se kam 6 characters ka rakhein' });
      return;
    }
    store.adminPasswordHash = hashPasswordScrypt(password);
    saveStore();
    const session = createAdminSessionToken();
    res.json({ ok: true, token: session.token, expiresAt: session.expiresAt });
  });

  app.post('/api/admin/login', (req: Request, res: Response) => {
    const clientIp = req.ip || 'unknown';
    if (!checkRateLimit(`login:${clientIp}`, 15, 10 * 60 * 1000)) {
      res.status(429).json({ error: 'Too many login attempts — wait a few minutes' });
      return;
    }
    const body = (req.body || {}) as { password?: unknown };
    const password = typeof body.password === 'string' ? body.password : '';
    if (!password) {
      res.status(400).json({ error: 'Passcode required' });
      return;
    }

    const envPass = (process.env.ADMIN_PASSCODE || '').trim();
    let valid = false;
    if (envPass.length > 0) {
      valid = safeEqualText(password, envPass);
    } else if (store.adminPasswordHash) {
      valid = verifyPasswordScrypt(password, store.adminPasswordHash);
    } else {
      res.status(400).json({ error: 'No admin passcode claimed yet' });
      return;
    }

    if (!valid) {
      res.status(401).json({ error: 'Galat admin passcode' });
      return;
    }
    const session = createAdminSessionToken();
    res.json({ ok: true, token: session.token, expiresAt: session.expiresAt });
  });

  app.get('/api/admin/dashboard', requireAdminAuth, (_req: Request, res: Response) => {
    const orders = Object.values(store.orders)
      .sort((a, b) => b.createdAt - a.createdAt)
      .slice(0, 40);
    const licenses = Object.values(store.licenses).sort((a, b) => b.updatedAt - a.updatedAt);
    res.json({
      config: store.config,
      plans: store.plans,
      orders,
      licenses,
      integrations: {
        razorpayConfigured: Boolean(getRazorpayCreds()),
        razorpayWebhookConfigured: Boolean((process.env.RAZORPAY_WEBHOOK_SECRET || '').trim()),
        merchantWebhookEnvConfigured: Boolean((process.env.MERCHANT_WEBHOOK_SECRET || '').trim()),
        merchantWebhookKeySet: Boolean(store.webhookSecretHash),
        githubTokenConfigured: Boolean((process.env.GITHUB_LICENSE_TOKEN || '').trim()),
      },
      serverTimeMs: Date.now(),
    });
  });

  app.put('/api/admin/config', requireAdminAuth, (req: Request, res: Response) => {
    const body = (req.body || {}) as {
      businessName?: unknown;
      businessUpiVpa?: unknown;
      plans?: unknown;
    };

    if (typeof body.businessName === 'string') {
      const cleanName = body.businessName.trim().slice(0, 50);
      if (cleanName.length >= 2) store.config.businessName = cleanName;
    }

    if (typeof body.businessUpiVpa === 'string') {
      const cleanVpa = body.businessUpiVpa.trim();
      if (cleanVpa && !UPI_VPA_RE.test(cleanVpa)) {
        res.status(400).json({ error: 'Sahi Business UPI ID daalein (jaise: name@okaxis ya paytmqr@paytm)' });
        return;
      }
      store.config.businessUpiVpa = cleanVpa;
    }

    if (Array.isArray(body.plans)) {
      const cleanPlans: SubscriptionPlan[] = [];
      for (const item of body.plans.slice(0, 8)) {
        if (!item || typeof item !== 'object') continue;
        const p = item as Record<string, unknown>;
        const id = typeof p.id === 'string' && /^[a-z0-9_]{2,24}$/i.test(p.id) ? p.id : '';
        const name = typeof p.name === 'string' ? p.name.trim().slice(0, 40) : '';
        const durationHours = Number(p.durationHours);
        const priceInr = Number(p.priceInr);
        if (!id || !name) continue;
        if (!Number.isFinite(durationHours) || durationHours < 0 || durationHours > 87600) continue;
        if (!Number.isFinite(priceInr) || priceInr < 1 || priceInr > 100000) continue;
        cleanPlans.push({
          id,
          name,
          durationHours: Math.round(durationHours),
          priceInr: Math.round(priceInr),
          badge: typeof p.badge === 'string' ? p.badge.trim().slice(0, 20) || undefined : undefined,
        });
      }
      if (cleanPlans.length > 0) {
        store.plans = cleanPlans;
      }
    }

    saveStore();
    res.json({ ok: true, config: store.config, plans: store.plans });
  });

  app.post('/api/admin/webhook-secret/rotate', requireAdminAuth, (_req: Request, res: Response) => {
    const newSecret = 'sg_wh_' + crypto.randomBytes(20).toString('hex');
    store.webhookSecretHash = crypto.createHash('sha256').update(newSecret, 'utf8').digest('hex');
    saveStore();
    res.json({
      ok: true,
      webhookSecret: newSecret,
      note: 'Copy this secret now — for security it is shown only once.',
    });
  });

  // One-tap Owner Merchant Phone Auto-Connect (avoids manual secret copy-paste errors)
  app.post('/api/admin/owner-device/connect', (req: Request, res: Response) => {
    const clientIp = req.ip || 'unknown';
    if (!checkRateLimit(`login:${clientIp}`, 15, 10 * 60 * 1000)) {
      res.status(429).json({ error: 'Bahut zyada tries — thodi der baad try karein' });
      return;
    }

    const body = (req.body || {}) as { passcode?: unknown };
    const passcode = typeof body.passcode === 'string' ? body.passcode.trim() : '';
    if (passcode.length < 6 || passcode.length > 128) {
      res.status(400).json({ error: 'Kam se kam 6 characters ka Owner Passcode daalein' });
      return;
    }

    const envPass = (process.env.ADMIN_PASSCODE || '').trim();
    if (!envPass && !store.adminPasswordHash) {
      res.status(403).json({
        error: 'Suraksha ke liye pehle Website ke Owner Business Panel me jakar apna Admin Password set karein',
      });
      return;
    }
    const valid = envPass
      ? safeEqualText(passcode, envPass)
      : Boolean(store.adminPasswordHash && verifyPasswordScrypt(passcode, store.adminPasswordHash));
    if (!valid) {
      res.status(401).json({ error: 'Galat Owner Passcode — ye sirf Owner ke liye hai' });
      return;
    }

    const envSecret = (process.env.MERCHANT_WEBHOOK_SECRET || '').trim();
    let webhookSecret = envSecret;
    if (!webhookSecret) {
      webhookSecret = 'sg_wh_' + crypto.randomBytes(20).toString('hex');
      store.webhookSecretHash = crypto.createHash('sha256').update(webhookSecret, 'utf8').digest('hex');
    }
    saveStore();

    res.json({
      ok: true,
      webhookSecret,
      businessName: store.config.businessName,
      businessUpiVpa: store.config.businessUpiVpa,
    });
  });

  app.post('/api/admin/orders/:orderId/confirm', requireAdminAuth, async (req: Request, res: Response) => {
    const orderId = String(req.params.orderId || '').trim().toUpperCase();
    if (!ORDER_ID_RE.test(orderId)) {
      res.status(400).json({ error: 'Invalid order ID' });
      return;
    }
    const order = store.orders[orderId];
    if (!order) {
      res.status(404).json({ error: 'Order not found' });
      return;
    }
    const body = (req.body || {}) as { utr?: unknown };
    const utr = typeof body.utr === 'string' ? body.utr.trim().slice(0, 40) : '';
    try {
      const license = await approveOrderPayment(order, utr || `ADMIN-${order.orderId}`, 'admin_verified');
      res.json({ ok: true, order, license });
    } catch (err) {
      res.status(409).json({ error: err instanceof Error ? err.message : 'Unable to confirm order' });
    }
  });

  app.post('/api/admin/licenses/revoke', requireAdminAuth, (req: Request, res: Response) => {
    const body = (req.body || {}) as { deviceId?: unknown };
    const deviceId = typeof body.deviceId === 'string' ? body.deviceId.trim().toUpperCase() : '';
    if (!DEVICE_ID_RE.test(deviceId)) {
      res.status(400).json({ error: 'Invalid Device ID' });
      return;
    }
    delete store.licenses[deviceId];
    saveStore();
    res.json({ ok: true });
  });

  // ---------- Frontend Hosting (Vite dev or built dist) ----------

  if (!IS_PROD) {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distDir = path.resolve(process.cwd(), 'dist');
    app.use(express.static(distDir));
    app.use((_req: Request, res: Response) => {
      res.sendFile(path.resolve(distDir, 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`SwiftGate server listening on http://0.0.0.0:${PORT}`);
  });
}

startServer().catch((err) => {
  console.error('Failed to start SwiftGate server:', err);
  process.exit(1);
});
