import type { PermissionState } from '../types';

const PERMS_KEY = 'swiftgate_permissions_state';

const DEFAULT_STATE: PermissionState = {
  accessibility: true,
  overlay: true,
  notification: true,
  battery: true,
  autostart: false,
  manufacturer: 'Generic / Web',
};

export const OEM_AUTOSTART_GUIDES: Record<string, string> = {
  Xiaomi: 'Security App -> Permissions -> Autostart -> SwiftGate ON karein',
  Vivo: 'i Manager -> App Manager -> Autostart Manager -> SwiftGate ON karein',
  Oppo: 'Phone Manager -> Privacy Permissions -> Startup Manager -> SwiftGate ON karein',
  OnePlus: 'Settings -> Apps -> App Management -> SwiftGate -> Battery Usage -> Allow background',
  Samsung: 'Settings -> Apps -> SwiftGate -> Battery -> Unrestricted chunein',
  Huawei: 'Optimizer -> App Launch -> SwiftGate -> Manage Manually ON karein',
  'Generic / Web': 'App Info -> Battery -> Unrestricted aur Notifications Allow rakhein',
};

export function getPermissions(): PermissionState {
  try {
    const raw = localStorage.getItem(PERMS_KEY);
    if (!raw) return { ...DEFAULT_STATE };
    const parsed = JSON.parse(raw) as Partial<PermissionState>;
    return {
      accessibility: parsed.accessibility ?? DEFAULT_STATE.accessibility,
      overlay: parsed.overlay ?? DEFAULT_STATE.overlay,
      notification: parsed.notification ?? DEFAULT_STATE.notification,
      battery: parsed.battery ?? DEFAULT_STATE.battery,
      autostart: parsed.autostart ?? DEFAULT_STATE.autostart,
      manufacturer: parsed.manufacturer ?? DEFAULT_STATE.manufacturer,
    };
  } catch {
    return { ...DEFAULT_STATE };
  }
}

export function savePermissions(next: PermissionState): PermissionState {
  localStorage.setItem(PERMS_KEY, JSON.stringify(next));
  return next;
}

export function grantAllViaRoot(current: PermissionState): PermissionState {
  return savePermissions({
    ...current,
    accessibility: true,
    overlay: true,
    notification: true,
    battery: true,
    autostart: true,
  });
}

export function isEverythingReady(p: PermissionState): boolean {
  return p.accessibility && p.overlay && p.notification;
}
