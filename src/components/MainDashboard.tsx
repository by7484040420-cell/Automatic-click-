import React, { useMemo, useState } from 'react';
import {
  ChevronDown,
  ChevronUp,
  Play,
  ShieldAlert,
  Volume2,
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
import type {
  LiveWatcherStatus,
  PermissionState,
  ScreenId,
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
      window.setTimeout(() => {
        onSimulateWatcherAccept('READY', `Ignored: ${msg}`);
      }, 900);
      return;
    }

    if (!liveAnalysis.hasAcceptWord) {
      onSimulateWatcherAccept(
        'READY',
        'Location match hua, par screen par active Accept control nahi mila'
      );
      return;
    }

    onSimulateWatcherAccept('ORDER FOUND', 'ORDER FOUND -> 0ms Trigger + 28ms Turbo Swipe');
    setScanAnimActive(true);
    window.setTimeout(() => {
      onSimulateWatcherAccept(
        'ORDER UTHA RAHA',
        `SWIPE ZONE/AUTO A DONE 28ms | ${liveAnalysis.reason}`
      );
      playOrderSound();
    }, 28);
    window.setTimeout(() => {
      setScanAnimActive(false);
      onSimulateWatcherAccept('READY', `Last order accepted: ${liveAnalysis.reason}`);
    }, 450);
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

      {/* Live OrderWatcher Matching & Swipe Test Bench */}
      <div className="mt-5 rounded-2xl border border-white/10 bg-[#1E1E1E] p-5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h2 className="font-display text-base font-bold text-white">
              Order Watcher Live Test
            </h2>
            <p className="text-xs text-[#9E9E9E]">
              Screen text parser, A/R matching aur Trip Details guard test karein
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
                Trip Details screen detected — auto-accept safely blocked
              </p>
            ) : liveAnalysis.matched ? (
              <p className="font-medium text-[#5AD278]">{liveAnalysis.reason}</p>
            ) : (
              <p className="flex items-center gap-1.5 font-medium text-[#EF5350]">
                <ShieldAlert className="h-4 w-4 shrink-0" />
                <span>{liveAnalysis.reason}</span>
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
          </div>
        )}
      </div>
    </div>
  );
};
