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
  triggerNetDrop: boolean;
  reason: string;
  pickupText: string;
  dropText: string;
  isTripDetails: boolean;
  hasAcceptWord: boolean;
  pickupMatchedBy: string | null;
  dropMatchedBy: string | null;
}

export type VpnTargetMode = 'ALL_PHONE' | 'SELECTED_APPS';

export interface VpnPulseConfig {
  enabled: boolean;
  durationMs: number;
  targetMode: VpnTargetMode;
  targetPackages: string[];
  webhookOffUrl: string;
  webhookOnUrl: string;
}

export type VpnPulsePhase = 'CONNECTED' | 'NET_OFF_VPN' | 'RECONNECTING';

export type LiveWatcherStatus =
  | 'OFF'
  | 'READY'
  | 'ORDER FOUND'
  | 'ORDER UTHA RAHA'
  | 'ORDER REJECTED'
  | 'ACTION RESULT UNKNOWN';
