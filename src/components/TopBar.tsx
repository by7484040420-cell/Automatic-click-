import React from 'react';
import type { LiveWatcherStatus, ScreenId } from '../types';

interface TopBarProps {
  activeScreen: ScreenId;
  onNavigate: (screen: ScreenId) => void;
  isRunning: boolean;
  watcherStatus: LiveWatcherStatus;
  onToggleRun: () => void;
}

const NAV_ITEMS: { id: ScreenId; label: string }[] = [
  { id: 'home', label: 'Control Panel' },
  { id: 'areas', label: 'Pickup / Drop' },
  { id: 'swipe', label: 'Swipe Zone' },
  { id: 'permissions', label: 'Permissions' },
  { id: 'license', label: 'License' },
];

export const TopBar: React.FC<TopBarProps> = ({
  activeScreen,
  onNavigate,
  isRunning,
  onToggleRun,
}) => {
  return (
    <header className="sticky top-0 z-30 border-b border-white/10 bg-[#121212]/95 backdrop-blur-md">
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3 sm:gap-8 sm:px-6">
        <button
          type="button"
          onClick={() => onNavigate('home')}
          className="font-display text-lg font-extrabold tracking-wider text-white whitespace-nowrap shrink-0 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#43A047]"
        >
          SWIFTGATE
        </button>

        <nav
          aria-label="Primary navigation"
          className="hidden md:flex items-center gap-6 text-sm font-medium"
        >
          {NAV_ITEMS.map((item) => {
            const active = activeScreen === item.id;
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => onNavigate(item.id)}
                className={`relative py-1 whitespace-nowrap shrink-0 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#43A047] ${
                  active ? 'text-white' : 'text-[#9E9E9E] hover:text-white'
                }`}
              >
                {item.label}
                {active && (
                  <span className="absolute right-0 bottom-0 left-0 h-0.5 rounded-full bg-[#43A047]" />
                )}
              </button>
            );
          })}
        </nav>

        <div className="flex items-center shrink-0">
          <button
            type="button"
            onClick={onToggleRun}
            className={`min-h-[40px] rounded-lg px-4 py-2 text-xs font-bold tracking-wide text-white transition-colors whitespace-nowrap shrink-0 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white ${
              isRunning
                ? 'bg-[#43A047] hover:bg-[#388E3C]'
                : 'bg-[#C62828] hover:bg-[#B71C1C]'
            }`}
          >
            {isRunning ? '● ON' : '○ OFF'}
          </button>
        </div>
      </div>

      {/* Mobile horizontal tab bar (compact, <= 15% viewport height) */}
      <nav
        aria-label="Mobile navigation"
        className="flex md:hidden items-center gap-1 overflow-x-auto border-t border-white/5 px-3 py-1.5"
      >
        {NAV_ITEMS.map((item) => {
          const active = activeScreen === item.id;
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => onNavigate(item.id)}
              className={`min-h-[36px] rounded-md px-3 py-1.5 text-xs font-medium whitespace-nowrap shrink-0 transition-colors ${
                active
                  ? 'bg-[#2A2A2A] text-white'
                  : 'text-[#9E9E9E] hover:text-white'
              }`}
            >
              {item.label}
            </button>
          );
        })}
      </nav>
    </header>
  );
};
