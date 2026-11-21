import React, { useCallback, useEffect, useState } from 'react';
import {
  CheckCircle2,
  Copy,
  ExternalLink,
  KeyRound,
  Lock,
  RefreshCw,
  Settings2,
  Share2,
  ShieldCheck,
  ShieldOff,
  Trash2,
} from 'lucide-react';
import {
  adminGrantDevice,
  adminRevokeDevice,
  claimAdminPassword,
  fetchAdminDashboard,
  getAdminAuthState,
  loginAdmin,
  refreshLicenseAsync,
  setCustomDeviceId,
  setPreviewUnlocked,
} from '../lib/licenseManager';
import {
  CURRENT_VERSION_NAME,
  checkForUpdate,
  snoozeUpdate,
} from '../lib/updateManager';
import type {
  AdminDashboardData,
  LicenseState,
  UpdateInfo,
} from '../types';

interface LicenseUpdateViewProps {
  license: LicenseState;
  onLicenseChange: (next: LicenseState) => void;
  updateInfo: UpdateInfo | null;
  onUpdateInfoChange: (info: UpdateInfo | null) => void;
  onNotify: (message: string) => void;
  onEnterApp: () => void;
}

const OWNER_WHATSAPP_WA = '918383872065';
const OWNER_WHATSAPP_DISPLAY = '8383872065';

const DURATION_OPTIONS = [
  { label: '1 Day', days: 1 },
  { label: '7 Days', days: 7 },
  { label: '15 Days', days: 15 },
  { label: '30 Days', days: 30 },
  { label: 'Unlimited', days: 0 },
];

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

  // Owner Manual Approval Panel state (token strictly in JS memory)
  const [adminOpen, setAdminOpen] = useState(false);
  const [adminToken, setAdminToken] = useState<string | null>(null);
  const [authState, setAuthState] = useState<{
    authenticated: boolean;
    viaEnv: boolean;
    canClaimFirstRun: boolean;
  } | null>(null);
  const [passInput, setPassInput] = useState('');
  const [authBusy, setAuthBusy] = useState(false);
  const [adminDash, setAdminDash] = useState<AdminDashboardData | null>(null);
  const [grantDeviceInput, setGrantDeviceInput] = useState('');
  const [grantDays, setGrantDays] = useState<number>(7);
  const [grantBusy, setGrantBusy] = useState(false);

  const loadAdminState = useCallback(
    async (tok: string | null) => {
      try {
        const state = await getAdminAuthState(tok);
        setAuthState(state);
        if (tok && state.authenticated) {
          const dash = await fetchAdminDashboard(tok);
          setAdminDash(dash);
        }
      } catch (err) {
        onNotify(err instanceof Error ? err.message : 'Admin panel load nahi hua');
      }
    },
    [onNotify]
  );

  useEffect(() => {
    if (adminOpen) {
      void loadAdminState(adminToken);
    }
  }, [adminOpen, adminToken, loadAdminState]);

  const handleCopyId = async () => {
    try {
      await navigator.clipboard.writeText(license.deviceId);
      onNotify('Code copy ho gaya');
    } catch {
      onNotify(`Device Code: ${license.deviceId}`);
    }
  };

  const openOwnerWhatsApp = async (mode: 'approval' | 'help') => {
    try {
      await navigator.clipboard.writeText(license.deviceId);
    } catch {
      // Ignore clipboard permission failure
    }
    const text =
      mode === 'help'
        ? `Namaste Sir, mujhe SwiftGate app help chahiye.\nDevice Code: ${license.deviceId}`
        : `Namaste Sir, SwiftGate approval chahiye.\nDevice Code: ${license.deviceId}`;

    onNotify(
      `Code copy ho gaya (${license.deviceId}) — WhatsApp (${OWNER_WHATSAPP_DISPLAY}) khul raha hai`
    );
    const url = `https://wa.me/${OWNER_WHATSAPP_WA}?text=${encodeURIComponent(text)}`;
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
    if (next.expiryMs > 0 && (next.isUnlimited || Date.now() < next.expiryMs)) {
      onNotify('Approval active hai!');
    } else if (next.lastError) {
      onNotify(next.lastError);
    } else {
      onNotify('Is Device Code par abhi approval nahi mila');
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
      isActive:
        (license.expiryMs > 0 && (license.isUnlimited || Date.now() < license.expiryMs)) ||
        nextUnlocked,
    });
    onNotify(
      nextUnlocked
        ? 'Web Preview Mode unlocked'
        : 'Approval Gate locked — ab approval ke baad hi app khulega'
    );
  };

  const handleAdminAuthSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!passInput.trim()) return;
    setAuthBusy(true);
    try {
      const res = authState?.canClaimFirstRun
        ? await claimAdminPassword(passInput.trim())
        : await loginAdmin(passInput.trim());
      setAdminToken(res.token);
      setPassInput('');
      await loadAdminState(res.token);
      onNotify('Owner Approval Panel unlocked');
    } catch (err) {
      onNotify(err instanceof Error ? err.message : 'Auth fail');
    } finally {
      setAuthBusy(false);
    }
  };

  const handleGrantDeviceAdmin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!adminToken) return;
    const cleanCode = grantDeviceInput.trim().toUpperCase();
    if (!cleanCode) return;
    setGrantBusy(true);
    try {
      await adminGrantDevice(adminToken, cleanCode, grantDays);
      setGrantDeviceInput('');
      await loadAdminState(adminToken);
      onNotify(`${cleanCode} ko approval de diya gaya!`);
    } catch (err) {
      onNotify(err instanceof Error ? err.message : 'Approve fail');
    } finally {
      setGrantBusy(false);
    }
  };

  const handleRevokeDeviceAdmin = async (deviceId: string) => {
    if (!adminToken) return;
    try {
      await adminRevokeDevice(adminToken, deviceId);
      await loadAdminState(adminToken);
      onNotify(`${deviceId} ka access band kiya gaya`);
    } catch (err) {
      onNotify(err instanceof Error ? err.message : 'Revoke fail');
    }
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
      <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-bold text-white">
            {license.isActive ? 'Approval & Updates' : 'Approval Zaroori Hai'}
          </h1>
          <p className="mt-1 text-sm text-[#9E9E9E]">
            Apna Device Code WhatsApp par bhejein ({OWNER_WHATSAPP_DISPLAY}) — approval milne ke
            baad "Approval Check Karein" dabayein.
          </p>
        </div>

        <button
          type="button"
          onClick={() => setAdminOpen((v) => !v)}
          className="flex min-h-[40px] items-center gap-2 rounded-xl border border-white/15 bg-[#1E1E1E] px-3.5 py-2 text-xs font-semibold text-white hover:bg-[#2A2A2A]"
        >
          <Settings2 className="h-4 w-4 text-[#81C784]" />
          <span>{adminOpen ? 'Owner Panel Band Karein' : 'Owner Approval Panel'}</span>
        </button>
      </div>

      {/* Collapsible Owner Manual Approval Panel */}
      {adminOpen && (
        <div className="mb-6 rounded-2xl border border-[#FFB74D]/35 bg-[#1A1A1A] p-6">
          <div className="flex items-center gap-2">
            <KeyRound className="h-5 w-5 text-[#FFB74D]" />
            <h2 className="font-display text-lg font-bold text-white">Owner Approval Panel</h2>
          </div>

          {!adminToken || !adminDash ? (
            <form onSubmit={handleAdminAuthSubmit} className="mt-4 space-y-3">
              <p className="text-xs text-[#B0BEC5]">
                {authState?.canClaimFirstRun
                  ? 'First-run setup: Apna Owner Admin Password banayein (kam se kam 6 characters).'
                  : 'Apna Owner Admin Passcode daalkar device approval panel kholein.'}
              </p>
              <div className="flex flex-col gap-2 sm:flex-row">
                <input
                  type="password"
                  value={passInput}
                  onChange={(e) => setPassInput(e.target.value)}
                  placeholder={
                    authState?.canClaimFirstRun
                      ? 'Naya Owner Password set karein'
                      : 'Owner Passcode daalein'
                  }
                  className="h-11 flex-1 rounded-xl border border-white/15 bg-[#2A2A2A] px-3.5 text-sm text-white focus:border-[#FFB74D] focus:outline-none"
                />
                <button
                  type="submit"
                  disabled={authBusy}
                  className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl bg-[#FFB74D] px-5 py-2.5 text-xs font-bold text-[#121212] hover:bg-[#FFA726] disabled:opacity-60"
                >
                  <Lock className="h-4 w-4" />
                  <span>
                    {authBusy
                      ? 'Wait...'
                      : authState?.canClaimFirstRun
                        ? 'Password Set & Unlock'
                        : 'Unlock Owner Panel'}
                  </span>
                </button>
              </div>
            </form>
          ) : (
            <div className="mt-5 space-y-5">
              <form onSubmit={handleGrantDeviceAdmin} className="space-y-3">
                <p className="text-xs text-[#B0BEC5]">
                  WhatsApp par aaya hua rider ka Device Code yahan daalein aur time select karke Approve karein:
                </p>
                <div className="flex flex-col gap-2 sm:flex-row">
                  <input
                    type="text"
                    value={grantDeviceInput}
                    onChange={(e) => setGrantDeviceInput(e.target.value.toUpperCase())}
                    placeholder="Device Code daalein (jaise E5E0BAE1AA80)"
                    className="font-mono-tabular h-11 flex-1 rounded-xl border border-white/15 bg-[#2A2A2A] px-3.5 text-sm text-white focus:border-[#43A047] focus:outline-none"
                  />
                  <select
                    value={grantDays}
                    onChange={(e) => setGrantDays(Number(e.target.value))}
                    className="h-11 rounded-xl border border-white/15 bg-[#2A2A2A] px-3 text-sm text-white focus:border-[#43A047] focus:outline-none"
                  >
                    {DURATION_OPTIONS.map((opt) => (
                      <option key={opt.days} value={opt.days}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                  <button
                    type="submit"
                    disabled={grantBusy}
                    className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl bg-[#43A047] px-5 py-2.5 text-xs font-bold text-white hover:bg-[#388E3C] disabled:opacity-60"
                  >
                    <CheckCircle2 className="h-4 w-4" />
                    <span>{grantBusy ? 'Approving...' : 'Approve Karein'}</span>
                  </button>
                </div>
              </form>

              {adminDash.licenses.length > 0 && (
                <div>
                  <h3 className="text-xs font-bold uppercase tracking-wider text-[#B0BEC5]">
                    Approved Devices ({adminDash.licenses.length})
                  </h3>
                  <div className="mt-2 space-y-1.5">
                    {adminDash.licenses.map((lic) => (
                      <div
                        key={lic.deviceId}
                        className="flex items-center justify-between rounded-lg bg-[#141414] px-3 py-2 text-xs"
                      >
                        <div>
                          <span className="font-mono-tabular font-bold text-white">{lic.deviceId}</span>{' '}
                          · <span className="text-[#5AD278]">{lic.planName}</span>
                        </div>
                        <button
                          type="button"
                          onClick={() => void handleRevokeDeviceAdmin(lic.deviceId)}
                          className="flex items-center gap-1 rounded bg-[#C62828]/25 px-2 py-1 text-[11px] text-[#EF5350] hover:bg-[#C62828]/40"
                        >
                          <Trash2 className="h-3 w-3" />
                          <span>Revoke</span>
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* Device Code & WhatsApp Approval Card */}
      <div className="rounded-2xl border border-white/10 bg-[#1E1E1E] p-6">
        <div className="text-center">
          <p className="text-xs font-medium tracking-wide text-[#9E9E9E]">AAPKA DEVICE CODE</p>
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
            <span>Code Copy Karein</span>
          </button>

          <button
            type="button"
            onClick={() => void openOwnerWhatsApp('approval')}
            className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl bg-[#235F37] px-4 py-2.5 text-sm font-bold text-white hover:bg-[#2E7D32]"
          >
            <Share2 className="h-4 w-4 text-[#5AD278]" />
            <span>WhatsApp par Bhejein ({OWNER_WHATSAPP_DISPLAY})</span>
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

        <button
          type="button"
          onClick={() => void openOwnerWhatsApp('help')}
          className="mt-3 flex min-h-[42px] w-full items-center justify-center gap-2 rounded-xl border border-[#5AD278]/35 bg-[#235F37]/25 px-4 py-2 text-xs font-semibold text-[#81C784] hover:bg-[#235F37]/45"
        >
          <Share2 className="h-4 w-4" />
          <span>Help / Approval Support — WhatsApp ({OWNER_WHATSAPP_DISPLAY})</span>
        </button>

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
              placeholder="Custom Device Code test karein"
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

      {/* App Update Card */}
      <div className="mt-6 rounded-2xl border border-white/10 bg-[#1E1E1E] p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="font-display text-lg font-bold text-white">App Update Check</h2>
            <p className="mt-0.5 text-sm text-[#9E9E9E]">
              Aapka version:{' '}
              <span className="font-mono-tabular text-white">{CURRENT_VERSION_NAME}</span>
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
