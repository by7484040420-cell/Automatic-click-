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
  getPermissions,
  grantAllViaRoot,
  savePermissions,
} from './lib/permissionManager';
import { checkForUpdate } from './lib/updateManager';
import type {
  LicenseState,
  LiveWatcherStatus,
  PermissionState,
  ScreenId,
  UpdateInfo,
} from './types';

export function App() {
  const [activeScreen, setActiveScreen] = useState<ScreenId>('home');
  const [permissions, setPermissions] = useState<PermissionState>(() => getPermissions());
  const [isPaused, setIsPaused] = useState<boolean>(() => readPaused());
  const [watcherStatus, setWatcherStatus] = useState<LiveWatcherStatus>(() =>
    readPaused() ? 'OFF' : 'READY'
  );
  const [bubbleRunning, setBubbleRunning] = useState<boolean>(false);
  const [rootReady, setRootReady] = useState<boolean>(false);
  const [rootStateText, setRootStateText] = useState<string>('root try nahi hua');
  const [screenWatcherRunning, setScreenWatcherRunning] = useState<boolean>(false);
  const [debugOverlayEnabled, setDebugOverlayEnabled] = useState<boolean>(false);
  const [debugMessage, setDebugMessage] = useState<string>(
    'Service connected. canPerformGestures=true'
  );
  const [license, setLicense] = useState<LicenseState>(() => getLicenseState());
  const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null);
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  const toastTimer = useRef<number | null>(null);
  const displayStreamRef = useRef<MediaStream | null>(null);

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
    return () => {
      if (displayStreamRef.current) {
        displayStreamRef.current.getTracks().forEach((t) => t.stop());
      }
    };
  }, []);

  const isRunning = permissions.accessibility && !isPaused && license.isActive;

  const stopScreenWatcher = useCallback(() => {
    if (displayStreamRef.current) {
      displayStreamRef.current.getTracks().forEach((t) => t.stop());
      displayStreamRef.current = null;
    }
    setScreenWatcherRunning(false);
  }, []);

  const handleToggleStartStop = () => {
    if (!license.isActive) {
      setActiveScreen('license');
      showToast('Pehle approval check karein');
      return;
    }
    if (!permissions.accessibility) {
      showToast('Pehle Accessibility Service ON karein');
      setActiveScreen('permissions');
      return;
    }
    const nextPaused = !isPaused;
    persistPaused(nextPaused);
    setIsPaused(nextPaused);
    if (nextPaused) {
      stopScreenWatcher();
      setBubbleRunning(false);
      setWatcherStatus('OFF');
      showToast('Auto-Accept STOP kar diya');
    } else {
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
    setBubbleRunning(next);
    showToast(next ? 'Floating Bubble ON' : 'Floating Bubble Band');
  };

  const handleRunRootSetup = () => {
    const nextPerms = grantAllViaRoot(permissions);
    setPermissions(nextPerms);
    setRootReady(true);
    setRootStateText('ROOT OK (Web permissions granted)');
    showToast('Root se permissions de di gayi');
  };

  const handleToggleScreenWatch = async () => {
    if (screenWatcherRunning) {
      stopScreenWatcher();
      showToast('Screen Watcher BAND');
      return;
    }
    if (navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia) {
      try {
        const stream = await navigator.mediaDevices.getDisplayMedia({ video: true });
        displayStreamRef.current = stream;
        stream.getVideoTracks()[0]?.addEventListener('ended', () => {
          setScreenWatcherRunning(false);
          displayStreamRef.current = null;
        });
        setScreenWatcherRunning(true);
        showToast('Screen Watcher ON');
        return;
      } catch {
        // User cancelled or iframe disallows getDisplayMedia -> fall back cleanly
      }
    }
    setScreenWatcherRunning(true);
    showToast('Screen Watcher ON (Preview mode)');
  };

  const handleUpdatePermissions = (next: PermissionState) => {
    savePermissions(next);
    setPermissions(next);
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
    if (!license.isActive && screen !== 'license') {
      showToast('Approval zaroori hai — pehle Device ID approve karwayein');
      navigateScreen('license');
      return;
    }
    navigateScreen(screen);
  };

  const effectiveScreen: ScreenId = !license.isActive ? 'license' : activeScreen;

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
            rootReady={rootReady}
            rootStateText={rootStateText}
            screenWatcherRunning={screenWatcherRunning}
            debugOverlayEnabled={debugOverlayEnabled}
            onToggleStartStop={handleToggleStartStop}
            onNavigate={handleNavigate}
            onToggleBubble={handleToggleBubble}
            onRunRootSetup={handleRunRootSetup}
            onToggleScreenWatch={handleToggleScreenWatch}
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
