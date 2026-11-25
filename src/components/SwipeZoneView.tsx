import React, { useState } from 'react';
import { playOrderSound } from '../lib/soundPlayer';
import {
  autoCalibrateSwipeZone,
  computeAutoSwipeZone,
  ensureAutoSwipeZoneCalibrated,
} from '../lib/swipeZone';
import type { SwipeZoneConfig } from '../types';

interface SwipeZoneViewProps {
  onNotify: (message: string) => void;
  onClose: () => void;
}

interface PhonePreset {
  id: string;
  label: string;
  w: number;
  h: number;
  desc: string;
}

const PHONE_PRESETS: PhonePreset[] = [
  { id: 'user', label: 'Aapka Phone (20:9)', w: 360, h: 802, desc: '720x1604 · Standard' },
  { id: 'small', label: 'Chhota Phone (16:9)', w: 320, h: 569, desc: '480x854 / 720x1280' },
  { id: 'tall', label: 'Patla / Bada (21:9)', w: 384, h: 880, desc: '1080x2460 · Tall' },
  { id: 'wide', label: 'Choda / Wide Phone', w: 430, h: 820, desc: '1440x2800 · Wide' },
];

export const SwipeZoneView: React.FC<SwipeZoneViewProps> = ({ onNotify, onClose }) => {
  const [selectedPreset, setSelectedPreset] = useState<PhonePreset>(PHONE_PRESETS[0]);
  const [zone, setZone] = useState<SwipeZoneConfig>(() => ensureAutoSwipeZoneCalibrated());
  const [testingOrbX, setTestingOrbX] = useState<number | null>(null);
  const [sliderDemoAccepted, setSliderDemoAccepted] = useState(false);

  const handleSelectPreset = (preset: PhonePreset) => {
    setSelectedPreset(preset);
    const autoZone = autoCalibrateSwipeZone(preset.w, preset.h);
    setZone(autoZone);
    onNotify(`${preset.label} ke hisab se Swipe Zone khud fix ho gaya`);
  };

  const handleAutoFix = () => {
    const autoZone = autoCalibrateSwipeZone(selectedPreset.w, selectedPreset.h);
    setZone(autoZone);
    onNotify('Swipe Zone mobile screen ke hisab se auto-fix & save ho gaya');
  };

  const handleTest = () => {
    setSliderDemoAccepted(false);
    const start = zone.startX;
    const end = zone.endX;
    const duration = 380;
    const startedAt = performance.now();

    const step = (now: number) => {
      const f = Math.min(1, (now - startedAt) / duration);
      setTestingOrbX(start + (end - start) * f);
      if (f < 1) {
        requestAnimationFrame(step);
      } else {
        setTestingOrbX(null);
        setSliderDemoAccepted(true);
        playOrderSound();
        window.setTimeout(() => setSliderDemoAccepted(false), 1400);
      }
    };
    requestAnimationFrame(step);
  };

  const computedForPreset = computeAutoSwipeZone(selectedPreset.w, selectedPreset.h);

  return (
    <div className="mx-auto max-w-4xl px-4 py-6 sm:px-6">
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-bold text-white">
            Auto-Fix Swipe Zone
          </h1>
          <p className="mt-1 text-sm text-[#9E9E9E]">
            Kisi bhi size ke phone (chhota, bada, patla ya mota) par line khud apni sahi jagah fix ho jati hai — upar-neeche set karne ki zaroorat nahi.
          </p>
        </div>
        <span className="font-mono-tabular rounded-lg bg-[#2E7D32]/25 px-3 py-1 text-xs font-semibold text-[#5AD278]">
          AUTO-FIXED ✅
        </span>
      </div>

      {/* Device size preview switcher */}
      <div className="mb-5 flex flex-wrap gap-2">
        {PHONE_PRESETS.map((preset) => (
          <button
            key={preset.id}
            type="button"
            onClick={() => handleSelectPreset(preset)}
            className={`min-h-[40px] rounded-xl px-3.5 py-2 text-xs font-semibold transition-colors ${
              selectedPreset.id === preset.id
                ? 'bg-[#5E35B1] text-white'
                : 'bg-[#1E1E1E] text-[#B0BEC5] hover:bg-[#2A2A2A] hover:text-white'
            }`}
          >
            {preset.label}
          </button>
        ))}
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
        {/* Porter-style Popup Screen Preview */}
        <div className="lg:col-span-7">
          <div
            style={{ aspectRatio: `${selectedPreset.w} / ${selectedPreset.h}` }}
            className="relative mx-auto max-h-[560px] w-full max-w-[330px] select-none overflow-hidden rounded-3xl border border-white/15 bg-[#191C22] shadow-xl"
          >
            {/* Simulated Porter Popup anchored at top */}
            <div className="m-3 mt-5 rounded-3xl bg-white p-3.5 text-[#1E293B] shadow-lg">
              <div className="rounded-2xl border border-slate-200 bg-slate-50 py-2.5 text-center shadow-sm">
                <span className="font-display text-2xl font-extrabold text-slate-900">
                  ₹902
                </span>
              </div>

              <div className="mt-3 space-y-2 px-1 text-left">
                <div>
                  <p className="text-[10px] font-bold tracking-wide text-emerald-600">
                    PICKUP · Mayapuri (4.8 km)
                  </p>
                  <p className="truncate text-[11px] text-slate-500">
                    Mayapuri Industrial Area Phase II, New Delhi
                  </p>
                </div>
                <div>
                  <p className="text-[10px] font-bold tracking-wide text-rose-600">
                    DROP · Prahlad Nagar (80.8 km)
                  </p>
                  <p className="truncate text-[11px] text-slate-500">
                    Garh Rd, Prahlad Nagar, Hapur, UP
                  </p>
                </div>
              </div>

              {/* Blue Accept Pill Slider */}
              <div
                className={`relative mt-3 flex h-11 items-center justify-center rounded-full transition-colors ${
                  sliderDemoAccepted ? 'bg-[#43A047]' : 'bg-[#0066FF]'
                }`}
              >
                <div className="absolute left-1.5 flex h-8 w-8 items-center justify-center rounded-full bg-white text-xs font-bold text-[#0066FF]">
                  »
                </div>
                <span className="text-xs font-bold tracking-wide text-white">
                  {sliderDemoAccepted ? 'Order Accepted!' : 'Accept in 4s'}
                </span>
              </div>
            </div>

            <div className="flex justify-center">
              <span className="rounded-full bg-[#E11D48] px-4 py-1 text-[11px] font-bold text-white">
                Skip X
              </span>
            </div>

            {/* Auto-Fixed Green Swipe Line (Read-only, zero drag) */}
            <div
              style={{
                left: `${zone.startX}%`,
                width: `${zone.endX - zone.startX}%`,
                top: `${zone.y}%`,
              }}
              className="pointer-events-none absolute -translate-y-1/2"
            >
              <div className="h-2.5 w-full rounded-full bg-[#00DC5A]/90 shadow" />
              <div className="absolute -left-2 top-1/2 h-5 w-5 -translate-y-1/2 rounded-full border-2 border-white bg-[#00B446]" />
            </div>

            {/* Animated Swipe Test Orb */}
            {testingOrbX !== null && (
              <div
                style={{
                  left: `${testingOrbX}%`,
                  top: `${zone.y}%`,
                }}
                className="pointer-events-none absolute h-6 w-6 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white bg-[#00DC5A]"
              />
            )}
          </div>
        </div>

        {/* Auto-Calibration Details */}
        <div className="flex flex-col justify-between rounded-2xl border border-white/10 bg-[#1E1E1E] p-5 lg:col-span-5">
          <div>
            <h2 className="font-display text-base font-bold text-white">
              Automatic Phone Calibration
            </h2>
            <p className="mt-1 text-xs text-[#9E9E9E]">
              App khud mobile ki width, height aur aspect ratio dekh kar slider ki jagah set rakhta hai. Order aane par seedha swipe hota hai.
            </p>

            <div className="mt-5 space-y-2.5 border-y border-white/10 py-4 text-sm">
              <div className="flex items-center justify-between">
                <span className="text-[#9E9E9E]">Phone Profile</span>
                <span className="font-mono-tabular font-semibold text-white">
                  {selectedPreset.desc}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-[#9E9E9E]">Start X (Thumb »)</span>
                <span className="font-mono-tabular font-semibold text-white">
                  {computedForPreset.startX.toFixed(1)}%
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-[#9E9E9E]">End X</span>
                <span className="font-mono-tabular font-semibold text-white">
                  {computedForPreset.endX.toFixed(1)}%
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-[#9E9E9E]">Vertical Y (Auto)</span>
                <span className="font-mono-tabular font-semibold text-[#5AD278]">
                  {computedForPreset.y.toFixed(1)}%
                </span>
              </div>
            </div>
          </div>

          <div className="mt-6 grid grid-cols-2 gap-2.5">
            <button
              type="button"
              onClick={handleAutoFix}
              className="min-h-[44px] rounded-xl bg-[#43A047] px-3 py-2.5 text-xs font-bold text-white hover:bg-[#388E3C]"
            >
              AUTO-FIX & SAVE
            </button>
            <button
              type="button"
              onClick={handleTest}
              className="min-h-[44px] rounded-xl bg-[#1976D2] px-3 py-2.5 text-xs font-bold text-white hover:bg-[#1565C0]"
            >
              TEST SWIPE
            </button>
            <button
              type="button"
              onClick={onClose}
              className="col-span-2 min-h-[44px] rounded-xl border border-white/15 bg-transparent px-3 py-2.5 text-xs font-bold text-[#B0BEC5] hover:bg-white/5 hover:text-white"
            >
              Wapas Dashboard
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
