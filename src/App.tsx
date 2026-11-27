import { useCallback, useEffect, useRef, useState } from 'react';
import { AreaPreferenceView } from './components/AreaPreferenceView';
import { DebugOverlayBar } from './components/DebugOverlayBar';
import { FloatingBubble } from './components/FloatingBubble';
import { LicenseUpdateView } from './components/LicenseUpdateView';
import { MainDashboard } from './components/MainDashboard';
import { PermissionSetupView } from './components/PermissionSetupView';
import { SwipeZoneView } from './components/SwipeZoneView';
import { TopBar } from './components/TopBar';
import { isPaused as readPaused, setPaused as persistPaused } from './lib/areaPrefs';
import { getLicenseState, refreshLicenseAsync } from './lib/licenseManager';
import {
  getMissingSettings,
  getPermissions,
  isEverythingReady,
  savePermissions,
} from './lib/permissionManager';
import {
  autoCalibrateSwipeZone,
  ensureAutoSwipeZoneCalibrated,
  isSwipeZoneSet,
} from './lib/swipeZone';
import { checkForUpdate, fetchAppControlStatus } from './lib/updateManager';
import type {
  AppControlStatus,
  LicenseState,
  LiveWatcherStatus,
  PermissionState,
  ScreenId,
  UpdateInfo,
} from './types';

export function App() {
  const [activeScreen, setActiveScreen] = useState<ScreenId>('home');
  const [permissions, setPermissions] = useState<PermissionState>(() => getPermissions());
  const allSettingsReady = isEverythingReady(permissions);
  const [isPaused, setIsPaused] = useState<boolean>(() => {
    const ready = isEverythingReady(getPermissions());
    if (!ready) {
      persistPaused(true);
      return true;
    }
    return readPaused();
  });
  const [watcherStatus, setWatcherStatus] = useState<LiveWatcherStatus>(() =>
    !isEverythingReady(getPermissions()) || readPaused() ? 'OFF' : 'READY'
  );
  const [bubbleRunning, setBubbleRunning] = useState<boolean>(() =>
    isEverythingReady(getPermissions()) && getLicenseState().isActive
  );
  const bubbleManuallyOffRef = useRef<boolean>(false);
  const [debugOverlayEnabled, setDebugOverlayEnabled] = useState<boolean>(false);
  const [debugMessage, setDebugMessage] = useState<string>(
    'Service connected. canPerformGestures=true'
  );
  const [license, setLicense] = useState<LicenseState>(() => getLicenseState());
  const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null);
  const [appControl, setAppControl] = useState<AppControlStatus | null>(null);
  const [swipeZoneReady, setSwipeZoneReady] = useState<boolean>(() => {
    ensureAutoSwipeZoneCalibrated();
    return isSwipeZoneSet();
  });
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  const toastTimer = useRef<number | null>(null);

  const showToast = useCallback((msg: string) => {
    setToastMessage(msg);
    if (toastTimer.current !== null) {
      window.clearTimeout(toastTimer.current);
    }
    toastTimer.current = window.setTimeout(() => {
      setToastMessage(null);
    }, 3200);
  }, []);

  useEffect(() => {
    refreshLicenseAsync().then((next) => setLicense(next));
    checkForUpdate(false).then((res) => {
      if (res.info) setUpdateInfo(res.info);
    });
  }, []);

  useEffect(() => {
    fetchAppControlStatus(false).then((res) => {
      if (res?.control) {
        setAppControl(res.control);
        if (res.control.update) setUpdateInfo(res.control.update);
      }
    });
  }, [license]);

  const isRemoteKilled = Boolean(license.killed || appControl?.killed);
  const effectiveKillMode = license.killed
    ? license.killMode || 'lock'
    : appControl?.killMode || 'lock';
  const effectiveKillMessage =
    (license.killed ? license.killMessage : '') ||
    appControl?.killMessage ||
    'App temporarily disabled by Owner.';
  const broadcastMessage =
    license.broadcastMessage || appControl?.broadcastMessage || '';
  const effectiveLicenseActive = license.isActive && !isRemoteKilled;

  // Keep app OFF if any setting is missing or remote kill switch is active, and auto-enable Floating Bubble once all settings are ON
  useEffect(() => {
    if (!allSettingsReady || !effectiveLicenseActive) {
      if (!isPaused) {
        persistPaused(true);
        setIsPaused(true);
      }
      setWatcherStatus('OFF');
      setBubbleRunning(false);
      return;
    }
    if (!bubbleManuallyOffRef.current) {
      setBubbleRunning(true);
    }
  }, [allSettingsReady, effectiveLicenseActive, isPaused]);

  const isRunning = allSettingsReady && !isPaused && effectiveLicenseActive;

  const handleToggleStartStop = () => {
    if (!effectiveLicenseActive) {
      setActiveScreen('license');
      showToast(isRemoteKilled ? 'Owner Kill Switch ON hai' : 'Pehle approval check karein');
      return;
    }
    if (!allSettingsReady) {
      persistPaused(true);
      setIsPaused(true);
      setWatcherStatus('OFF');
      const missing = getMissingSettings(permissions).join(', ');
      showToast(`Pehle sabhi settings ON karein (${missing}), tabhi app ON hoga`);
      setActiveScreen('permissions');
      return;
    }
    const nextPaused = !isPaused;
    persistPaused(nextPaused);
    setIsPaused(nextPaused);
    if (nextPaused) {
      setBubbleRunning(false);
      setWatcherStatus('OFF');
      showToast('Auto-Accept STOP kar diya');
    } else {
      bubbleManuallyOffRef.current = false;
      setBubbleRunning(true);
      setWatcherStatus('READY');
      showToast('Auto-Accept START kar diya');
    }
  };

  const handleToggleBubble = () => {
    if (!permissions.overlay) {
      showToast("Pehle 'Display over other apps' permission allow karein");
      setActiveScreen('permissions');
      return;
    }
    const next = !bubbleRunning;
    bubbleManuallyOffRef.current = !next;
    setBubbleRunning(next);
    showToast(next ? 'Floating Bubble ON' : 'Floating Bubble Band');
  };

  const handleAutoFixSwipeZone = () => {
    if (!allSettingsReady) {
      const missing = getMissingSettings(permissions).join(', ');
      showToast(`Pehle sabhi settings ON karein (${missing})`);
      navigateScreen('permissions');
      return;
    }
    autoCalibrateSwipeZone();
    setSwipeZoneReady(true);
    showToast('Swipe Zone is phone ke hisab se khud fix ho gaya! Ab upar-neeche set nahi karna padega.');
  };

  const handleUpdatePermissions = (next: PermissionState) => {
    savePermissions(next);
    setPermissions(next);
    if (!isEverythingReady(next)) {
      persistPaused(true);
      setIsPaused(true);
      setWatcherStatus('OFF');
      setBubbleRunning(false);
    } else if (!bubbleManuallyOffRef.current && effectiveLicenseActive) {
      setBubbleRunning(true);
    }
  };

  const navigateScreen = useCallback((screen: ScreenId) => {
    setActiveScreen((prev) => {
      if (prev !== screen && typeof window !== 'undefined' && window.history?.pushState) {
        try {
          window.history.pushState({ screen }, '', undefined);
        } catch {
          // ignore if restricted in iframe
        }
      }
      return screen;
    });
  }, []);

  useEffect(() => {
    const onPopState = (e: PopStateEvent) => {
      const next = (e.state as { screen?: ScreenId } | null)?.screen;
      if (next) {
        setActiveScreen(next);
      } else {
        setActiveScreen('home');
      }
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  const handleNavigate = (screen: ScreenId) => {
    if (!effectiveLicenseActive && screen !== 'license') {
      showToast(
        isRemoteKilled
          ? 'Owner ne App Kill Switch ON kiya hua hai'
          : 'Approval zaroori hai — pehle Device ID approve karwayein'
      );
      navigateScreen('license');
      return;
    }
    if (!allSettingsReady && (screen === 'areas' || screen === 'swipe')) {
      const missing = getMissingSettings(permissions).join(', ');
      showToast(`Pehle sabhi settings ON karein (${missing}), tabhi Location set hogi`);
      navigateScreen('permissions');
      return;
    }
    navigateScreen(screen);
  };

  const effectiveScreen: ScreenId = !effectiveLicenseActive
    ? 'license'
    : !allSettingsReady && (activeScreen === 'areas' || activeScreen === 'swipe')
      ? 'permissions'
      : activeScreen;

  return (
    <div className="min-h-screen bg-[#121212] text-white">
      <DebugOverlayBar
        visible={debugOverlayEnabled}
        message={debugMessage}
        onClose={() => setDebugOverlayEnabled(false)}
      />

      <TopBar
        activeScreen={effectiveScreen}
        onNavigate={handleNavigate}
        isRunning={isRunning}
        watcherStatus={watcherStatus}
        onToggleRun={handleToggleStartStop}
      />

      {/* Remote Owner Kill Switch Alert */}
      {isRemoteKilled && (
        <div className="border-b border-red-500/40 bg-red-950/70 px-4 py-3 text-center text-xs text-red-100">
          <div className="font-bold uppercase tracking-wider text-red-300">
            Owner Kill Switch Active ({effectiveKillMode === 'crash' ? 'Crash / Force Stop Mode' : 'Lock Mode'})
          </div>
          <div className="mt-0.5">{effectiveKillMessage}</div>
        </div>
      )}

      {/* Owner Broadcast Banner */}
      {broadcastMessage && (
        <div className="border-b border-amber-500/30 bg-amber-500/15 px-4 py-2 text-center text-xs font-medium text-amber-200">
          Notice: {broadcastMessage}
        </div>
      )}

      {/* Forced Update Banner */}
      {updateInfo?.forced && effectiveScreen !== 'license' && (
        <div className="border-b border-[#C62828]/40 bg-[#C62828]/20 px-4 py-2.5 text-center text-xs font-semibold text-white">
          Zaroori Update v{updateInfo.name} available hai —{' '}
          <button
            type="button"
            onClick={() => setActiveScreen('license')}
            className="underline hover:text-[#FFB74D]"
          >
            Update Karein
          </button>
        </div>
      )}

      <main className="pb-16">
        {effectiveScreen === 'home' && (
          <MainDashboard
            permissions={permissions}
            isPaused={isPaused}
            isRunning={isRunning}
            watcherStatus={watcherStatus}
            bubbleRunning={bubbleRunning}
            swipeZoneReady={swipeZoneReady}
            debugOverlayEnabled={debugOverlayEnabled}
            onToggleStartStop={handleToggleStartStop}
            onNavigate={handleNavigate}
            onAutoFixSwipeZone={handleAutoFixSwipeZone}
            onToggleBubble={handleToggleBubble}
            onToggleDebugOverlay={() => setDebugOverlayEnabled((prev) => !prev)}
            onSimulateWatcherAccept={(status, dbg) => {
              setWatcherStatus(status);
              setDebugMessage(dbg);
            }}
          />
        )}

        {effectiveScreen === 'areas' && (
          <AreaPreferenceView
            onSaved={(msg) => {
              showToast(msg);
              setActiveScreen('home');
            }}
            onNotify={showToast}
            onCancel={() => setActiveScreen('home')}
          />
        )}

        {effectiveScreen === 'swipe' && (
          <SwipeZoneView
            onNotify={showToast}
            onClose={() => setActiveScreen('home')}
          />
        )}

        {effectiveScreen === 'permissions' && (
          <PermissionSetupView
            permissions={permissions}
            onUpdatePermissions={handleUpdatePermissions}
            onNotify={showToast}
            onDone={() => setActiveScreen('home')}
          />
        )}

        {effectiveScreen === 'license' && (
          <LicenseUpdateView
            license={license}
            onLicenseChange={setLicense}
            updateInfo={updateInfo}
            onUpdateInfoChange={setUpdateInfo}
            onNotify={showToast}
            onEnterApp={() => setActiveScreen('home')}
          />
        )}
      </main>

      <FloatingBubble
        visible={bubbleRunning}
        isPaused={isPaused || !isRunning}
        watcherStatus={watcherStatus}
        onOpenAreas={() => handleNavigate('areas')}
        onCloseBubble={() => {
          bubbleManuallyOffRef.current = true;
          setBubbleRunning(false);
          showToast('SwiftGate bubble band kiya');
        }}
      />

      {toastMessage && (
        <div
          role="status"
          aria-live="polite"
          className="fixed bottom-5 left-1/2 z-50 max-w-md -translate-x-1/2 rounded-xl border border-white/15 bg-[#2A2A2A] px-4 py-2.5 text-center text-sm font-medium text-white shadow-xl"
        >
          {toastMessage}
        </div>
      )}
    </div>
  );
}

export default App;
