import React, { useEffect, useRef, useState } from 'react';
import type { LiveWatcherStatus } from '../types';

interface FloatingBubbleProps {
  visible: boolean;
  isPaused: boolean;
  watcherStatus: LiveWatcherStatus;
  onOpenAreas: () => void;
  onCloseBubble: () => void;
}

export const FloatingBubble: React.FC<FloatingBubbleProps> = ({
  visible,
  isPaused,
  watcherStatus,
  onOpenAreas,
  onCloseBubble,
}) => {
  const [pos, setPos] = useState({ x: 16, y: 120 });
  const dragState = useRef<{
    dragging: boolean;
    moved: boolean;
    startX: number;
    startY: number;
    origX: number;
    origY: number;
    longPressTimer: number | null;
    longPressed: boolean;
  }>({
    dragging: false,
    moved: false,
    startX: 0,
    startY: 0,
    origX: 16,
    origY: 120,
    longPressTimer: null,
    longPressed: false,
  });

  useEffect(() => {
    const onMove = (e: PointerEvent) => {
      const ds = dragState.current;
      if (!ds.dragging) return;
      const dx = e.clientX - ds.startX;
      const dy = e.clientY - ds.startY;
      if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
        ds.moved = true;
        if (ds.longPressTimer !== null) {
          window.clearTimeout(ds.longPressTimer);
          ds.longPressTimer = null;
        }
      }
      const nextX = Math.max(8, Math.min(window.innerWidth - 96, ds.origX + dx));
      const nextY = Math.max(64, Math.min(window.innerHeight - 76, ds.origY + dy));
      setPos({ x: nextX, y: nextY });
    };

    const onUp = () => {
      const ds = dragState.current;
      if (!ds.dragging) return;
      ds.dragging = false;
      if (ds.longPressTimer !== null) {
        window.clearTimeout(ds.longPressTimer);
        ds.longPressTimer = null;
      }
      if (!ds.moved && !ds.longPressed) {
        onOpenAreas();
      }
    };

    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };
  }, [onOpenAreas]);

  if (!visible) return null;

  let label = isPaused ? 'AUTO\nOFF' : 'AUTO\nON';
  let bg = isPaused ? '#616161' : '#43A047';

  if (watcherStatus === 'ORDER FOUND') {
    label = 'ORDER\nDETECTED';
    bg = '#FF9800';
  } else if (watcherStatus === 'ORDER UTHA RAHA') {
    label = 'ORDER\nUTH RAHA';
    bg = '#00C853';
  } else if (watcherStatus === 'ORDER REJECTED') {
    label = 'NET OFF\n1s VPN';
    bg = '#C62828';
  } else if (watcherStatus === 'OFF') {
    label = 'AUTO\nOFF';
    bg = '#616161';
  }

  return (
    <div
      role="button"
      tabIndex={0}
      title="Tap: Pickup/Drop kholein | Long press (600ms): Bubble band karein"
      onPointerDown={(e) => {
        e.preventDefault();
        const ds = dragState.current;
        ds.dragging = true;
        ds.moved = false;
        ds.longPressed = false;
        ds.startX = e.clientX;
        ds.startY = e.clientY;
        ds.origX = pos.x;
        ds.origY = pos.y;
        ds.longPressTimer = window.setTimeout(() => {
          ds.longPressed = true;
          ds.dragging = false;
          onCloseBubble();
        }, 600);
      }}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onOpenAreas();
        }
      }}
      style={{
        left: `${pos.x}px`,
        top: `${pos.y}px`,
        backgroundColor: bg,
      }}
      className="fixed z-50 flex h-[64px] w-[88px] cursor-grab select-none items-center justify-center rounded-xl border border-white/25 text-center text-[11px] font-bold leading-tight whitespace-pre-line text-white opacity-85 shadow-lg active:cursor-grabbing"
    >
      {label}
    </div>
  );
};
