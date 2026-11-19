import React, { useState } from 'react';
import { Copy, ExternalLink, RefreshCw, Share2, ShieldCheck, ShieldOff } from 'lucide-react';
import {
  refreshLicenseAsync,
  setCustomDeviceId,
  setPreviewUnlocked,
} from '../lib/licenseManager';
import {
  CURRENT_VERSION_NAME,
  checkForUpdate,
  snoozeUpdate,
} from '../lib/updateManager';
import type { LicenseState, UpdateInfo } from '../types';

interface LicenseUpdateViewProps {
  license: LicenseState;
  onLicenseChange: (next: LicenseState) => void;
  updateInfo: UpdateInfo | null;
  onUpdateInfoChange: (info: UpdateInfo | null) => void;
  onNotify: (message: string) => void;
  onEnterApp: () => void;
}

export const LicenseUpdateView: React.FC<LicenseUpdateViewProps> = ({
  license,
  onLicenseChange,
  updateInfo,
  onUpdateInfoChange,
  onNotify,
  onEnterApp,
}) => {
  const [checkingLicense, setCheckingLicense] = useState(false);
  const [checkingUpdate, setCheckingUpdate] = useState(false);
  const [updateStatusMsg, setUpdateStatusMsg] = useState('');
  const [customIdInput, setCustomIdInput] = useState(license.deviceId);

  const handleCopyId = async () => {
    try {
      await navigator.clipboard.writeText(license.deviceId);
      onNotify('ID copy ho gayi');
    } catch {
      onNotify(`Device ID: ${license.deviceId}`);
    }
  };

  const handleShareWhatsApp = async () => {
    const text = `SwiftGate Device ID: ${license.deviceId}`;
    if (navigator.share) {
      try {
        await navigator.share({ title: 'SwiftGate Device ID', text });
        return;
      } catch {
        // Fallback to WhatsApp URL
      }
    }
    const url = `https://wa.me/?text=${encodeURIComponent(text)}`;
    const link = document.createElement('a');
    link.href = url;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.click();
  };

  const handleCheckApproval = async () => {
    setCheckingLicense(true);
    const next = await refreshLicenseAsync();
    onLicenseChange(next);
    setCheckingLicense(false);
    if (next.expiryMs > 0 && Date.now() < next.expiryMs) {
      onNotify('Approval active hai!');
    } else if (next.lastError) {
      onNotify(next.lastError);
    } else {
      onNotify('Is Device ID par abhi approval nahi mila');
    }
  };

  const handleApplyDeviceId = async () => {
    setCustomDeviceId(customIdInput);
    await handleCheckApproval();
  };

  const handleTogglePreviewLock = () => {
    const nextUnlocked = !license.previewBypass;
    setPreviewUnlocked(nextUnlocked);
    onLicenseChange({
      ...license,
      previewBypass: nextUnlocked,
      isActive: (license.expiryMs > 0 && Date.now() < license.expiryMs) || nextUnlocked,
    });
    onNotify(
      nextUnlocked
        ? 'Web Preview Mode unlocked'
        : 'Approval Gate locked — ab bina GitHub approval ke main screen lock rahegi'
    );
  };

  const handleCheckUpdate = async () => {
    setCheckingUpdate(true);
    const res = await checkForUpdate(true);
    onUpdateInfoChange(res.info);
    setUpdateStatusMsg(res.status);
    setCheckingUpdate(false);
  };

  return (
    <div className="mx-auto max-w-3xl px-4 py-6 sm:px-6">
      <div className="mb-6">
        <h1 className="font-display text-2xl font-bold text-white">
          {license.isActive ? 'License & Updates' : 'Approval Zaroori Hai'}
        </h1>
        <p className="mt-1 text-sm text-[#9E9E9E]">
          Neeche wali Device ID admin ko bhejo. Approve hote hi app khud chalu ho jayega.
        </p>
      </div>

      {/* License Card */}
      <div className="rounded-2xl border border-white/10 bg-[#1E1E1E] p-6">
        <div className="text-center">
          <p className="text-xs font-medium tracking-wide text-[#9E9E9E]">DEVICE ID</p>
          <p className="font-mono-tabular mt-2 select-all text-3xl font-bold tracking-widest text-white">
            {license.deviceId}
          </p>
          <p
            className={`mt-3 text-sm font-semibold ${
              license.isActive ? 'text-[#5AD278]' : 'text-[#FFB74D]'
            }`}
          >
            {license.remainingText}
          </p>
        </div>

        <div className="mt-6 grid grid-cols-1 gap-2.5 sm:grid-cols-3">
          <button
            type="button"
            onClick={handleCopyId}
            className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl bg-[#2A2A2A] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#363636]"
          >
            <Copy className="h-4 w-4" />
            <span>ID Copy Karein</span>
          </button>

          <button
            type="button"
            onClick={handleShareWhatsApp}
            className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl bg-[#2A2A2A] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#363636]"
          >
            <Share2 className="h-4 w-4" />
            <span>ID WhatsApp par Bhejein</span>
          </button>

          <button
            type="button"
            onClick={handleCheckApproval}
            disabled={checkingLicense}
            className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl bg-[#43A047] px-4 py-2.5 text-sm font-bold text-white hover:bg-[#388E3C] disabled:opacity-60"
          >
            <RefreshCw className={`h-4 w-4 ${checkingLicense ? 'animate-spin' : ''}`} />
            <span>{checkingLicense ? 'Check ho raha hai...' : 'Approval Check Karein'}</span>
          </button>
        </div>

        {(license.lastError || license.debugInfo) && (
          <div className="mt-4 rounded-xl bg-[#141414] p-3 text-xs text-[#B0BEC5]">
            {license.lastError && <p className="text-[#EF5350]">{license.lastError}</p>}
            {license.debugInfo && (
              <p className="font-mono-tabular mt-1 text-[#9E9E9E]">[{license.debugInfo}]</p>
            )}
          </div>
        )}

        {/* Device ID tester & Preview Gate toggle */}
        <div className="mt-5 border-t border-white/10 pt-4">
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <input
              type="text"
              value={customIdInput}
              onChange={(e) => setCustomIdInput(e.target.value.toUpperCase())}
              maxLength={12}
              placeholder="Custom Device ID test karein"
              className="font-mono-tabular h-10 flex-1 rounded-lg border border-white/10 bg-[#2A2A2A] px-3 text-sm text-white focus:border-[#43A047] focus:outline-none"
            />
            <button
              type="button"
              onClick={handleApplyDeviceId}
              className="min-h-[40px] rounded-lg bg-[#2A2A2A] px-4 py-2 text-xs font-semibold text-white hover:bg-[#363636]"
            >
              ID Apply & Check
            </button>
            <button
              type="button"
              onClick={handleTogglePreviewLock}
              className={`flex min-h-[40px] items-center justify-center gap-1.5 rounded-lg border px-3.5 py-2 text-xs font-semibold ${
                license.previewBypass
                  ? 'border-[#5AD278]/40 bg-[#235F37]/50 text-[#5AD278]'
                  : 'border-[#EF5350]/40 bg-[#C62828]/20 text-[#EF5350]'
              }`}
            >
              {license.previewBypass ? (
                <>
                  <ShieldCheck className="h-4 w-4" />
                  <span>Preview Unlocked</span>
                </>
              ) : (
                <>
                  <ShieldOff className="h-4 w-4" />
                  <span>Gate Locked</span>
                </>
              )}
            </button>
          </div>
          {license.isActive && (
            <button
              type="button"
              onClick={onEnterApp}
              className="mt-3 min-h-[42px] w-full rounded-xl border border-white/15 py-2 text-xs font-semibold text-white hover:bg-white/5"
            >
              Control Panel Kholein
            </button>
          )}
        </div>
      </div>

      {/* App Update Card (UpdateActivity / UpdateManager) */}
      <div className="mt-6 rounded-2xl border border-white/10 bg-[#1E1E1E] p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="font-display text-lg font-bold text-white">App Update Check</h2>
            <p className="mt-0.5 text-sm text-[#9E9E9E]">
              Aapka version: <span className="font-mono-tabular text-white">{CURRENT_VERSION_NAME}</span>
            </p>
          </div>
          <button
            type="button"
            onClick={handleCheckUpdate}
            disabled={checkingUpdate}
            className="min-h-[40px] rounded-lg bg-[#2A2A2A] px-4 py-2 text-xs font-semibold text-white hover:bg-[#363636]"
          >
            {checkingUpdate ? 'Checking...' : 'Update Check Karein'}
          </button>
        </div>

        {updateStatusMsg && (
          <p className="mt-3 text-xs text-[#B0BEC5]">{updateStatusMsg}</p>
        )}

        {updateInfo && (
          <div className="mt-4 rounded-xl border border-[#FFB74D]/40 bg-[#171717] p-4">
            <h3 className="font-display text-base font-bold text-white">
              Naya Update Available — v{updateInfo.name}
            </h3>
            {updateInfo.forced && (
              <p className="mt-1 text-xs font-bold text-[#EF5350]">
                Ye update zaroori hai. Update karne ke baad hi app chalega.
              </p>
            )}
            {updateInfo.notes.length > 0 && (
              <ul className="mt-3 space-y-1 text-sm text-[#E0E0E0]">
                {updateInfo.notes.map((note, idx) => (
                  <li key={idx}>• {note}</li>
                ))}
              </ul>
            )}
            <div className="mt-4 flex flex-wrap gap-2.5">
              <a
                href={updateInfo.url}
                target="_blank"
                rel="noopener noreferrer"
                className="flex min-h-[40px] items-center gap-2 rounded-lg bg-[#43A047] px-4 py-2 text-xs font-bold text-white hover:bg-[#388E3C]"
              >
                <span>Update Karein</span>
                <ExternalLink className="h-3.5 w-3.5" />
              </a>
              {!updateInfo.forced && (
                <button
                  type="button"
                  onClick={() => {
                    snoozeUpdate(updateInfo.code);
                    onUpdateInfoChange(null);
                    onNotify('Update 6 ghante ke liye snooze ho gaya');
                  }}
                  className="min-h-[40px] rounded-lg bg-[#2A2A2A] px-4 py-2 text-xs font-semibold text-[#B0BEC5] hover:text-white"
                >
                  Baad Me
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
