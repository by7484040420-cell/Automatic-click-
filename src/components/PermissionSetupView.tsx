import React, { useState } from 'react';
import { OEM_AUTOSTART_GUIDES } from '../lib/permissionManager';
import type { PermissionState } from '../types';

interface PermissionSetupViewProps {
  permissions: PermissionState;
  onUpdatePermissions: (next: PermissionState) => void;
  onNotify: (message: string) => void;
  onDone: () => void;
}

export const PermissionSetupView: React.FC<PermissionSetupViewProps> = ({
  permissions,
  onUpdatePermissions,
  onNotify,
  onDone,
}) => {
  const [wizardStep, setWizardStep] = useState<number | null>(null);

  const togglePerm = (key: keyof Pick<PermissionState, 'accessibility' | 'overlay' | 'notification' | 'battery' | 'autostart'>) => {
    const next = { ...permissions, [key]: !permissions[key] };
    onUpdatePermissions(next);
  };

  const handleNotificationRequest = async () => {
    if (typeof window !== 'undefined' && 'Notification' in window && Notification.permission === 'default') {
      try {
        const res = await Notification.requestPermission();
        if (res === 'granted') {
          onUpdatePermissions({ ...permissions, notification: true });
          onNotify('Notifications: Allowed');
          return;
        }
      } catch {
        // Fallback to manual toggle
      }
    }
    togglePerm('notification');
  };

  const runSetupWizard = () => {
    const steps: Array<{
      key: keyof Pick<PermissionState, 'accessibility' | 'overlay' | 'battery' | 'autostart' | 'notification'>;
      msg: string;
    }> = [
      {
        key: 'accessibility',
        msg: "1) 'SwiftGate' dhoondo aur ON karo (Accessibility Service)",
      },
      {
        key: 'overlay',
        msg: "2) 'Display over other apps' ON karo",
      },
      {
        key: 'battery',
        msg: '3) Battery: Allow / Unrestricted dabao',
      },
      {
        key: 'autostart',
        msg: '4) Autostart / Background ko ON karo',
      },
      {
        key: 'notification',
        msg: '5) Notifications Allow karo',
      },
    ];

    const firstPending = steps.findIndex((s) => !permissions[s.key]);
    if (firstPending === -1) {
      setWizardStep(null);
      onNotify('Setup poora. Bubble ON hai to screen band nahi hogi.');
      return;
    }
    setWizardStep(firstPending);
    onNotify(steps[firstPending].msg);
  };

  const oemGuide = OEM_AUTOSTART_GUIDES[permissions.manufacturer] ?? OEM_AUTOSTART_GUIDES['Generic / Web'];

  return (
    <div className="mx-auto max-w-3xl px-4 py-6 sm:px-6">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-bold text-white">Permission Setup</h1>
          <p className="mt-1 text-sm text-[#9E9E9E]">
            Har permission/service ka status yahan manage karein.
          </p>
        </div>
        <button
          type="button"
          onClick={runSetupWizard}
          className="min-h-[42px] rounded-xl bg-[#43A047] px-4 py-2 text-xs font-bold text-white hover:bg-[#388E3C]"
        >
          Auto Setup Chalayein
        </button>
      </div>

      {wizardStep !== null && (
        <div className="mb-5 rounded-xl border border-[#FFB74D]/40 bg-[#1E1E1E] p-4 text-sm text-[#FFB74D]">
          Setup Step {wizardStep + 1} of 5 — Neeche di gayi setting ko ON karein, phir dobara &ldquo;Auto Setup Chalayein&rdquo; dabayein.
        </div>
      )}

      <div className="space-y-4">
        {/* 1. Accessibility */}
        <div className="rounded-xl border border-white/10 bg-[#1E1E1E] p-4">
          <p className="text-sm font-medium text-white">
            {permissions.accessibility
              ? '✅ Accessibility Service: ON'
              : '❌ Accessibility Service: OFF (order auto-accept nahi hoga)'}
          </p>
          <button
            type="button"
            onClick={() => togglePerm('accessibility')}
            className="mt-3 min-h-[44px] w-full rounded-lg bg-[#2A2A2A] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#363636]"
          >
            {permissions.accessibility
              ? 'Accessibility Service OFF Karein'
              : 'Accessibility Settings Kholein (Allow)'}
          </button>
        </div>

        {/* 2. Overlay */}
        <div className="rounded-xl border border-white/10 bg-[#1E1E1E] p-4">
          <p className="text-sm font-medium text-white">
            {permissions.overlay
              ? '✅ Display over other apps: Allowed'
              : '❌ Display over other apps: Not Allowed (bubble nahi dikhega)'}
          </p>
          <button
            type="button"
            onClick={() => togglePerm('overlay')}
            className="mt-3 min-h-[44px] w-full rounded-lg bg-[#2A2A2A] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#363636]"
          >
            {permissions.overlay
              ? 'Overlay Permission Revoke Karein'
              : 'Overlay Permission Kholein (Allow)'}
          </button>
        </div>

        {/* 3. Notification */}
        <div className="rounded-xl border border-white/10 bg-[#1E1E1E] p-4">
          <p className="text-sm font-medium text-white">
            {permissions.notification
              ? '✅ Notifications: Allowed'
              : '❌ Notifications: Not Allowed (status icon nahi dikhega)'}
          </p>
          <button
            type="button"
            onClick={handleNotificationRequest}
            className="mt-3 min-h-[44px] w-full rounded-lg bg-[#2A2A2A] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#363636]"
          >
            {permissions.notification
              ? 'Notification Permission OFF Karein'
              : 'Notification Permission Allow Karein'}
          </button>
        </div>

        {/* 4. Battery & OEM Autostart (SetupWizard / DeviceAutoStartHelper) */}
        <div className="rounded-xl border border-white/10 bg-[#1E1E1E] p-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-sm font-medium text-white">
              {permissions.battery && permissions.autostart
                ? '✅ Battery & Autostart: Unrestricted'
                : '⚠️ Battery Saver / Autostart Setup'}
            </p>
            <select
              value={permissions.manufacturer}
              onChange={(e) =>
                onUpdatePermissions({ ...permissions, manufacturer: e.target.value })
              }
              aria-label="Phone brand"
              className="h-9 rounded-lg border border-white/15 bg-[#2A2A2A] px-2.5 text-xs text-white focus:outline-none"
            >
              {Object.keys(OEM_AUTOSTART_GUIDES).map((oem) => (
                <option key={oem} value={oem}>
                  {oem}
                </option>
              ))}
            </select>
          </div>
          <p className="mt-2 text-xs text-[#B0BEC5]">{oemGuide}</p>
          <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
            <button
              type="button"
              onClick={() => togglePerm('battery')}
              className="min-h-[42px] rounded-lg bg-[#2A2A2A] px-3 py-2 text-xs font-semibold text-white hover:bg-[#363636]"
            >
              {permissions.battery ? '✅ Battery: Unrestricted' : 'Battery Exemption Allow Karein'}
            </button>
            <button
              type="button"
              onClick={() => togglePerm('autostart')}
              className="min-h-[42px] rounded-lg bg-[#2A2A2A] px-3 py-2 text-xs font-semibold text-white hover:bg-[#363636]"
            >
              {permissions.autostart ? '✅ Autostart: ON' : 'Autostart / Background ON Karein'}
            </button>
          </div>
        </div>
      </div>

      <button
        type="button"
        onClick={onDone}
        className="mt-6 min-h-[48px] w-full rounded-xl bg-[#43A047] px-6 py-3 text-sm font-bold tracking-wide text-white hover:bg-[#388E3C]"
      >
        DONE / WAPAS JAYEIN
      </button>
    </div>
  );
};
