import React, { useMemo, useState } from 'react';
import {
  Check,
  ChevronDown,
  ChevronUp,
  Code2,
  Copy,
  Download,
  Play,
  Plus,
  ShieldAlert,
  Smartphone,
  Volume2,
  Wifi,
  WifiOff,
  X,
} from 'lucide-react';
import {
  analyzeScreenText,
  getDropSelections,
  getMaxPickupKm,
  getPickupKeywords,
  getPickupSelections,
  getRejectDropSelections,
  getRejectPickupSelections,
} from '../lib/areaPrefs';
import { playOrderSound } from '../lib/soundPlayer';
import { isSwipeZoneSet } from '../lib/swipeZone';
import {
  COMMON_APK_PRESETS,
  formatVpnTargetLabel,
  generateAndroidVpnKotlinCode,
  generateManifestSnippet,
  generateWatcherHookSnippet,
} from '../lib/vpnNetPulse';
import type {
  LiveWatcherStatus,
  PermissionState,
  ScreenId,
  VpnPulseConfig,
  VpnPulsePhase,
} from '../types';

interface MainDashboardProps {
  permissions: PermissionState;
  isPaused: boolean;
  isRunning: boolean;
  watcherStatus: LiveWatcherStatus;
  bubbleRunning: boolean;
  rootReady: boolean;
  rootStateText: string;
  screenWatcherRunning: boolean;
  debugOverlayEnabled: boolean;
  vpnConfig: VpnPulseConfig;
  vpnPhase: VpnPulsePhase;
  vpnCountdownMs: number;
  onUpdateVpnConfig: (next: VpnPulseConfig) => void;
  onTriggerVpnPulse: (reason: string) => void;
  onToggleStartStop: () => void;
  onNavigate: (screen: ScreenId) => void;
  onToggleBubble: () => void;
  onRunRootSetup: () => void;
  onToggleScreenWatch: () => void;
  onToggleDebugOverlay: () => void;
  onSimulateWatcherAccept: (status: LiveWatcherStatus, debugMessage: string) => void;
}

const SAMPLE_ORDERS: { label: string; text: string }[] = [
  {
    label: 'Delhi Central -> Noida Sec 18',
    text: 'Porter Order | Connaught Place, New Delhi (0.7 km) | Sector 18 Market, Noida (8.4 km) | Rs 385 | Accept in 8s',
  },
  {
    label: 'Rohini Sec 5 -> Gurugram DLF 3',
    text: 'Rohini Sector 5, Delhi (1.1 km) | DLF Phase 3, Gurugram (19.2 km) | Cash | Accept in 6s',
  },
  {
    label: 'Rohini Sec 50 (Word Boundary Test)',
    text: 'Karol Bagh, Delhi (0.5 km) | Rohini Sector 50, Delhi (14.0 km) | Accept in 9s',
  },
  {
    label: 'South Delhi Pincode 110017',
    text: 'Hauz Khas, New Delhi 110017 (0.9 km) | Greater Noida Alpha 1 (24.5 km) | Accept in 7s',
  },
  {
    label: 'Trip Details Guard (Already Accepted)',
    text: 'Trip Details | Cash to Collect Rs 410 | Trip Fare Rs 410 | Call Customer | Porter Support | Connaught Place, Delhi | Noida Sector 18',
  },
];

export const MainDashboard: React.FC<MainDashboardProps> = ({
  permissions,
  isPaused,
  isRunning,
  watcherStatus,
  bubbleRunning,
  rootReady,
  rootStateText,
  screenWatcherRunning,
  debugOverlayEnabled,
  vpnConfig,
  vpnPhase,
  vpnCountdownMs,
  onUpdateVpnConfig,
  onTriggerVpnPulse,
  onToggleStartStop,
  onNavigate,
  onToggleBubble,
  onRunRootSetup,
  onToggleScreenWatch,
  onToggleDebugOverlay,
  onSimulateWatcherAccept,
}) => {
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [sampleScreenText, setSampleScreenText] = useState(SAMPLE_ORDERS[0].text);
  const [scanAnimActive, setScanAnimActive] = useState(false);
  const [customPkgInput, setCustomPkgInput] = useState('');
  const [showKotlinCode, setShowKotlinCode] = useState(false);
  const [kitTab, setKitTab] = useState<'service' | 'manifest' | 'hook'>('service');
  const [copiedKotlin, setCopiedKotlin] = useState(false);

  const toggleApkPackage = (pkg: string) => {
    const clean = pkg.trim();
    if (!clean) return;
    const exists = vpnConfig.targetPackages.includes(clean);
    const nextPackages = exists
      ? vpnConfig.targetPackages.filter((p) => p !== clean)
      : [...vpnConfig.targetPackages, clean];
    onUpdateVpnConfig({ ...vpnConfig, targetPackages: nextPackages });
  };

  const handleAddCustomApk = () => {
    const clean = customPkgInput.trim();
    if (!clean) return;
    if (!vpnConfig.targetPackages.includes(clean)) {
      onUpdateVpnConfig({
        ...vpnConfig,
        targetMode: 'SELECTED_APPS',
        targetPackages: [...vpnConfig.targetPackages, clean],
      });
    }
    setCustomPkgInput('');
  };

  const kotlinCode = useMemo(() => generateAndroidVpnKotlinCode(vpnConfig), [vpnConfig]);
  const manifestCode = useMemo(() => generateManifestSnippet(), []);
  const hookCode = useMemo(() => generateWatcherHookSnippet(vpnConfig), [vpnConfig]);

  const activeSnippet =
    kitTab === 'service' ? kotlinCode : kitTab === 'manifest' ? manifestCode : hookCode;

  const handleCopyKotlin = () => {
    navigator.clipboard?.writeText(activeSnippet).catch(() => {});
    setCopiedKotlin(true);
    window.setTimeout(() => setCopiedKotlin(false), 2000);
  };

  const handleDownloadKotlin = () => {
    const blob = new Blob([kotlinCode], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'NetPulseVpnService.kt';
    a.click();
    URL.revokeObjectURL(url);
  };

  const pickupSelections = getPickupSelections();
  const dropSelections = getDropSelections();
  const rejectPickupSelections = getRejectPickupSelections();
  const rejectDropSelections = getRejectDropSelections();
  const pickupKeywords = getPickupKeywords();
  const maxPickupKm = getMaxPickupKm();

  const pickupACount = Object.keys(pickupSelections).length;
  const pickupRCount = Object.keys(rejectPickupSelections).length;
  const dropACount = Object.keys(dropSelections).length;
  const dropRCount = Object.keys(rejectDropSelections).length;
  const swipeSet = isSwipeZoneSet();

  const liveAnalysis = useMemo(() => analyzeScreenText(sampleScreenText), [sampleScreenText]);

  const handleRunOrderScan = () => {
    if (!isRunning) {
      onSimulateWatcherAccept(
        'OFF',
        'Scan roka gaya: Auto-Accept OFF hai ya Accessibility ON nahi hai'
      );
      return;
    }

    if (liveAnalysis.isTripDetails) {
      onSimulateWatcherAccept(
        'READY',
        'Trip Details screen - yaha tap nahi (order pehle hi uth chuka)'
      );
      return;
    }

    if (liveAnalysis.rejected || !liveAnalysis.matched) {
      const msg = liveAnalysis.rejected
        ? `REJECT (R) match! ${liveAnalysis.reason}`
        : `NOT match: ${liveAnalysis.reason}`;
      onSimulateWatcherAccept('ORDER REJECTED', msg);
      if (vpnConfig.enabled && liveAnalysis.triggerNetDrop) {
        onTriggerVpnPulse(msg);
      }
      window.setTimeout(() => {
        onSimulateWatcherAccept('READY', `Net restored after ${msg}`);
      }, vpnConfig.durationMs + 250);
      return;
    }

    if (!liveAnalysis.hasAcceptWord) {
      onSimulateWatcherAccept(
        'READY',
        'Location match hua, par screen par active Accept control nahi mila'
      );
      return;
    }

    onSimulateWatcherAccept('ORDER FOUND', 'ORDER FOUND -> Turbo Accept swipe');
    setScanAnimActive(true);
    window.setTimeout(() => {
      onSimulateWatcherAccept(
        'ORDER UTHA RAHA',
        `SWIPE AUTO A DONE 18ms | ${liveAnalysis.reason}`
      );
      playOrderSound();
    }, 45);
    window.setTimeout(() => {
      setScanAnimActive(false);
      onSimulateWatcherAccept('READY', `Last order accepted: ${liveAnalysis.reason}`);
    }, 500);
  };

  return (
    <div className="mx-auto max-w-4xl px-4 py-6 sm:px-6">
      {/* Main Title Block (from activity_main.xml) */}
      <div className="mb-5">
        <h1 className="font-display text-3xl font-bold tracking-wider text-white">SWIFTGATE</h1>
        <p className="mt-1 text-sm text-[#9E9E9E]">Location-based Auto-Accept</p>
      </div>

      {/* Conditional Permission Warnings */}
      {(!permissions.accessibility || !permissions.overlay) && (
        <div className="mb-4 space-y-2 rounded-xl border border-[#EF5350]/35 bg-[#1E1E1E] p-4">
          {!permissions.accessibility && (
            <p className="text-sm text-white">❌ Accessibility Service: OFF</p>
          )}
          {!permissions.overlay && (
            <p className="text-sm text-white">❌ Overlay Permission: OFF</p>
          )}
          <button
            type="button"
            onClick={() => onNavigate('permissions')}
            className="mt-2 min-h-[42px] w-full rounded-lg bg-[#2A2A2A] px-4 py-2 text-sm font-semibold text-white hover:bg-[#363636]"
          >
            Setup Pura Karein (kuch permission baaki hai)
          </button>
        </div>
      )}

      {/* Rules Summary Box (A, R & Distance rules) */}
      <div className="rounded-xl border border-white/10 bg-[#1E1E1E] p-4 text-sm leading-relaxed">
        <p className="text-[#B0BEC5]">
          {pickupACount === 0 && pickupKeywords.length === 0 && maxPickupKm <= 0
            ? 'Pickup (A): koi restriction nahi'
            : `Pickup (A): ${[
                pickupACount > 0 ? `${pickupACount} city set` : '',
                pickupKeywords.length > 0 ? `${pickupKeywords.length} khaas naam` : '',
                maxPickupKm > 0 ? `<= ${maxPickupKm} km` : '',
              ]
                .filter(Boolean)
                .join(' · ')}`}
          {pickupRCount > 0 && (
            <span className="ml-2 text-[#EF5350]">· Reject (R): {pickupRCount} city</span>
          )}
        </p>
        <p
          className={`mt-1 ${
            dropACount === 0 ? 'font-medium text-[#FFB74D]' : 'text-[#B0BEC5]'
          }`}
        >
          {dropACount === 0
            ? 'Drop (A): ABHI SET NAHI HAI (kuch bhi accept nahi hoga)'
            : `Drop (A): ${dropACount} city set`}
          {dropRCount > 0 && (
            <span className="ml-2 font-normal text-[#EF5350]">
              · Reject (R): {dropRCount} city
            </span>
          )}
        </p>
      </div>

      {/* Primary 3-Button Row: ON/OFF | LOCATION | SWIPE ZONE */}
      <div className="mt-5 grid grid-cols-3 gap-2.5">
        <button
          type="button"
          onClick={onToggleStartStop}
          className={`flex min-h-[72px] items-center justify-center rounded-xl px-2 text-sm font-bold tracking-wide text-white transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white ${
            isRunning
              ? 'bg-[#43A047] hover:bg-[#388E3C]'
              : 'bg-[#C62828] hover:bg-[#B71C1C]'
          }`}
        >
          {!permissions.accessibility ? 'START' : isPaused ? '○ OFF' : '● ON'}
        </button>

        <button
          type="button"
          onClick={() => onNavigate('areas')}
          className="flex min-h-[72px] items-center justify-center rounded-xl bg-[#2A2A2A] px-2 text-sm font-bold tracking-wide text-white transition-colors hover:bg-[#363636]"
        >
          LOCATION (A / R)
        </button>

        <button
          type="button"
          onClick={() => onNavigate('swipe')}
          className="flex min-h-[72px] items-center justify-center rounded-xl bg-[#2A2A2A] px-2 text-sm font-bold tracking-wide text-white transition-colors hover:bg-[#363636]"
        >
          {swipeSet ? 'SWIPE ZONE ✅' : 'SWIPE ZONE'}
        </button>
      </div>

      {/* 1-Second Auto Net-Off (VPN Pulse) Panel */}
      <div
        className={`mt-5 rounded-2xl border p-4 transition-colors ${
          vpnPhase === 'NET_OFF_VPN'
            ? 'border-[#EF5350] bg-[#3B1515]'
            : vpnPhase === 'RECONNECTING'
              ? 'border-[#FFB74D] bg-[#2D2414]'
              : 'border-white/10 bg-[#1E1E1E]'
        }`}
      >
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            {vpnPhase === 'NET_OFF_VPN' ? (
              <WifiOff className="h-5 w-5 shrink-0 text-[#EF5350]" />
            ) : (
              <Wifi className="h-5 w-5 shrink-0 text-[#5AD278]" />
            )}
            <div>
              <h2 className="font-display text-sm font-bold text-white">
                1s Auto Net-Off (VPN Pulse)
              </h2>
              <p className="text-xs text-[#9E9E9E]">
                Jab order <span className="font-bold text-[#EF5350]">R</span> par ho ya{' '}
                <span className="font-bold text-[#5AD278]">A</span> se match na kare,{' '}
                {(vpnConfig.durationMs / 1000).toFixed(1)}s ke liye net off karke turant auto-on karta hai
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <select
              aria-label="VPN net off duration"
              value={vpnConfig.durationMs}
              onChange={(e) =>
                onUpdateVpnConfig({ ...vpnConfig, durationMs: Number(e.target.value) })
              }
              className="font-mono-tabular h-9 rounded-lg border border-white/10 bg-[#2A2A2A] px-2.5 text-xs text-white focus:outline-none"
            >
              <option value={500}>0.5 sec</option>
              <option value={800}>0.8 sec</option>
              <option value={1000}>1.0 sec</option>
              <option value={1500}>1.5 sec</option>
              <option value={2000}>2.0 sec</option>
            </select>

            <button
              type="button"
              onClick={() => onUpdateVpnConfig({ ...vpnConfig, enabled: !vpnConfig.enabled })}
              className={`min-h-[36px] rounded-lg px-3.5 text-xs font-bold text-white transition-colors ${
                vpnConfig.enabled
                  ? 'bg-[#43A047] hover:bg-[#388E3C]'
                  : 'bg-[#616161] hover:bg-[#757575]'
              }`}
            >
              {vpnConfig.enabled ? 'VPN PULSE: ON' : 'VPN PULSE: OFF'}
            </button>
          </div>
        </div>

        {/* Target Scope: Poora Phone vs Sirf Chune Hue APKs */}
        <div className="mt-3 rounded-xl border border-white/10 bg-[#141414] p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="flex items-center gap-1.5 text-xs font-semibold text-[#B0BEC5]">
              <Smartphone className="h-3.5 w-3.5 text-[#5AD278]" />
              Net kiska band hoga?
            </span>

            <div className="grid grid-cols-2 gap-1.5 rounded-lg bg-[#1E1E1E] p-1">
              <button
                type="button"
                onClick={() => onUpdateVpnConfig({ ...vpnConfig, targetMode: 'ALL_PHONE' })}
                className={`rounded-md px-3 py-1.5 text-xs font-bold transition-colors ${
                  vpnConfig.targetMode === 'ALL_PHONE'
                    ? 'bg-[#C62828] text-white'
                    : 'text-[#9E9E9E] hover:text-white'
                }`}
              >
                Poora Phone
              </button>
              <button
                type="button"
                onClick={() => onUpdateVpnConfig({ ...vpnConfig, targetMode: 'SELECTED_APPS' })}
                className={`rounded-md px-3 py-1.5 text-xs font-bold transition-colors ${
                  vpnConfig.targetMode === 'SELECTED_APPS'
                    ? 'bg-[#43A047] text-white'
                    : 'text-[#9E9E9E] hover:text-white'
                }`}
              >
                Chune Hue APKs ({vpnConfig.targetPackages.length})
              </button>
            </div>
          </div>

          {vpnConfig.targetMode === 'ALL_PHONE' ? (
            <p className="mt-2 text-xs text-[#9E9E9E]">
              Reject order aate hi <span className="font-semibold text-white">pure phone</span> ka net{' '}
              {(vpnConfig.durationMs / 1000).toFixed(1)}s ke liye off hokar turant auto-on hoga.
            </p>
          ) : (
            <div className="mt-2.5 space-y-2.5">
              <p className="text-xs text-[#9E9E9E]">
                Sirf niche chune hue APKs ka net {(vpnConfig.durationMs / 1000).toFixed(1)}s ke liye
                band-chalu hoga (baaki apps chalu rahenge):
              </p>

              {/* Quick APK Presets */}
              <div className="flex flex-wrap gap-1.5">
                {COMMON_APK_PRESETS.map((preset) => {
                  const active = vpnConfig.targetPackages.includes(preset.pkg);
                  return (
                    <button
                      key={preset.pkg}
                      type="button"
                      onClick={() => toggleApkPackage(preset.pkg)}
                      className={`inline-flex items-center gap-1 rounded-md border px-2 py-1 text-xs font-medium transition-colors ${
                        active
                          ? 'border-[#43A047] bg-[#1B3A22] text-[#81C784]'
                          : 'border-white/10 bg-[#1E1E1E] text-[#B0BEC5] hover:border-white/25 hover:text-white'
                      }`}
                    >
                      {active && <Check className="h-3 w-3" />}
                      <span>{preset.label}</span>
                    </button>
                  );
                })}
              </div>

              {/* Add Custom APK Package Name */}
              <div className="flex gap-2">
                <input
                  type="text"
                  value={customPkgInput}
                  onChange={(e) => setCustomPkgInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      handleAddCustomApk();
                    }
                  }}
                  placeholder="Custom APK package daalein (e.g. com.porter.driver)"
                  className="h-9 flex-1 rounded-lg border border-white/10 bg-[#1E1E1E] px-3 text-xs text-white placeholder-[#757575] focus:border-[#43A047] focus:outline-none"
                />
                <button
                  type="button"
                  onClick={handleAddCustomApk}
                  className="inline-flex h-9 items-center gap-1 rounded-lg bg-[#2A2A2A] px-3 text-xs font-bold text-white hover:bg-[#363636]"
                >
                  <Plus className="h-3.5 w-3.5" />
                  <span>Add APK</span>
                </button>
              </div>

              {/* Active Selected Packages */}
              {vpnConfig.targetPackages.length > 0 ? (
                <div className="flex flex-wrap gap-1.5">
                  {vpnConfig.targetPackages.map((pkg) => (
                    <span
                      key={pkg}
                      className="font-mono-tabular inline-flex items-center gap-1.5 rounded-md border border-white/10 bg-[#222222] px-2 py-1 text-[11px] text-white"
                    >
                      {pkg}
                      <button
                        type="button"
                        aria-label={`Remove ${pkg}`}
                        onClick={() => toggleApkPackage(pkg)}
                        className="text-[#9E9E9E] hover:text-[#EF5350]"
                      >
                        <X className="h-3 w-3" />
                      </button>
                    </span>
                  ))}
                </div>
              ) : (
                <p className="text-xs font-medium text-[#FFB74D]">
                  Koi APK select nahi hai — kam se kam 1 APK select karein ya Poora Phone chunein.
                </p>
              )}
            </div>
          )}
        </div>

        <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-white/10 pt-3 text-xs">
          <span className="font-mono-tabular font-semibold text-white">
            {vpnPhase === 'NET_OFF_VPN'
              ? `NET OFF [${formatVpnTargetLabel(vpnConfig)}] — ${(vpnCountdownMs / 1000).toFixed(1)}s me auto ON hoga`
              : vpnPhase === 'RECONNECTING'
                ? 'NET AUTO-ON HO RAHA HAI...'
                : `VPN Status: CONNECTED (${formatVpnTargetLabel(vpnConfig)})`}
          </span>

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setShowKotlinCode((prev) => !prev)}
              className="inline-flex min-h-[34px] items-center gap-1.5 rounded-lg border border-white/10 bg-[#141414] px-3 py-1.5 text-xs font-semibold text-[#B0BEC5] hover:text-white"
            >
              <Code2 className="h-3.5 w-3.5 text-[#5AD278]" />
              <span>{showKotlinCode ? 'APK Code Chhupayein' : 'Android APK VPN Code'}</span>
            </button>

            <button
              type="button"
              onClick={() => onTriggerVpnPulse('Manual 1s Net-Off Test')}
              className="min-h-[34px] rounded-lg bg-[#2A2A2A] px-3 py-1.5 text-xs font-semibold text-white hover:bg-[#363636]"
            >
              Test 1s Net-Off
            </button>
          </div>
        </div>

        {/* Ready-to-Use GitHub Android APK Integration Kit */}
        {showKotlinCode && (
          <div className="mt-3 rounded-xl border border-white/10 bg-[#121212] p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <p className="text-xs font-bold text-white">
                  GitHub Android APK Kit (by7484040420-cell/Automatic-click-)
                </p>
                <p className="text-[11px] text-[#9E9E9E]">
                  Mode: <span className="text-white">{formatVpnTargetLabel(vpnConfig)}</span> ·{' '}
                  {(vpnConfig.durationMs / 1000).toFixed(1)}s auto-restore
                </p>
              </div>
              <div className="flex items-center gap-1.5">
                <button
                  type="button"
                  onClick={handleCopyKotlin}
                  className="inline-flex h-8 items-center gap-1 rounded-lg bg-[#2A2A2A] px-2.5 text-xs font-semibold text-white hover:bg-[#363636]"
                >
                  {copiedKotlin ? (
                    <>
                      <Check className="h-3.5 w-3.5 text-[#5AD278]" />
                      <span>Copied</span>
                    </>
                  ) : (
                    <>
                      <Copy className="h-3.5 w-3.5" />
                      <span>Copy Tab</span>
                    </>
                  )}
                </button>
                <button
                  type="button"
                  onClick={handleDownloadKotlin}
                  className="inline-flex h-8 items-center gap-1 rounded-lg bg-[#43A047] px-2.5 text-xs font-semibold text-white hover:bg-[#388E3C]"
                >
                  <Download className="h-3.5 w-3.5" />
                  <span>Download .kt</span>
                </button>
              </div>
            </div>

            <div className="mt-2.5 flex flex-wrap gap-1.5">
              {(
                [
                  { id: 'service', label: '1. NetPulseVpnService.kt' },
                  { id: 'manifest', label: '2. AndroidManifest.xml' },
                  { id: 'hook', label: '3. OrderWatcher Hook' },
                ] as const
              ).map((tab) => (
                <button
                  key={tab.id}
                  type="button"
                  onClick={() => setKitTab(tab.id)}
                  className={`rounded-md px-2.5 py-1 text-xs font-semibold transition-colors ${
                    kitTab === tab.id
                      ? 'bg-[#43A047] text-white'
                      : 'bg-[#1E1E1E] text-[#9E9E9E] hover:text-white'
                  }`}
                >
                  {tab.label}
                </button>
              ))}
            </div>

            <pre className="font-mono-tabular mt-2.5 max-h-64 overflow-auto rounded-lg bg-black/50 p-3 text-[11px] leading-relaxed text-[#CFD8DC]">
              {activeSnippet}
            </pre>
          </div>
        )}
      </div>

      {/* Live OrderWatcher Matching & Swipe Test Bench */}
      <div className="mt-5 rounded-2xl border border-white/10 bg-[#1E1E1E] p-5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h2 className="font-display text-base font-bold text-white">
              Order Watcher Live Test
            </h2>
            <p className="text-xs text-[#9E9E9E]">
              Screen text parser, A/R matching, 1s VPN drop aur Trip Details guard test karein
            </p>
          </div>
          <span
            className={`font-mono-tabular text-xs font-semibold ${
              watcherStatus === 'ORDER REJECTED'
                ? 'text-[#EF5350]'
                : watcherStatus === 'ORDER FOUND'
                  ? 'text-[#FFB74D]'
                  : 'text-[#5AD278]'
            }`}
          >
            {watcherStatus}
          </span>
        </div>

        {/* Preset Sample Order Buttons */}
        <div className="mt-3 flex flex-wrap gap-1.5">
          {SAMPLE_ORDERS.map((sample) => (
            <button
              key={sample.label}
              type="button"
              onClick={() => setSampleScreenText(sample.text)}
              className={`min-h-[34px] rounded-lg px-2.5 py-1 text-xs font-medium transition-colors ${
                sampleScreenText === sample.text
                  ? 'bg-[#43A047] text-white'
                  : 'bg-[#2A2A2A] text-[#B0BEC5] hover:text-white'
              }`}
            >
              {sample.label}
            </button>
          ))}
        </div>

        <textarea
          rows={3}
          value={sampleScreenText}
          onChange={(e) => setSampleScreenText(e.target.value)}
          placeholder="Order screen text yahan paste karein..."
          className="mt-3 w-full rounded-xl border border-white/10 bg-[#141414] p-3 text-sm text-white placeholder-[#757575] focus:border-[#43A047] focus:outline-none"
        />

        {/* Real-time Split & Match Breakdown */}
        <div className="mt-3 grid grid-cols-1 gap-2 rounded-xl bg-[#141414] p-3 text-xs sm:grid-cols-3">
          <div>
            <span className="text-[#9E9E9E]">Parsed Pickup Segment:</span>
            <p className="font-mono-tabular mt-0.5 truncate text-white">
              {liveAnalysis.pickupText || '—'}
            </p>
          </div>
          <div>
            <span className="text-[#9E9E9E]">Pickup Dist / Rule:</span>
            <p className="font-mono-tabular mt-0.5 truncate text-[#81C784]">
              {liveAnalysis.pickupDistanceKm != null ? `${liveAnalysis.pickupDistanceKm} km` : '—'}
              {maxPickupKm > 0 ? ` (Max <= ${maxPickupKm} km)` : ' (Dist OFF)'}
            </p>
          </div>
          <div>
            <span className="text-[#9E9E9E]">Parsed Drop Segment:</span>
            <p className="font-mono-tabular mt-0.5 truncate text-white">
              {liveAnalysis.dropText || '—'}
            </p>
          </div>
        </div>

        {/* Result & Execute Button */}
        <div className="mt-3 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="text-sm">
            {liveAnalysis.isTripDetails ? (
              <p className="font-medium text-[#FFB74D]">
                Trip Details screen detected — auto-accept & net-off safely blocked
              </p>
            ) : liveAnalysis.matched ? (
              <p className="font-medium text-[#5AD278]">{liveAnalysis.reason}</p>
            ) : (
              <p className="flex items-center gap-1.5 font-medium text-[#EF5350]">
                <ShieldAlert className="h-4 w-4 shrink-0" />
                <span>
                  {liveAnalysis.reason}
                  {vpnConfig.enabled ? ' → 1s Net-Off trigger hoga' : ''}
                </span>
              </p>
            )}
          </div>

          <div className="flex items-center gap-2 shrink-0">
            <button
              type="button"
              onClick={playOrderSound}
              title="Order sound test karein"
              className="flex min-h-[42px] items-center gap-1.5 rounded-xl bg-[#2A2A2A] px-3 py-2 text-xs font-semibold text-white hover:bg-[#363636]"
            >
              <Volume2 className="h-4 w-4" />
              <span>Chime</span>
            </button>

            <button
              type="button"
              onClick={handleRunOrderScan}
              className="flex min-h-[42px] items-center gap-2 rounded-xl bg-[#43A047] px-4 py-2 text-xs font-bold text-white hover:bg-[#388E3C]"
            >
              <Play className="h-3.5 w-3.5" />
              <span>{scanAnimActive ? 'SWIPING...' : 'Scan & Execute'}</span>
            </button>
          </div>
        </div>

        {/* Visual Swipe Animation Bar */}
        {scanAnimActive && (
          <div className="mt-3 overflow-hidden rounded-full bg-[#2A2A2A] p-1">
            <div className="h-2.5 w-full origin-left animate-pulse rounded-full bg-[#00DC5A]" />
          </div>
        )}
      </div>

      {/* Collapsible Advanced Section */}
      <div className="mt-5">
        <button
          type="button"
          onClick={() => setAdvancedOpen((prev) => !prev)}
          className="flex min-h-[44px] w-full items-center justify-center gap-1.5 rounded-xl py-2.5 text-sm font-medium text-[#9E9E9E] transition-colors hover:bg-white/5 hover:text-white"
        >
          <span>Advanced</span>
          {advancedOpen ? (
            <ChevronUp className="h-4 w-4" />
          ) : (
            <ChevronDown className="h-4 w-4" />
          )}
        </button>

        {advancedOpen && (
          <div className="mt-2 space-y-3 rounded-2xl border border-white/10 bg-[#1E1E1E] p-4">
            <button
              type="button"
              onClick={onToggleBubble}
              className="min-h-[44px] w-full rounded-xl bg-[#2A2A2A] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#363636]"
            >
              {bubbleRunning ? 'Floating Bubble Band Karein' : 'Floating Bubble ON Karein'}
            </button>

            <div className="rounded-xl bg-[#141414] p-3 text-sm text-white">
              <p>
                {rootReady ? '✅ ' : '❌ '}Root: {rootStateText}
              </p>
              <p className="mt-1">
                {screenWatcherRunning ? '✅ Screen Watcher: ON' : '❌ Screen Watcher: OFF'}
              </p>
            </div>

            <button
              type="button"
              onClick={onRunRootSetup}
              className="min-h-[44px] w-full rounded-xl bg-[#2A2A2A] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#363636]"
            >
              Root: Auto Permissions
            </button>

            <button
              type="button"
              onClick={onToggleScreenWatch}
              className="min-h-[44px] w-full rounded-xl bg-[#2A2A2A] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#363636]"
            >
              {screenWatcherRunning ? 'Screen Watcher BAND Karein' : 'Screen Watcher ON Karein'}
            </button>

            <button
              type="button"
              onClick={onToggleDebugOverlay}
              className="min-h-[44px] w-full rounded-xl bg-[#2A2A2A] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#363636]"
            >
              {debugOverlayEnabled ? 'Debug Overlay BAND Karein' : 'Debug Overlay ON Karein'}
            </button>

            {/* Companion Webhook / Android Bridge Config for Phone-Wide VPN Off/On */}
            <div className="rounded-xl border border-white/10 bg-[#141414] p-3.5">
              <p className="text-xs font-semibold text-[#FFB74D]">
                Android VPN / MacroDroid / Tasker Bridge (Optional)
              </p>
              <p className="mt-1 text-xs text-[#9E9E9E]">
                Agar aap MacroDroid/Tasker ya Android WebView bridge use kar rahe hain to phone-wide 1s net off/on ke liye local webhook URLs daal sakte hain:
              </p>
              <div className="mt-2.5 grid grid-cols-1 gap-2 sm:grid-cols-2">
                <input
                  type="url"
                  value={vpnConfig.webhookOffUrl}
                  onChange={(e) =>
                    onUpdateVpnConfig({ ...vpnConfig, webhookOffUrl: e.target.value })
                  }
                  placeholder="Net OFF Webhook (e.g. http://127.0.0.1:8765/vpn-off)"
                  className="h-10 rounded-lg border border-white/10 bg-[#222222] px-3 text-xs text-white placeholder-[#757575] focus:border-[#43A047] focus:outline-none"
                />
                <input
                  type="url"
                  value={vpnConfig.webhookOnUrl}
                  onChange={(e) =>
                    onUpdateVpnConfig({ ...vpnConfig, webhookOnUrl: e.target.value })
                  }
                  placeholder="Net ON Webhook (e.g. http://127.0.0.1:8765/vpn-on)"
                  className="h-10 rounded-lg border border-white/10 bg-[#222222] px-3 text-xs text-white placeholder-[#757575] focus:border-[#43A047] focus:outline-none"
                />
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
