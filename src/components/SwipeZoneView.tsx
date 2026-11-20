import React, { useEffect, useRef, useState } from 'react';
import { playOrderSound } from '../lib/soundPlayer';
import {
  DEFAULT_SWIPE_ZONE,
  clearSwipeZone,
  getSavedSwipeZone,
  saveSwipeZone,
} from '../lib/swipeZone';
import type { SwipeZoneConfig } from '../types';

interface SwipeZoneViewProps {
  onNotify: (message: string) => void;
  onClose: () => void;
}

export const SwipeZoneView: React.FC<SwipeZoneViewProps> = ({ onNotify, onClose }) => {
  const canvasRef = useRef<HTMLDivElement | null>(null);
  const [zone, setZone] = useState<SwipeZoneConfig>(() => getSavedSwipeZone() ?? DEFAULT_SWIPE_ZONE);
  const [isSaved, setIsSaved] = useState<boolean>(() => getSavedSwipeZone() !== null);
  const [testingOrbX, setTestingOrbX] = useState<number | null>(null);
  const [sliderDemoAccepted, setSliderDemoAccepted] = useState(false);

  const dragMode = useRef<'move' | 'resize' | null>(null);
  const dragOrigin = useRef<{
    clientX: number;
    clientY: number;
    startX: number;
    endX: number;
    y: number;
  }>({
    clientX: 0,
    clientY: 0,
    startX: zone.startX,
    endX: zone.endX,
    y: zone.y,
  });

  useEffect(() => {
    const onMove = (e: PointerEvent) => {
      if (!dragMode.current || !canvasRef.current) return;
      const rect = canvasRef.current.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) return;

      const dxPct = ((e.clientX - dragOrigin.current.clientX) / rect.width) * 100;
      const dyPct = ((e.clientY - dragOrigin.current.clientY) / rect.height) * 100;

      if (dragMode.current === 'move') {
        const len = dragOrigin.current.endX - dragOrigin.current.startX;
        const nextSx = Math.max(4, Math.min(96 - len, dragOrigin.current.startX + dxPct));
        const nextEx = nextSx + len;
        const nextY = Math.max(10, Math.min(92, dragOrigin.current.y + dyPct));
        setZone({ startX: nextSx, endX: nextEx, y: nextY });
      } else if (dragMode.current === 'resize') {
        const nextEx = Math.max(dragOrigin.current.startX + 18, Math.min(96, dragOrigin.current.endX + dxPct));
        setZone((prev) => ({ ...prev, endX: nextEx }));
      }
    };

    const onUp = () => {
      dragMode.current = null;
    };

    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };
  }, []);

  const handleAutoSnap = () => {
    // Snap directly over the simulated Porter-style "Accept in 7s" slider track
    const snapped: SwipeZoneConfig = {
      startX: 16.5,
      endX: 86.0,
      y: 78.5,
    };
    setZone(snapped);
    onNotify('Slider mil gaya. Ab TEST, phir SAVE dabao.');
  };

  const handleTest = () => {
    setSliderDemoAccepted(false);
    const start = zone.startX;
    const end = zone.endX;
    const duration = 450;
    const startedAt = performance.now();

    const step = (now: number) => {
      const f = Math.min(1, (now - startedAt) / duration);
      setTestingOrbX(start + (end - start) * f);
      if (f < 1) {
        requestAnimationFrame(step);
      } else {
        setTestingOrbX(null);
        // Check if zone aligns reasonably with the slider track (y ~72-85%)
        if (zone.y >= 68 && zone.y <= 88 && zone.endX - zone.startX >= 35) {
          setSliderDemoAccepted(true);
          playOrderSound();
          window.setTimeout(() => setSliderDemoAccepted(false), 1400);
        }
      }
    };
    requestAnimationFrame(step);
  };

  const handleSave = () => {
    saveSwipeZone(zone);
    setIsSaved(true);
    onNotify('Swipe zone SAVE ho gaya');
  };

  const handleReset = () => {
    clearSwipeZone();
    setZone(DEFAULT_SWIPE_ZONE);
    setIsSaved(false);
    onNotify('Zone hata diya (auto-detect wapas)');
  };

  return (
    <div className="mx-auto max-w-4xl px-4 py-6 sm:px-6">
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-bold text-white">Swipe Zone Editor</h1>
          <p className="mt-1 text-sm text-[#9E9E9E]">
            Hari line ko slider par rakho: hara gol (») poori zone khiskata hai, narangi handle (END) lambai badalta hai.
          </p>
        </div>
        <span className="font-mono-tabular text-xs text-[#B0BEC5]">
          {isSaved ? 'Status: SAVED' : 'Status: AUTO-DETECT'}
        </span>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
        {/* Interactive Phone Screen Canvas */}
        <div className="lg:col-span-7">
          <div
            ref={canvasRef}
            className="relative mx-auto aspect-[9/14] max-h-[540px] w-full max-w-[360px] touch-none select-none overflow-hidden rounded-3xl border border-white/15 bg-[#191C22] p-5 shadow-xl"
          >
            {/* Simulated Delivery Order Card */}
            <div className="flex h-full flex-col justify-between">
              <div className="space-y-3">
                <div className="flex items-center justify-between text-xs text-[#9E9E9E]">
                  <span>LIVE ORDER PREVIEW</span>
                  <span className="font-mono-tabular text-[#FFB74D]">Rs 420 · 6.4 km</span>
                </div>

                <div className="rounded-xl border border-white/10 bg-[#232730] p-3.5">
                  <p className="text-[11px] font-semibold text-[#5AD278]">PICKUP (0.8 km)</p>
                  <p className="mt-0.5 text-sm font-medium text-white">
                    Connaught Place, Central Delhi
                  </p>
                  <div className="my-2.5 border-t border-white/10" />
                  <p className="text-[11px] font-semibold text-[#FFB74D]">DROP (5.6 km)</p>
                  <p className="mt-0.5 text-sm font-medium text-white">
                    Sector 18 Market, Noida
                  </p>
                </div>
              </div>

              {/* Simulated Slider Track at y ~ 78.5% */}
              <div className="mb-8">
                <div
                  className={`relative flex h-14 items-center justify-center rounded-full transition-colors ${
                    sliderDemoAccepted ? 'bg-[#43A047]' : 'bg-[#1976D2]'
                  }`}
                >
                  <div className="absolute left-1.5 flex h-11 w-11 items-center justify-center rounded-full bg-white text-sm font-bold text-[#1976D2]">
                    »
                  </div>
                  <span className="text-sm font-bold tracking-wide text-white">
                    {sliderDemoAccepted ? 'Order Accepted!' : 'Accept in 7s'}
                  </span>
                </div>
              </div>
            </div>

            {/* Overlay: Green Swipe Line */}
            <div
              style={{
                left: `${zone.startX}%`,
                width: `${zone.endX - zone.startX}%`,
                top: `${zone.y}%`,
              }}
              className="pointer-events-none absolute -translate-y-1/2"
            >
              <div className="h-3 w-full rounded-full bg-[#00DC5A]/90" />
            </div>

            {/* Animated Swipe Orb (SwipeIndicator.animateSwipe) */}
            {testingOrbX !== null && (
              <div
                style={{
                  left: `${testingOrbX}%`,
                  top: `${zone.y}%`,
                }}
                className="pointer-events-none absolute h-7 w-7 -translate-x-1/2 -translate-y-1/2 rounded-full border-4 border-white bg-[#00C34B]"
              />
            )}

            {/* Start Handle (Green » - Move All) */}
            <button
              type="button"
              aria-label="Drag to move entire swipe zone"
              onPointerDown={(e) => {
                e.preventDefault();
                try {
                  e.currentTarget.setPointerCapture(e.pointerId);
                } catch {
                  // ignore if unsupported
                }
                dragMode.current = 'move';
                dragOrigin.current = {
                  clientX: e.clientX,
                  clientY: e.clientY,
                  startX: zone.startX,
                  endX: zone.endX,
                  y: zone.y,
                };
              }}
              style={{
                left: `${zone.startX}%`,
                top: `${zone.y}%`,
              }}
              className="absolute z-20 flex h-12 w-12 -translate-x-1/2 -translate-y-1/2 touch-none cursor-grab items-center justify-center rounded-full border-2 border-white bg-[#00B446]/95 text-base font-bold text-white shadow-lg active:cursor-grabbing"
            >
              »
            </button>

            {/* End Handle (Orange END - Resize Length) */}
            <button
              type="button"
              aria-label="Drag to resize swipe length"
              onPointerDown={(e) => {
                e.preventDefault();
                try {
                  e.currentTarget.setPointerCapture(e.pointerId);
                } catch {
                  // ignore if unsupported
                }
                dragMode.current = 'resize';
                dragOrigin.current = {
                  clientX: e.clientX,
                  clientY: e.clientY,
                  startX: zone.startX,
                  endX: zone.endX,
                  y: zone.y,
                };
              }}
              style={{
                left: `${zone.endX}%`,
                top: `${zone.y}%`,
              }}
              className="absolute z-20 flex h-11 w-11 -translate-x-1/2 -translate-y-1/2 touch-none cursor-ew-resize items-center justify-center rounded-full border-2 border-white bg-[#FF8F00]/95 text-[10px] font-bold text-white shadow-lg"
            >
              END
            </button>
          </div>
        </div>

        {/* Controls & Coordinates Panel */}
        <div className="flex flex-col justify-between rounded-2xl border border-white/10 bg-[#1E1E1E] p-5 lg:col-span-5">
          <div>
            <h2 className="font-display text-base font-bold text-white">Zone Calibration</h2>
            <p className="mt-1 text-xs text-[#9E9E9E]">
              SAVE dabane ke baad auto-accept swipe sirf isi line par chalega. RESET karne par auto-detect wapas chalu ho jata hai.
            </p>

            <div className="mt-5 space-y-2.5 border-y border-white/10 py-4 text-sm">
              <div className="flex items-center justify-between">
                <span className="text-[#9E9E9E]">Start X</span>
                <span className="font-mono-tabular font-semibold text-white">
                  {zone.startX.toFixed(1)}%
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-[#9E9E9E]">End X</span>
                <span className="font-mono-tabular font-semibold text-white">
                  {zone.endX.toFixed(1)}%
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-[#9E9E9E]">Vertical Y</span>
                <span className="font-mono-tabular font-semibold text-white">
                  {zone.y.toFixed(1)}%
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-[#9E9E9E]">Swipe Length</span>
                <span className="font-mono-tabular font-semibold text-[#5AD278]">
                  {(zone.endX - zone.startX).toFixed(1)}%
                </span>
              </div>
            </div>
          </div>

          <div className="mt-6 grid grid-cols-2 gap-2.5 sm:grid-cols-3 lg:grid-cols-2">
            <button
              type="button"
              onClick={handleAutoSnap}
              className="min-h-[44px] rounded-xl bg-[#2A2A2A] px-3 py-2.5 text-xs font-bold text-white hover:bg-[#363636]"
            >
              AUTO
            </button>
            <button
              type="button"
              onClick={handleTest}
              className="min-h-[44px] rounded-xl bg-[#1976D2] px-3 py-2.5 text-xs font-bold text-white hover:bg-[#1565C0]"
            >
              TEST
            </button>
            <button
              type="button"
              onClick={handleSave}
              className="min-h-[44px] rounded-xl bg-[#43A047] px-3 py-2.5 text-xs font-bold text-white hover:bg-[#388E3C]"
            >
              SAVE
            </button>
            <button
              type="button"
              onClick={handleReset}
              className="min-h-[44px] rounded-xl bg-[#C62828]/80 px-3 py-2.5 text-xs font-bold text-white hover:bg-[#C62828]"
            >
              RESET
            </button>
            <button
              type="button"
              onClick={onClose}
              className="col-span-2 min-h-[44px] rounded-xl border border-white/15 bg-transparent px-3 py-2.5 text-xs font-bold text-[#B0BEC5] hover:bg-white/5 hover:text-white"
            >
              X (Band Karein)
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
