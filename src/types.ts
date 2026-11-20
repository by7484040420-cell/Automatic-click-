export type ScreenId = 'home' | 'areas' | 'swipe' | 'permissions' | 'license';

export type AreaTab = 'PICKUP' | 'DROP';

export type LocationMode = 'A' | 'R';

export type SelectionMap = Record<string, string[]>;

export type CityLocalitiesMap = Record<string, string[]>;

export type ZonePincodesMap = Record<string, string[]>;

export interface Order {
  id: string;
  pickupCity: string;
  pickupArea: string;
  dropCity: string;
  dropArea: string;
  itemInfo: string;
}

export interface SwipeZoneConfig {
  /** Percentage of container width (0..100) */
  startX: number;
  /** Percentage of container width (0..100) */
  endX: number;
  /** Percentage of container height (0..100) */
  y: number;
}

export interface PermissionState {
  accessibility: boolean;
  overlay: boolean;
  notification: boolean;
  battery: boolean;
  autostart: boolean;
  manufacturer: string;
}

export interface LicenseState {
  deviceId: string;
  expiryMs: number;
  isUnlimited: boolean;
  isActive: boolean;
  remainingText: string;
  lastError: string;
  debugInfo: string;
  previewBypass: boolean;
}

export interface UpdateInfo {
  code: number;
  name: string;
  minCode: number;
  url: string;
  notes: string[];
  forced: boolean;
}

export interface MatchAnalysis {
  matched: boolean;
  rejected: boolean;
  rejectedBy: string | null;
  reason: string;
  pickupText: string;
  dropText: string;
  pickupDistanceKm?: number | null;
  isTripDetails: boolean;
  hasAcceptWord: boolean;
  pickupMatchedBy: string | null;
  dropMatchedBy: string | null;
}

export type LiveWatcherStatus =
  | 'OFF'
  | 'READY'
  | 'ORDER FOUND'
  | 'ORDER UTHA RAHA'
  | 'ORDER REJECTED'
  | 'ACTION RESULT UNKNOWN';

export interface SubscriptionPlan {
  id: string;
  name: string;
  durationHours: number;
  priceInr: number;
  badge?: string;
}

export interface PublicPlansInfo {
  businessName: string;
  businessUpiVpa: string;
  businessUpiConfigured: boolean;
  razorpayEnabled: boolean;
  merchantWebhookConfigured: boolean;
  instantAutoApproveReady: boolean;
  plans: SubscriptionPlan[];
  serverTimeMs: number;
}

export interface PaymentOrderData {
  orderId: string;
  deviceId: string;
  plan: SubscriptionPlan;
  amountInr: number;
  mode: 'razorpay' | 'business_upi';
  upiUri: string;
  qrDataUrl: string;
  razorpayQrImageUrl: string;
  qrPngUrl: string;
  status: 'PENDING' | 'PAID' | 'EXPIRED';
  expiresAt: number;
  serverTimeMs: number;
}

export interface PaymentPollStatus {
  orderId: string;
  deviceId: string;
  planName: string;
  amountInr: number;
  status: 'PENDING' | 'PAID' | 'EXPIRED';
  utr: string;
  paidAt: number;
  expiresAt: number;
  licenseActive: boolean;
  licenseExpiryMs: number;
  isUnlimited: boolean;
  serverTimeMs: number;
}

export interface AdminDashboardData {
  config: {
    businessName: string;
    businessUpiVpa: string;
  };
  plans: SubscriptionPlan[];
  orders: Array<{
    orderId: string;
    deviceId: string;
    planName: string;
    amountInr: number;
    mode: string;
    status: 'PENDING' | 'PAID' | 'EXPIRED';
    utr?: string;
    verifiedBy?: string;
    createdAt: number;
    paidAt?: number;
  }>;
  licenses: Array<{
    deviceId: string;
    expiryMs: number;
    isUnlimited: boolean;
    planName: string;
    lastOrderId?: string;
    updatedAt: number;
  }>;
  integrations: {
    razorpayConfigured: boolean;
    razorpayWebhookConfigured: boolean;
    merchantWebhookEnvConfigured: boolean;
    merchantWebhookKeySet: boolean;
    githubTokenConfigured: boolean;
  };
  serverTimeMs: number;
}

