import React, { useCallback, useEffect, useState } from 'react';
import {
  Activity,
  AlertOctagon,
  CheckCircle2,
  Copy,
  ExternalLink,
  KeyRound,
  Lock,
  Megaphone,
  Power,
  RefreshCw,
  Rocket,
  Settings2,
  Share2,
  ShieldCheck,
  ShieldOff,
  Smartphone,
  Trash2,
  UploadCloud,
} from 'lucide-react';
import {
  adminDeleteDevice,
  adminGrantDevice,
  adminKillDevice,
  adminRevokeDevice,
  adminUpdateAppControl,
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
const GITHUB_UPDATE_EDIT_URL =
  'https://github.com/by7484040420-cell/bipin-licenses/blob/main/update.txt';

const DURATION_OPTIONS = [
  { label: '1 Day', days: 1 },
  { label: '7 Days', days: 7 },
  { label: '15 Days', days: 15 },
  { label: '30 Days', days: 30 },
  { label: 'Unlimited', days: 0 },
];

type OwnerTab = 'devices' | 'control' | 'approvals';

function formatLastSeen(ts: number): string {
  if (!ts) return 'Never pinged';
  const diffMin = Math.floor((Date.now() - ts) / 60000);
  if (diffMin < 1) return 'Abhi online';
  if (diffMin < 60) return `${diffMin}m pehle`;
  const h = Math.floor(diffMin / 60);
  if (h < 24) return `${h}h pehle`;
  const d = Math.floor(h / 24);
  return `${d}d pehle`;
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

  // Owner Control Panel state (token strictly in JS memory)
  const [adminOpen, setAdminOpen] = useState(false);
  const [ownerTab, setOwnerTab] = useState<OwnerTab>('devices');
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
  const [deviceSearch, setDeviceSearch] = useState('');
  const [deviceActionId, setDeviceActionId] = useState<string | null>(null);

  // Global Kill Switch & Update Publisher form state
  const [globalKillSwitch, setGlobalKillSwitch] = useState(false);
  const [killMode, setKillMode] = useState<'lock' | 'crash'>('lock');
  const [globalKillMessage, setGlobalKillMessage] = useState('');
  const [broadcastMessage, setBroadcastMessage] = useState('');
  const [pubVersionCode, setPubVersionCode] = useState(4);
  const [pubVersionName, setPubVersionName] = useState('1.4');
  const [pubMinVersionCode, setPubMinVersionCode] = useState(1);
  const [pubDownloadUrl, setPubDownloadUrl] = useState('');
  const [pubNotes, setPubNotes] = useState('');
  const [publishBusy, setPublishBusy] = useState(false);
  const [generatedTxt, setGeneratedTxt] = useState('');

  const syncFormFromDash = useCallback((dash: AdminDashboardData) => {
    setGlobalKillSwitch(dash.appControl.globalKillSwitch);
    setKillMode(dash.appControl.killMode);
    setGlobalKillMessage(dash.appControl.globalKillMessage);
    setBroadcastMessage(dash.appControl.broadcastMessage);
    setPubVersionCode(dash.appControl.updateInfo.versionCode);
    setPubVersionName(dash.appControl.updateInfo.versionName);
    setPubMinVersionCode(dash.appControl.updateInfo.minVersionCode);
    setPubDownloadUrl(dash.appControl.updateInfo.downloadUrl);
    setPubNotes(dash.appControl.updateInfo.notes);
    setGeneratedTxt(dash.updateTxtContent);
  }, []);

  const loadAdminState = useCallback(
    async (tok: string | null) => {
      try {
        const state = await getAdminAuthState(tok);
        setAuthState(state);
        if (tok && state.authenticated) {
          const dash = await fetchAdminDashboard(tok);
          setAdminDash(dash);
          syncFormFromDash(dash);
        }
      } catch (err) {
        onNotify(err instanceof Error ? err.message : 'Admin panel load nahi hua');
      }
    },
    [onNotify, syncFormFromDash]
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
    if (next.killed) {
      onNotify(next.killMessage || 'App owner dwara band kiya gaya hai');
    } else if (next.expiryMs > 0 && (next.isUnlimited || Date.now() < next.expiryMs)) {
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
      onNotify('Owner Control Panel unlocked');
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

  const handleQuickApproveDevice = async (deviceId: string, days: number) => {
    if (!adminToken) return;
    setDeviceActionId(deviceId);
    try {
      await adminGrantDevice(adminToken, deviceId, days);
      await loadAdminState(adminToken);
      onNotify(`${deviceId} ko ${days === 0 ? 'Unlimited' : `${days}d`} approval mil gaya`);
    } catch (err) {
      onNotify(err instanceof Error ? err.message : 'Approve fail');
    } finally {
      setDeviceActionId(null);
    }
  };

  const handleRevokeDeviceAdmin = async (deviceId: string) => {
    if (!adminToken) return;
    setDeviceActionId(deviceId);
    try {
      await adminRevokeDevice(adminToken, deviceId);
      await loadAdminState(adminToken);
      onNotify(`${deviceId} ka license revoke kiya gaya`);
    } catch (err) {
      onNotify(err instanceof Error ? err.message : 'Revoke fail');
    } finally {
      setDeviceActionId(null);
    }
  };

  const handleToggleKillDevice = async (deviceId: string, nextKilled: boolean) => {
    if (!adminToken) return;
    setDeviceActionId(deviceId);
    try {
      await adminKillDevice(
        adminToken,
        deviceId,
        nextKilled,
        nextKilled ? globalKillMessage || 'Owner dwara app band kiya gaya hai.' : ''
      );
      await loadAdminState(adminToken);
      onNotify(
        nextKilled
          ? `${deviceId} par app REMOTE BAND/KILL kar diya gaya`
          : `${deviceId} par app wapas CHALU kar diya gaya`
      );
    } catch (err) {
      onNotify(err instanceof Error ? err.message : 'Kill action fail');
    } finally {
      setDeviceActionId(null);
    }
  };

  const handleDeleteDevice = async (deviceId: string) => {
    if (!adminToken) return;
    setDeviceActionId(deviceId);
    try {
      await adminDeleteDevice(adminToken, deviceId);
      await loadAdminState(adminToken);
      onNotify(`${deviceId} list se hataya gaya`);
    } catch (err) {
      onNotify(err instanceof Error ? err.message : 'Remove fail');
    } finally {
      setDeviceActionId(null);
    }
  };

  const handlePublishAppControl = async (pushToGitHub: boolean, overrideKill?: boolean) => {
    if (!adminToken) return;
    setPublishBusy(true);
    const nextKill = typeof overrideKill === 'boolean' ? overrideKill : globalKillSwitch;
    try {
      const res = await adminUpdateAppControl(adminToken, {
        globalKillSwitch: nextKill,
        killMode,
        globalKillMessage,
        broadcastMessage,
        pushToGitHub,
        updateInfo: {
          versionCode: pubVersionCode,
          versionName: pubVersionName,
          minVersionCode: pubMinVersionCode,
          downloadUrl: pubDownloadUrl,
          notes: pubNotes,
          publishedAt: Date.now(),
        },
      });
      setGlobalKillSwitch(res.appControl.globalKillSwitch);
      setGeneratedTxt(res.updateTxtContent);
      await loadAdminState(adminToken);
      if (pushToGitHub) {
        if (res.githubPushed) {
          onNotify('Server + GitHub update.txt dono par live publish ho gaya!');
        } else {
          onNotify(`Server par save hua, par GitHub push nahi hua: ${res.githubError || 'Token missing'}`);
        }
      } else {
        onNotify('Server par App Control / Update save ho gaya!');
      }
    } catch (err) {
      onNotify(err instanceof Error ? err.message : 'Publish fail');
    } finally {
      setPublishBusy(false);
    }
  };

  const handleCopyUpdateTxt = async () => {
    if (!generatedTxt) return;
    try {
      await navigator.clipboard.writeText(generatedTxt);
      onNotify('update.txt content copy ho gaya — GitHub par paste karein');
    } catch {
      onNotify('Copy failed — manual select karke copy karein');
    }
  };

  const handleCheckUpdate = async () => {
    setCheckingUpdate(true);
    const res = await checkForUpdate(true);
    onUpdateInfoChange(res.info);
    setUpdateStatusMsg(res.status);
    setCheckingUpdate(false);
  };

  const filteredDevices = (adminDash?.devices || []).filter((d) => {
    const q = deviceSearch.trim().toLowerCase();
    if (!q) return true;
    return (
      d.deviceId.toLowerCase().includes(q) ||
      d.deviceModel.toLowerCase().includes(q) ||
      d.manufacturer.toLowerCase().includes(q) ||
      d.appVersionName.toLowerCase().includes(q)
    );
  });

  return (
    <div className="mx-auto max-w-4xl px-4 py-6 sm:px-6">
      <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-bold text-white">
            {license.killed
              ? 'App Disabled by Owner'
              : license.isActive
                ? 'Approval & Updates'
                : 'Approval Zaroori Hai'}
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
          <span>{adminOpen ? 'Owner Panel Band Karein' : 'Owner Control Panel'}</span>
        </button>
      </div>

      {/* Collapsible Owner Fleet, Kill Switch & Update Control Panel */}
      {adminOpen && (
        <div className="mb-6 rounded-2xl border border-[#FFB74D]/35 bg-[#1A1A1A] p-5 sm:p-6">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <KeyRound className="h-5 w-5 text-[#FFB74D]" />
              <h2 className="font-display text-lg font-bold text-white">
                Owner Control Center (Installs · Updates · Kill Switch)
              </h2>
            </div>
            {adminToken && adminDash && (
              <button
                type="button"
                onClick={() => void loadAdminState(adminToken)}
                className="flex items-center gap-1.5 rounded-lg bg-[#2A2A2A] px-3 py-1.5 text-xs font-semibold text-white hover:bg-[#363636]"
              >
                <RefreshCw className="h-3.5 w-3.5" />
                <span>Refresh</span>
              </button>
            )}
          </div>

          {!adminToken || !adminDash ? (
            <form onSubmit={handleAdminAuthSubmit} className="mt-4 space-y-3">
              <p className="text-xs text-[#B0BEC5]">
                {authState?.canClaimFirstRun
                  ? 'First-run setup: Apna Owner Admin Password banayein (kam se kam 6 characters).'
                  : 'Apna Owner Admin Passcode daalkar install count, remote update aur kill switch kholein.'}
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
              {/* Fleet Live Stats */}
              <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
                <div className="rounded-xl border border-white/10 bg-[#141414] p-3">
                  <p className="text-[11px] font-medium text-[#9E9E9E]">Total Installed</p>
                  <p className="font-mono-tabular mt-1 text-2xl font-bold text-white">
                    {adminDash.stats.totalInstalled}
                  </p>
                  <p className="text-[10px] text-[#757575]">
                    {adminDash.stats.active24h} active in 24h
                  </p>
                </div>
                <div className="rounded-xl border border-white/10 bg-[#141414] p-3">
                  <p className="text-[11px] font-medium text-[#9E9E9E]">Abhi Online</p>
                  <p className="font-mono-tabular mt-1 text-2xl font-bold text-[#5AD278]">
                    {adminDash.stats.onlineNow}
                  </p>
                  <p className="text-[10px] text-[#757575]">
                    {adminDash.stats.runningAutoAccept} Auto-Accept ON
                  </p>
                </div>
                <div className="rounded-xl border border-white/10 bg-[#141414] p-3">
                  <p className="text-[11px] font-medium text-[#9E9E9E]">Approved Active</p>
                  <p className="font-mono-tabular mt-1 text-2xl font-bold text-[#FFB74D]">
                    {adminDash.stats.licensedCount}
                  </p>
                  <p className="text-[10px] text-[#757575]">
                    {adminDash.stats.totalOrdersAccepted} orders picked
                  </p>
                </div>
                <div className="rounded-xl border border-white/10 bg-[#141414] p-3">
                  <p className="text-[11px] font-medium text-[#9E9E9E]">Remote Killed</p>
                  <p className="font-mono-tabular mt-1 text-2xl font-bold text-[#EF5350]">
                    {adminDash.appControl.globalKillSwitch
                      ? 'ALL'
                      : adminDash.stats.killedCount}
                  </p>
                  <p className="text-[10px] text-[#757575]">
                    {adminDash.appControl.globalKillSwitch
                      ? `Global (${adminDash.appControl.killMode})`
                      : 'Global switch OFF'}
                  </p>
                </div>
              </div>

              {/* Version Breakdown Chips */}
              {Object.keys(adminDash.stats.versionBreakdown).length > 0 && (
                <div className="flex flex-wrap items-center gap-2 text-xs">
                  <span className="text-[#9E9E9E]">App Versions:</span>
                  {Object.entries(adminDash.stats.versionBreakdown).map(([ver, cnt]) => (
                    <span
                      key={ver}
                      className="font-mono-tabular rounded-lg border border-white/10 bg-[#141414] px-2.5 py-1 text-white"
                    >
                      {ver}: <strong className="text-[#5AD278]">{cnt}</strong>
                    </span>
                  ))}
                </div>
              )}

              {/* Owner Sub-Tabs */}
              <div className="flex flex-wrap gap-2 border-b border-white/10 pb-3">
                <button
                  type="button"
                  onClick={() => setOwnerTab('devices')}
                  className={`flex items-center gap-1.5 rounded-lg px-3.5 py-2 text-xs font-bold transition-colors ${
                    ownerTab === 'devices'
                      ? 'bg-[#FFB74D] text-[#121212]'
                      : 'bg-[#2A2A2A] text-[#B0BEC5] hover:text-white'
                  }`}
                >
                  <Smartphone className="h-3.5 w-3.5" />
                  <span>Installed Devices ({adminDash.devices.length})</span>
                </button>
                <button
                  type="button"
                  onClick={() => setOwnerTab('control')}
                  className={`flex items-center gap-1.5 rounded-lg px-3.5 py-2 text-xs font-bold transition-colors ${
                    ownerTab === 'control'
                      ? 'bg-[#FFB74D] text-[#121212]'
                      : 'bg-[#2A2A2A] text-[#B0BEC5] hover:text-white'
                  }`}
                >
                  <Rocket className="h-3.5 w-3.5" />
                  <span>GitHub Update & Kill Switch</span>
                </button>
                <button
                  type="button"
                  onClick={() => setOwnerTab('approvals')}
                  className={`flex items-center gap-1.5 rounded-lg px-3.5 py-2 text-xs font-bold transition-colors ${
                    ownerTab === 'approvals'
                      ? 'bg-[#FFB74D] text-[#121212]'
                      : 'bg-[#2A2A2A] text-[#B0BEC5] hover:text-white'
                  }`}
                >
                  <CheckCircle2 className="h-3.5 w-3.5" />
                  <span>Manual Code Approval</span>
                </button>
              </div>

              {/* TAB 1: INSTALLED DEVICES FLEET */}
              {ownerTab === 'devices' && (
                <div className="space-y-3">
                  <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                    <input
                      type="text"
                      value={deviceSearch}
                      onChange={(e) => setDeviceSearch(e.target.value)}
                      placeholder="Device Code, Phone Model ya Version search karein..."
                      className="h-10 flex-1 rounded-xl border border-white/10 bg-[#141414] px-3 text-xs text-white focus:border-[#FFB74D] focus:outline-none"
                    />
                  </div>

                  {filteredDevices.length === 0 ? (
                    <div className="rounded-xl border border-white/10 bg-[#141414] p-5 text-center text-xs text-[#9E9E9E]">
                      Abhi koi installed device record nahi hai. Jab bhi naya APK phone par khulega,
                      yahan phone model, version aur live status turant dikhne lagega.
                    </div>
                  ) : (
                    <div className="space-y-2">
                      {filteredDevices.map((dev) => {
                        const busy = deviceActionId === dev.deviceId;
                        return (
                          <div
                            key={dev.deviceId}
                            className={`rounded-xl border p-3.5 text-xs ${
                              dev.remoteKilled
                                ? 'border-[#EF5350]/50 bg-[#C62828]/10'
                                : 'border-white/10 bg-[#141414]'
                            }`}
                          >
                            <div className="flex flex-wrap items-start justify-between gap-2">
                              <div>
                                <div className="flex flex-wrap items-center gap-2">
                                  <span
                                    className={`inline-block h-2 w-2 rounded-full ${
                                      dev.onlineNow ? 'bg-[#5AD278]' : 'bg-[#757575]'
                                    }`}
                                  />
                                  <span className="font-mono-tabular text-sm font-bold text-white">
                                    {dev.deviceId}
                                  </span>
                                  <span className="rounded bg-[#2A2A2A] px-1.5 py-0.5 text-[10px] font-semibold text-[#B0BEC5]">
                                    v{dev.appVersionName}
                                  </span>
                                  {dev.remoteKilled && (
                                    <span className="rounded bg-[#C62828] px-1.5 py-0.5 text-[10px] font-bold text-white">
                                      KILLED / BLOCKED
                                    </span>
                                  )}
                                  {dev.licenseActive ? (
                                    <span className="rounded bg-[#235F37] px-1.5 py-0.5 text-[10px] font-bold text-[#5AD278]">
                                      {dev.licensePlan || 'Active'}
                                    </span>
                                  ) : (
                                    <span className="rounded bg-[#424242] px-1.5 py-0.5 text-[10px] text-[#E0E0E0]">
                                      No License
                                    </span>
                                  )}
                                </div>
                                <p className="mt-1 text-[11px] text-[#9E9E9E]">
                                  {[dev.manufacturer, dev.deviceModel, dev.androidVersion ? `Android ${dev.androidVersion}` : '']
                                    .filter(Boolean)
                                    .join(' · ')}{' '}
                                  · {formatLastSeen(dev.lastSeenAt)}
                                  {dev.ordersAcceptedCount > 0 && ` · ${dev.ordersAcceptedCount} orders`}
                                </p>
                                <p className="mt-0.5 text-[10px] text-[#757575]">
                                  Accessibility: {dev.accessibilityOn ? 'ON' : 'OFF'} · Auto-Accept:{' '}
                                  {dev.isRunning ? 'RUNNING' : 'PAUSED'}
                                </p>
                              </div>

                              <div className="flex flex-wrap items-center gap-1.5">
                                <button
                                  type="button"
                                  disabled={busy}
                                  onClick={() => void handleQuickApproveDevice(dev.deviceId, 7)}
                                  className="rounded-lg bg-[#2E7D32] px-2.5 py-1.5 text-[11px] font-bold text-white hover:bg-[#388E3C] disabled:opacity-50"
                                >
                                  +7d
                                </button>
                                <button
                                  type="button"
                                  disabled={busy}
                                  onClick={() => void handleQuickApproveDevice(dev.deviceId, 30)}
                                  className="rounded-lg bg-[#2E7D32] px-2.5 py-1.5 text-[11px] font-bold text-white hover:bg-[#388E3C] disabled:opacity-50"
                                >
                                  +30d
                                </button>
                                {dev.licenseActive && (
                                  <button
                                    type="button"
                                    disabled={busy}
                                    onClick={() => void handleRevokeDeviceAdmin(dev.deviceId)}
                                    className="rounded-lg bg-[#424242] px-2.5 py-1.5 text-[11px] font-semibold text-white hover:bg-[#555]"
                                  >
                                    Revoke
                                  </button>
                                )}
                                <button
                                  type="button"
                                  disabled={busy}
                                  onClick={() =>
                                    void handleToggleKillDevice(dev.deviceId, !dev.remoteKilled)
                                  }
                                  className={`flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-[11px] font-bold text-white ${
                                    dev.remoteKilled
                                      ? 'bg-[#43A047] hover:bg-[#388E3C]'
                                      : 'bg-[#C62828] hover:bg-[#B71C1C]'
                                  }`}
                                >
                                  <Power className="h-3 w-3" />
                                  <span>{dev.remoteKilled ? 'Unblock' : 'Kill App'}</span>
                                </button>
                                <button
                                  type="button"
                                  disabled={busy}
                                  onClick={() => void handleDeleteDevice(dev.deviceId)}
                                  title="List se hatayein"
                                  className="rounded-lg bg-[#2A2A2A] p-1.5 text-[#9E9E9E] hover:text-[#EF5350]"
                                >
                                  <Trash2 className="h-3.5 w-3.5" />
                                </button>
                              </div>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              )}

              {/* TAB 2: GLOBAL KILL SWITCH & GITHUB UPDATE PUBLISHER */}
              {ownerTab === 'control' && (
                <div className="space-y-5">
                  {/* Global Kill Switch Card */}
                  <div
                    className={`rounded-xl border p-4 ${
                      globalKillSwitch
                        ? 'border-[#EF5350] bg-[#C62828]/15'
                        : 'border-white/10 bg-[#141414]'
                    }`}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <div className="flex items-center gap-2">
                        <AlertOctagon
                          className={`h-5 w-5 ${
                            globalKillSwitch ? 'text-[#EF5350]' : 'text-[#FFB74D]'
                          }`}
                        />
                        <div>
                          <h3 className="text-sm font-bold text-white">
                            Global Remote Kill Switch (Sabhi Phones me App Band/Crash)
                          </h3>
                          <p className="text-[11px] text-[#9E9E9E]">
                            ON karne par sabhi installed phones par Clicker turant band ho jayega
                          </p>
                        </div>
                      </div>
                      <button
                        type="button"
                        disabled={publishBusy}
                        onClick={() => void handlePublishAppControl(false, !globalKillSwitch)}
                        className={`min-h-[38px] rounded-xl px-4 py-1.5 text-xs font-bold text-white ${
                          globalKillSwitch
                            ? 'bg-[#43A047] hover:bg-[#388E3C]'
                            : 'bg-[#C62828] hover:bg-[#B71C1C]'
                        }`}
                      >
                        {globalKillSwitch ? 'App Wapas CHALU Karein' : 'Sabhi App BAND/KILL Karein'}
                      </button>
                    </div>

                    <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
                      <div>
                        <label className="text-[11px] font-semibold text-[#B0BEC5]">
                          Kill Action Mode
                        </label>
                        <select
                          value={killMode}
                          onChange={(e) =>
                            setKillMode(e.target.value === 'crash' ? 'crash' : 'lock')
                          }
                          className="mt-1 h-10 w-full rounded-lg border border-white/15 bg-[#2A2A2A] px-3 text-xs text-white focus:outline-none"
                        >
                          <option value="lock">Owner Lock Screen (Service band + Message)</option>
                          <option value="crash">Instant Force Close / Crash Exit</option>
                        </select>
                      </div>
                      <div>
                        <label className="text-[11px] font-semibold text-[#B0BEC5]">
                          Band Hone ka Message
                        </label>
                        <input
                          type="text"
                          value={globalKillMessage}
                          onChange={(e) => setGlobalKillMessage(e.target.value)}
                          placeholder="App abhi band kiya gaya hai..."
                          className="mt-1 h-10 w-full rounded-lg border border-white/15 bg-[#2A2A2A] px-3 text-xs text-white focus:outline-none"
                        />
                      </div>
                    </div>

                    <div className="mt-3">
                      <label className="flex items-center gap-1.5 text-[11px] font-semibold text-[#B0BEC5]">
                        <Megaphone className="h-3.5 w-3.5 text-[#FFB74D]" />
                        <span>Broadcast Notice (Sabhi Riders ko Top Banner dikhega)</span>
                      </label>
                      <input
                        type="text"
                        value={broadcastMessage}
                        onChange={(e) => setBroadcastMessage(e.target.value)}
                        placeholder="Khali chhodein agar koi notice nahi bhejna hai..."
                        className="mt-1 h-10 w-full rounded-lg border border-white/15 bg-[#2A2A2A] px-3 text-xs text-white focus:outline-none"
                      />
                    </div>
                  </div>

                  {/* GitHub + Server Update Publisher */}
                  <div className="rounded-xl border border-white/10 bg-[#141414] p-4">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <UploadCloud className="h-5 w-5 text-[#5AD278]" />
                        <div>
                          <h3 className="text-sm font-bold text-white">
                            App Update Publisher (Server + GitHub update.txt)
                          </h3>
                          <p className="text-[11px] text-[#9E9E9E]">
                            {adminDash.integrations.githubTokenConfigured
                              ? 'GITHUB_LICENSE_TOKEN connected hai — 1 click me GitHub par push hoga'
                              : 'Server par turant live hoga + GitHub ke liye update.txt copy kar sakte hain'}
                          </p>
                        </div>
                      </div>
                      <Activity className="h-4 w-4 text-[#9E9E9E]" />
                    </div>

                    <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
                      <div>
                        <label className="text-[11px] font-semibold text-[#B0BEC5]">
                          Version Code (Int)
                        </label>
                        <input
                          type="number"
                          min={1}
                          value={pubVersionCode}
                          onChange={(e) => setPubVersionCode(Number(e.target.value) || 1)}
                          className="font-mono-tabular mt-1 h-10 w-full rounded-lg border border-white/15 bg-[#2A2A2A] px-3 text-xs text-white focus:outline-none"
                        />
                      </div>
                      <div>
                        <label className="text-[11px] font-semibold text-[#B0BEC5]">
                          Version Name
                        </label>
                        <input
                          type="text"
                          value={pubVersionName}
                          onChange={(e) => setPubVersionName(e.target.value)}
                          placeholder="1.4"
                          className="font-mono-tabular mt-1 h-10 w-full rounded-lg border border-white/15 bg-[#2A2A2A] px-3 text-xs text-white focus:outline-none"
                        />
                      </div>
                      <div>
                        <label className="text-[11px] font-semibold text-[#B0BEC5]">
                          Min Required Code (Forced Update)
                        </label>
                        <input
                          type="number"
                          min={0}
                          value={pubMinVersionCode}
                          onChange={(e) => setPubMinVersionCode(Number(e.target.value) || 0)}
                          className="font-mono-tabular mt-1 h-10 w-full rounded-lg border border-white/15 bg-[#2A2A2A] px-3 text-xs text-white focus:outline-none"
                        />
                      </div>
                    </div>

                    <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
                      <div>
                        <label className="text-[11px] font-semibold text-[#B0BEC5]">
                          APK Download Link (https://...)
                        </label>
                        <input
                          type="url"
                          value={pubDownloadUrl}
                          onChange={(e) => setPubDownloadUrl(e.target.value)}
                          placeholder="https://github.com/.../releases/latest/download/app-release.apk"
                          className="mt-1 h-10 w-full rounded-lg border border-white/15 bg-[#2A2A2A] px-3 text-xs text-white focus:outline-none"
                        />
                      </div>
                      <div>
                        <label className="text-[11px] font-semibold text-[#B0BEC5]">
                          Release Notes ( | se alag karein)
                        </label>
                        <input
                          type="text"
                          value={pubNotes}
                          onChange={(e) => setPubNotes(e.target.value)}
                          placeholder="0ms instant swipe | Faster OCR | Bug fix"
                          className="mt-1 h-10 w-full rounded-lg border border-white/15 bg-[#2A2A2A] px-3 text-xs text-white focus:outline-none"
                        />
                      </div>
                    </div>

                    <div className="mt-4 flex flex-wrap gap-2">
                      <button
                        type="button"
                        disabled={publishBusy}
                        onClick={() => void handlePublishAppControl(false)}
                        className="min-h-[40px] rounded-xl bg-[#2A2A2A] px-4 py-2 text-xs font-bold text-white hover:bg-[#363636] disabled:opacity-50"
                      >
                        {publishBusy ? 'Saving...' : 'Server par Save Karein'}
                      </button>
                      <button
                        type="button"
                        disabled={publishBusy}
                        onClick={() => void handlePublishAppControl(true)}
                        className="flex min-h-[40px] items-center gap-1.5 rounded-xl bg-[#43A047] px-4 py-2 text-xs font-bold text-white hover:bg-[#388E3C] disabled:opacity-50"
                      >
                        <UploadCloud className="h-4 w-4" />
                        <span>
                          {publishBusy ? 'Publishing...' : 'Server + GitHub update.txt Push Karein'}
                        </span>
                      </button>
                    </div>

                    {generatedTxt && (
                      <div className="mt-4 rounded-xl border border-white/10 bg-[#0F0F0F] p-3">
                        <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                          <span className="text-[11px] font-semibold text-[#9E9E9E]">
                            Generated update.txt (GitHub: by7484040420-cell/bipin-licenses)
                          </span>
                          <div className="flex items-center gap-2">
                            <button
                              type="button"
                              onClick={() => void handleCopyUpdateTxt()}
                              className="flex items-center gap-1 rounded bg-[#2A2A2A] px-2.5 py-1 text-[11px] font-semibold text-white hover:bg-[#363636]"
                            >
                              <Copy className="h-3 w-3" />
                              <span>Copy</span>
                            </button>
                            <a
                              href={GITHUB_UPDATE_EDIT_URL}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="flex items-center gap-1 rounded bg-[#2A2A2A] px-2.5 py-1 text-[11px] font-semibold text-[#81C784] hover:bg-[#363636]"
                            >
                              <span>GitHub Kholein</span>
                              <ExternalLink className="h-3 w-3" />
                            </a>
                          </div>
                        </div>
                        <pre className="font-mono-tabular overflow-x-auto text-[11px] leading-relaxed text-[#B0BEC5]">
                          {generatedTxt}
                        </pre>
                      </div>
                    )}
                  </div>
                </div>
              )}

              {/* TAB 3: MANUAL CODE APPROVAL */}
              {ownerTab === 'approvals' && (
                <div className="space-y-4">
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
                              <span className="font-mono-tabular font-bold text-white">
                                {lic.deviceId}
                              </span>{' '}
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
