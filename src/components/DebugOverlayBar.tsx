import React from 'react';
import { X } from 'lucide-react';

interface DebugOverlayBarProps {
  visible: boolean;
  message: string;
  onClose: () => void;
}

export const DebugOverlayBar: React.FC<DebugOverlayBarProps> = ({
  visible,
  message,
  onClose,
}) => {
  if (!visible) return null;

  return (
    <div className="border-b border-[#00FF6A]/25 bg-black/90 px-4 py-2 text-xs text-[#00FF6A]">
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-3">
        <p className="font-mono-tabular truncate">
          {message || 'Service connected. Waiting for live order scan...'}
        </p>
        <button
          type="button"
          onClick={onClose}
          aria-label="Hide debug overlay"
          className="flex h-7 w-7 shrink-0 items-center justify-center rounded text-[#00FF6A]/80 hover:bg-white/10 hover:text-[#00FF6A]"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
};
