import React, { useMemo, useState } from 'react';
import {
  ChevronDown,
  ChevronRight,
  Plus,
  RotateCcw,
  Search,
  Trash2,
} from 'lucide-react';
import {
  ALL_MARKER,
  addCustomLocality,
  getDropSelections,
  getMaxPickupKm,
  getPickupKeywords,
  getPickupSelections,
  getRejectDropSelections,
  getRejectPickupSelections,
  removeCustomCity,
  setAllowPickupWhenKmMissing,
  setDropSelections,
  setMaxPickupKm,
  setPickupKeywords,
  setPickupSelections,
  setRejectDropSelections,
  setRejectPickupSelections,
} from '../lib/areaPrefs';
import { loadAllCitiesWithCustom, loadVerifiedCities } from '../lib/locationRepository';
import type { AreaTab, CityLocalitiesMap, LocationMode, SelectionMap } from '../types';

interface AreaPreferenceViewProps {
  onSaved: (message: string) => void;
  onNotify: (message: string) => void;
  onCancel?: () => void;
}

function cloneSelectionMap(src: SelectionMap): Record<string, Set<string>> {
  const out: Record<string, Set<string>> = {};
  for (const [city, list] of Object.entries(src)) {
    out[city] = new Set(list);
  }
  return out;
}

function serializeSelectionMap(src: Record<string, Set<string>>): SelectionMap {
  const out: SelectionMap = {};
  for (const [city, set] of Object.entries(src)) {
    if (set.size > 0) {
      out[city] = Array.from(set);
    }
  }
  return out;
}

function countMapEntries(map: Record<string, Set<string>>): number {
  return Object.keys(map).length;
}

export const AreaPreferenceView: React.FC<AreaPreferenceViewProps> = ({
  onSaved,
  onNotify,
  onCancel,
}) => {
  const verifiedCityNames = useMemo(() => new Set(Object.keys(loadVerifiedCities())), []);
  const [allCities, setAllCities] = useState<CityLocalitiesMap>(() => loadAllCitiesWithCustom());

  const [currentTab, setCurrentTab] = useState<AreaTab>('PICKUP');

  // Accept (A) maps
  const [workingPickupA, setWorkingPickupA] = useState<Record<string, Set<string>>>(() =>
    cloneSelectionMap(getPickupSelections())
  );
  const [workingDropA, setWorkingDropA] = useState<Record<string, Set<string>>>(() =>
    cloneSelectionMap(getDropSelections())
  );

  // Reject (R) maps
  const [workingPickupR, setWorkingPickupR] = useState<Record<string, Set<string>>>(() =>
    cloneSelectionMap(getRejectPickupSelections())
  );
  const [workingDropR, setWorkingDropR] = useState<Record<string, Set<string>>>(() =>
    cloneSelectionMap(getRejectDropSelections())
  );

  const [keywordsText, setKeywordsText] = useState<string>(() =>
    getPickupKeywords().join(', ')
  );
  const [maxPickupKmText, setMaxPickupKmText] = useState<string>(() => {
    const saved = getMaxPickupKm();
    return saved > 0 ? String(saved) : '';
  });
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [expandedCities, setExpandedCities] = useState<Record<string, boolean>>({});
  const [inlineInputs, setInlineInputs] = useState<Record<string, string>>({});

  // Modals
  const [showResetConfirm, setShowResetConfirm] = useState(false);
  const [deleteFolderCity, setDeleteFolderCity] = useState<string | null>(null);

  const activeAcceptMap = currentTab === 'PICKUP' ? workingPickupA : workingDropA;
  const setActiveAcceptMap = currentTab === 'PICKUP' ? setWorkingPickupA : setWorkingDropA;
  const activeRejectMap = currentTab === 'PICKUP' ? workingPickupR : workingDropR;
  const setActiveRejectMap = currentTab === 'PICKUP' ? setWorkingPickupR : setWorkingDropR;

  const query = searchQuery.trim().toLowerCase();
  const rawQuery = searchQuery.trim();

  const exactMatchExists = useMemo(() => {
    if (!rawQuery) return true;
    return Object.values(allCities).some((list) =>
      list.some((item) => item.toLowerCase() === rawQuery.toLowerCase())
    );
  }, [allCities, rawQuery]);

  const niceSearchName = useMemo(() => {
    return rawQuery
      .split(/\s+/)
      .filter(Boolean)
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
      .join(' ');
  }, [rawQuery]);

  const toggleCityExpanded = (city: string) => {
    setExpandedCities((prev) => ({ ...prev, [city]: !prev[city] }));
  };

  /**
   * Toggle All <City> for A (Accept) or R (Reject).
   * Selecting A clears All R (and vice versa). Clicking an active mode clears it.
   */
  const handleToggleAllCityMode = (city: string, mode: LocationMode) => {
    const targetMap = mode === 'A' ? activeAcceptMap : activeRejectMap;
    const isCurrentlyAll = targetMap[city]?.has(ALL_MARKER) ?? false;

    if (mode === 'A') {
      setActiveAcceptMap((prev) => {
        const next = { ...prev };
        if (isCurrentlyAll) delete next[city];
        else next[city] = new Set([ALL_MARKER]);
        return next;
      });
      if (!isCurrentlyAll) {
        setActiveRejectMap((prev) => {
          if (!(city in prev)) return prev;
          const next = { ...prev };
          if (next[city]?.has(ALL_MARKER)) {
            delete next[city];
          }
          return next;
        });
      }
    } else {
      setActiveRejectMap((prev) => {
        const next = { ...prev };
        if (isCurrentlyAll) delete next[city];
        else next[city] = new Set([ALL_MARKER]);
        return next;
      });
      if (!isCurrentlyAll) {
        setActiveAcceptMap((prev) => {
          if (!prev[city]?.has(ALL_MARKER)) return prev;
          const next = { ...prev };
          delete next[city];
          return next;
        });
      }
    }
  };

  /**
   * Toggle a specific locality/sector for A (Accept) or R (Reject).
   * Clicking A clears R on that locality; clicking R clears A on that locality.
   */
  const handleToggleLocalityMode = (city: string, locality: string, mode: LocationMode) => {
    const targetMap = mode === 'A' ? activeAcceptMap : activeRejectMap;
    const hasOwn = targetMap[city]?.has(locality) ?? false;

    if (mode === 'A') {
      setActiveAcceptMap((prev) => {
        const next = { ...prev };
        const set = new Set(next[city] ?? []);
        if (hasOwn) set.delete(locality);
        else set.add(locality);
        if (set.size === 0) delete next[city];
        else next[city] = set;
        return next;
      });
      if (!hasOwn) {
        setActiveRejectMap((prev) => {
          if (!prev[city]?.has(locality)) return prev;
          const next = { ...prev };
          const set = new Set(next[city]);
          set.delete(locality);
          if (set.size === 0) delete next[city];
          else next[city] = set;
          return next;
        });
      }
    } else {
      setActiveRejectMap((prev) => {
        const next = { ...prev };
        const set = new Set(next[city] ?? []);
        if (hasOwn) set.delete(locality);
        else set.add(locality);
        if (set.size === 0) delete next[city];
        else next[city] = set;
        return next;
      });
      if (!hasOwn) {
        setActiveAcceptMap((prev) => {
          if (!prev[city]?.has(locality)) return prev;
          const next = { ...prev };
          const set = new Set(next[city]);
          set.delete(locality);
          if (set.size === 0) delete next[city];
          else next[city] = set;
          return next;
        });
      }
    }
  };

  const handleAddInlineLocality = (city: string, mode: LocationMode) => {
    const value = (inlineInputs[city] ?? '').trim();
    if (!value) {
      onNotify('Area ka naam likhein');
      return;
    }
    addCustomLocality(city, value);
    setAllCities(loadAllCitiesWithCustom());
    const setter = mode === 'A' ? setActiveAcceptMap : setActiveRejectMap;
    setter((prev) => {
      const next = { ...prev };
      const set = new Set(next[city] ?? []);
      set.add(value);
      next[city] = set;
      return next;
    });
    setInlineInputs((prev) => ({ ...prev, [city]: '' }));
    onNotify(`"${value}" (${city}) [${mode}] add aur select ho gaya`);
  };

  const handleQuickAddSearchToCity = (cityName: string, mode: LocationMode) => {
    if (!niceSearchName) return;
    addCustomLocality(cityName, niceSearchName);
    setAllCities(loadAllCitiesWithCustom());
    const setter = mode === 'A' ? setActiveAcceptMap : setActiveRejectMap;
    setter((prev) => {
      const next = { ...prev };
      const set = new Set(next[cityName] ?? []);
      set.add(niceSearchName);
      next[cityName] = set;
      return next;
    });
    setSearchQuery('');
    onNotify(`${niceSearchName} (${cityName}) [${mode}] jud gaya. SAVE dabao.`);
  };

  const handleConfirmResetAll = () => {
    setWorkingPickupA({});
    setWorkingDropA({});
    setWorkingPickupR({});
    setWorkingDropR({});
    setKeywordsText('');
    setMaxPickupKmText('');
    setPickupSelections({});
    setDropSelections({});
    setRejectPickupSelections({});
    setRejectDropSelections({});
    setPickupKeywords([]);
    setMaxPickupKm(0);
    setAllowPickupWhenKmMissing(true);
    setShowResetConfirm(false);
    onNotify('Sab location (A aur R) aur distance hat gaye');
  };

  const handleConfirmDeleteFolder = () => {
    if (!deleteFolderCity) return;
    removeCustomCity(deleteFolderCity);
    for (const setter of [setWorkingPickupA, setWorkingDropA, setWorkingPickupR, setWorkingDropR]) {
      setter((prev) => {
        if (!(deleteFolderCity in prev)) return prev;
        const next = { ...prev };
        delete next[deleteFolderCity];
        return next;
      });
    }
    setAllCities(loadAllCitiesWithCustom());
    onNotify(`"${deleteFolderCity}" hata diya gaya`);
    setDeleteFolderCity(null);
  };

  const handleSave = () => {
    const words = Array.from(
      new Set(
        keywordsText
          .split(/[,;\n]+/)
          .map((s) => s.trim())
          .filter(Boolean)
      )
    );
    const pickupAOut = serializeSelectionMap(workingPickupA);
    setPickupSelections(pickupAOut);
    setPickupKeywords(words);
    const parsedKm = Number(maxPickupKmText.trim().replace(',', '.'));
    const maxKm = Number.isFinite(parsedKm) && parsedKm > 0 ? parsedKm : 0;
    setMaxPickupKm(maxKm);
    setAllowPickupWhenKmMissing(true);

    const hasPickupLoc = Object.keys(pickupAOut).length > 0 || words.length > 0;
    const hasPickupKm = maxKm > 0;

    const dropAOut = serializeSelectionMap(workingDropA);
    setDropSelections(dropAOut);
    setRejectPickupSelections(serializeSelectionMap(workingPickupR));
    setRejectDropSelections(serializeSelectionMap(workingDropR));

    if (Object.keys(dropAOut).length === 0) {
      onSaved('Dhyan dein: Drop (A) set kiye bina koi bhi order auto-accept nahi hoga');
    } else if (!hasPickupLoc && !hasPickupKm) {
      onSaved('Saved! Pickup: Kahin se bhi aur kitni bhi door se uthega');
    } else {
      onSaved('Saved (A, R & Distance rules updated)');
    }
  };

  const pickupACount = countMapEntries(workingPickupA);
  const pickupRCount = countMapEntries(workingPickupR);
  const dropACount = countMapEntries(workingDropA);
  const dropRCount = countMapEntries(workingDropR);
  const parsedPreviewKm = Number(maxPickupKmText.trim().replace(',', '.'));
  const activeMaxKm = Number.isFinite(parsedPreviewKm) && parsedPreviewKm > 0 ? parsedPreviewKm : 0;
  const hasPickupLocInput = pickupACount > 0 || keywordsText.trim().length > 0;

  return (
    <div className="mx-auto max-w-4xl px-4 py-6 sm:px-6">
      {/* Header */}
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-bold text-white">Pickup / Drop Set Karein</h1>
          <p className="mt-1 text-sm text-[#9E9E9E]">
            Har location ke aage{' '}
            <span className="font-bold text-[#5AD278]">A</span> (Accept) aur{' '}
            <span className="font-bold text-[#EF5350]">R</span> (Reject) boxes hain
          </p>
          <p className="font-mono-tabular mt-1 text-xs text-[#B0BEC5]">
            Pickup:{' '}
            {!hasPickupLocInput && activeMaxKm <= 0
              ? 'Kahin se bhi (Any KM)'
              : `${hasPickupLocInput ? `${pickupACount} A` : 'Any Location'}${activeMaxKm > 0 ? ` · <= ${activeMaxKm} km` : ''}`}{' '}
            / {pickupRCount} R
            <span aria-hidden="true"> · </span>
            Drop: {dropACount} A / {dropRCount} R
          </p>
        </div>
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            className="min-h-[40px] rounded-lg border border-white/15 px-4 py-2 text-xs font-medium text-[#B0BEC5] hover:bg-white/5 hover:text-white"
          >
            Wapas Jayein
          </button>
        )}
      </div>

      {/* PICKUP / DROP Tabs */}
      <div className="mb-4 grid grid-cols-2 gap-2 rounded-xl bg-[#1E1E1E] p-1.5">
        <button
          type="button"
          onClick={() => setCurrentTab('PICKUP')}
          className={`min-h-[44px] rounded-lg text-sm font-bold tracking-wide transition-colors ${
            currentTab === 'PICKUP'
              ? 'bg-[#43A047] text-white'
              : 'text-[#9E9E9E] hover:text-white'
          }`}
        >
          PICKUP
        </button>
        <button
          type="button"
          onClick={() => setCurrentTab('DROP')}
          className={`min-h-[44px] rounded-lg text-sm font-bold tracking-wide transition-colors ${
            currentTab === 'DROP'
              ? 'bg-[#43A047] text-white'
              : 'text-[#9E9E9E] hover:text-white'
          }`}
        >
          DROP
        </button>
      </div>

      {/* Search bar */}
      <div className="relative mb-3">
        <Search className="pointer-events-none absolute top-1/2 left-3.5 h-4 w-4 -translate-y-1/2 text-[#9E9E9E]" />
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="Location / sector search ya type karein"
          className="h-12 w-full rounded-lg border border-white/10 bg-[#2A2A2A] pr-4 pl-10 text-sm text-white placeholder-[#BDBDBD] focus:border-[#43A047] focus:outline-none"
        />
      </div>

      {/* Unified Pickup Filter Box (KM + Khaas Naam + Niche Location) */}
      {currentTab === 'PICKUP' && (
        <div className="mb-4 space-y-3 rounded-xl border border-white/10 bg-[#1E1E1E] p-4">
          <div>
            <p className="text-xs font-semibold text-[#81C784]">
              Pickup Filter (KM + Khaas Naam + Niche Location) — sab ek jagah:
            </p>
            <p className="mt-0.5 text-[11px] text-[#B0BEC5]">
              Location set ho to location se, KM set ho to KM meter se, aur dono khaali hon to Pickup kahin se bhi aur kitni bhi door se uth sakta hai.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <input
              type="number"
              min="0"
              step="0.1"
              value={maxPickupKmText}
              onChange={(e) => setMaxPickupKmText(e.target.value)}
              placeholder="Pickup Max KM (jaise: 2, 3 ya 3.5 — khaali = koi limit nahi)"
              className="font-mono-tabular h-10 min-w-[160px] flex-1 rounded-lg border border-white/10 bg-[#2A2A2A] px-3 text-sm text-white placeholder-[#9E9E9E] focus:border-[#43A047] focus:outline-none"
            />
            <button
              type="button"
              onClick={() => setMaxPickupKmText('')}
              className={`h-10 rounded-lg border px-3 text-xs font-bold transition-colors ${
                activeMaxKm <= 0
                  ? 'border-[#EF5350] bg-[#C62828] text-white'
                  : 'border-white/15 bg-[#2A2A2A] text-[#B0BEC5] hover:text-white'
              }`}
            >
              OFF
            </button>
            {[0.5, 0.6, 1, 2, 3, 5].map((km) => (
              <button
                key={km}
                type="button"
                onClick={() => setMaxPickupKmText(String(km))}
                className={`h-10 rounded-lg border px-3 text-xs font-bold transition-colors ${
                  activeMaxKm === km
                    ? 'border-[#81C784] bg-[#43A047] text-white'
                    : 'border-white/15 bg-[#2A2A2A] text-[#B0BEC5] hover:text-white'
                }`}
              >
                {km}km
              </button>
            ))}
          </div>

          <input
            type="text"
            value={keywordsText}
            onChange={(e) => setKeywordsText(e.target.value)}
            placeholder="Khaas jagah ke naam (jaise: Mote Lala, Gurudwara, Lal Qila, Metro)"
            className="h-10 w-full rounded-lg border border-white/10 bg-[#2A2A2A] px-3 text-sm text-white placeholder-[#BDBDBD] focus:border-[#43A047] focus:outline-none"
          />

          {activeMaxKm > 0 && dropACount === 0 && (
            <p className="text-xs font-semibold text-[#FFB74D]">
              Dhyan dein: DROP tab me kam se kam 1 Drop area (A) select karein, warna koi order nahi uthega.
            </p>
          )}
        </div>
      )}

      {/* Quick-add unmatched query to any city with A or R */}
      {rawQuery && !exactMatchExists && (
        <div className="mb-4 rounded-xl border border-[#FFB74D]/30 bg-[#1E1E1E] p-3.5">
          <p className="mb-2.5 text-sm text-[#FFB74D]">
            &ldquo;{niceSearchName}&rdquo; list me pura nahi mila? Kis city me A (Accept) ya R (Reject) ke saath jodna hai:
          </p>
          <div className="flex flex-wrap gap-2">
            {Object.keys(allCities).map((cityName) => (
              <div
                key={cityName}
                className="flex items-center gap-1 rounded-lg border border-white/10 bg-[#2A2A2A] p-1"
              >
                <span className="px-2 text-xs font-medium text-white">{cityName}</span>
                <button
                  type="button"
                  onClick={() => handleQuickAddSearchToCity(cityName, 'A')}
                  className="min-h-[30px] rounded bg-[#43A047] px-2.5 text-xs font-bold text-white hover:bg-[#388E3C]"
                >
                  +A
                </button>
                <button
                  type="button"
                  onClick={() => handleQuickAddSearchToCity(cityName, 'R')}
                  className="min-h-[30px] rounded bg-[#C62828] px-2.5 text-xs font-bold text-white hover:bg-[#B71C1C]"
                >
                  +R
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* City Accordions */}
      <div className="space-y-2">
        {Object.entries(allCities).map(([city, allLocalities]) => {
          const cityMatchesQuery = city.toLowerCase().includes(query);
          const localities = !query
            ? allLocalities
            : allLocalities.filter(
                (loc) => cityMatchesQuery || loc.toLowerCase().includes(query)
              );

          if (query && localities.length === 0 && !cityMatchesQuery) {
            return null;
          }

          const acceptSet = activeAcceptMap[city] ?? new Set<string>();
          const rejectSet = activeRejectMap[city] ?? new Set<string>();
          const isAllA = acceptSet.has(ALL_MARKER);
          const isAllR = rejectSet.has(ALL_MARKER);
          const isExpanded = Boolean(expandedCities[city]) || Boolean(query);
          const isCustomFolder = !verifiedCityNames.has(city);

          const countA = isAllA ? allLocalities.length : acceptSet.size;
          const countR = isAllR ? allLocalities.length : rejectSet.size;

          return (
            <div
              key={city}
              className="overflow-hidden rounded-xl border border-white/10 bg-[#1E1E1E]"
            >
              <div className="flex flex-wrap items-center justify-between gap-2 px-3 py-2.5 sm:px-4">
                <button
                  type="button"
                  onClick={() => toggleCityExpanded(city)}
                  className="flex min-h-[40px] flex-1 items-center gap-2 text-left text-base font-semibold text-white"
                >
                  {isExpanded ? (
                    <ChevronDown className="h-4 w-4 shrink-0 text-[#9E9E9E]" />
                  ) : (
                    <ChevronRight className="h-4 w-4 shrink-0 text-[#9E9E9E]" />
                  )}
                  <span className="truncate">{city}</span>
                  <span className="font-mono-tabular text-xs font-normal text-[#9E9E9E]">
                    ({localities.length})
                    {countA > 0 ? ` · ${countA} A` : ''}
                    {countR > 0 ? ` · ${countR} R` : ''}
                  </span>
                </button>

                {/* City-level All A / R boxes */}
                <div className="flex items-center gap-1.5">
                  <span className="mr-1 hidden text-[11px] text-[#9E9E9E] sm:inline">All:</span>
                  <button
                    type="button"
                    onClick={() => handleToggleAllCityMode(city, 'A')}
                    title={`All ${city} — Accept (A)`}
                    className={`flex h-9 min-w-[40px] items-center justify-center rounded-lg border px-2.5 text-xs font-bold transition-colors ${
                      isAllA
                        ? 'border-[#5AD278] bg-[#43A047] text-white'
                        : 'border-white/15 bg-[#2A2A2A] text-[#B0BEC5] hover:border-[#43A047] hover:text-white'
                    }`}
                  >
                    A
                  </button>
                  <button
                    type="button"
                    onClick={() => handleToggleAllCityMode(city, 'R')}
                    title={`All ${city} — Reject (R)`}
                    className={`flex h-9 min-w-[40px] items-center justify-center rounded-lg border px-2.5 text-xs font-bold transition-colors ${
                      isAllR
                        ? 'border-[#FF8A80] bg-[#C62828] text-white'
                        : 'border-white/15 bg-[#2A2A2A] text-[#B0BEC5] hover:border-[#C62828] hover:text-white'
                    }`}
                  >
                    R
                  </button>

                  {isCustomFolder && (
                    <button
                      type="button"
                      onClick={() => setDeleteFolderCity(city)}
                      aria-label={`${city} folder hatayein`}
                      className="ml-1 flex h-9 w-9 items-center justify-center rounded-lg border border-[#EF5350]/30 bg-[#EF5350]/10 text-[#EF5350] hover:bg-[#EF5350]/20"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  )}
                </div>
              </div>

              {isExpanded && (
                <div className="border-t border-white/10 bg-[#171717] px-3 py-3 sm:px-4">
                  {/* Inline Add Custom Area with +A / +R */}
                  <div className="mb-3 flex gap-2">
                    <input
                      type="text"
                      value={inlineInputs[city] ?? ''}
                      onChange={(e) =>
                        setInlineInputs((prev) => ({ ...prev, [city]: e.target.value }))
                      }
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          e.preventDefault();
                          handleAddInlineLocality(city, 'A');
                        }
                      }}
                      placeholder="Naya area/sector likhein"
                      className="h-10 flex-1 rounded-lg border border-white/10 bg-[#2A2A2A] px-3 text-sm text-white placeholder-[#9E9E9E] focus:border-[#43A047] focus:outline-none"
                    />
                    <button
                      type="button"
                      onClick={() => handleAddInlineLocality(city, 'A')}
                      className="flex min-h-[40px] items-center gap-1 rounded-lg bg-[#43A047] px-3 text-xs font-bold text-white hover:bg-[#388E3C]"
                    >
                      <Plus className="h-3.5 w-3.5" />
                      <span>A</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => handleAddInlineLocality(city, 'R')}
                      className="flex min-h-[40px] items-center gap-1 rounded-lg bg-[#C62828] px-3 text-xs font-bold text-white hover:bg-[#B71C1C]"
                    >
                      <Plus className="h-3.5 w-3.5" />
                      <span>R</span>
                    </button>
                  </div>

                  {/* Localities grid — every single locality has A and R boxes */}
                  <div className="grid max-h-[380px] grid-cols-1 gap-1.5 overflow-y-auto pr-1 sm:grid-cols-2 lg:grid-cols-3">
                    {localities.map((locality) => {
                      const ownA = acceptSet.has(locality);
                      const ownR = rejectSet.has(locality);
                      const activeA = isAllA || ownA;
                      const activeR = isAllR || ownR;

                      // R takes priority visually if user excluded a specific sector while All A is active
                      const rowSurface = activeR
                        ? 'border-[#EF5350]/60 bg-[#4A1C1C] text-white'
                        : activeA
                          ? 'border-[#5AD278]/60 bg-[#235F37] text-white'
                          : 'border-transparent bg-[#222222] text-[#E0E0E0]';

                      return (
                        <div
                          key={locality}
                          className={`flex min-h-[44px] items-center justify-between gap-2 rounded-lg border px-2.5 py-1.5 text-xs transition-colors ${rowSurface}`}
                        >
                          <span className="truncate font-medium">{locality}</span>

                          <div className="flex shrink-0 items-center gap-1.5">
                            <button
                              type="button"
                              disabled={isAllA && !ownA}
                              onClick={() => handleToggleLocalityMode(city, locality, 'A')}
                              aria-label={`${locality} Accept (A)`}
                              aria-pressed={activeA && !activeR}
                              className={`flex h-10 w-10 touch-manipulation select-none items-center justify-center rounded-md border text-xs font-bold transition-colors ${
                                activeA && !ownR
                                  ? 'border-[#81C784] bg-[#43A047] text-white'
                                  : 'border-white/15 bg-[#161616] text-[#9E9E9E] hover:border-[#43A047] hover:text-white'
                              } ${isAllA && !ownA ? 'opacity-65' : ''}`}
                            >
                              A
                            </button>
                            <button
                              type="button"
                              disabled={isAllR && !ownR}
                              onClick={() => handleToggleLocalityMode(city, locality, 'R')}
                              aria-label={`${locality} Reject (R)`}
                              aria-pressed={activeR}
                              className={`flex h-10 w-10 touch-manipulation select-none items-center justify-center rounded-md border text-xs font-bold transition-colors ${
                                activeR
                                  ? 'border-[#FF8A80] bg-[#C62828] text-white'
                                  : 'border-white/15 bg-[#161616] text-[#9E9E9E] hover:border-[#C62828] hover:text-white'
                              } ${isAllR && !ownR ? 'opacity-65' : ''}`}
                            >
                              R
                            </button>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* Bottom Action Bar */}
      <div className="mt-6 flex flex-col gap-3 sm:flex-row">
        <button
          type="button"
          onClick={() => setShowResetConfirm(true)}
          className="flex min-h-[48px] flex-1 items-center justify-center gap-2 rounded-xl border border-[#C62828]/50 bg-[#C62828]/15 px-4 py-3 text-sm font-bold text-[#EF5350] transition-colors hover:bg-[#C62828]/25"
        >
          <RotateCcw className="h-4 w-4" />
          <span>SAB LOCATION RESET (sab hata do)</span>
        </button>

        <button
          type="button"
          onClick={handleSave}
          className="flex min-h-[48px] flex-1 items-center justify-center rounded-xl bg-[#43A047] px-6 py-3 text-sm font-bold tracking-wide text-white transition-colors hover:bg-[#388E3C]"
        >
          SAVE
        </button>
      </div>

      {/* Modal: Reset All Confirmation */}
      {showResetConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4">
          <div className="w-full max-w-md rounded-2xl border border-white/15 bg-[#1E1E1E] p-6">
            <h2 className="font-display text-lg font-bold text-white">Sab location hatao?</h2>
            <p className="mt-2 text-sm text-[#B0BEC5]">
              Pickup, Drop (A aur R dono) aur khaas naam sab hat jayenge. Naya Drop (A) set karne tak koi order auto-accept nahi hoga.
            </p>
            <div className="mt-6 flex justify-end gap-3">
              <button
                type="button"
                onClick={() => setShowResetConfirm(false)}
                className="min-h-[40px] rounded-lg px-4 py-2 text-sm font-medium text-[#B0BEC5] hover:bg-white/5"
              >
                Nahi
              </button>
              <button
                type="button"
                onClick={handleConfirmResetAll}
                className="min-h-[40px] rounded-lg bg-[#C62828] px-4 py-2 text-sm font-bold text-white hover:bg-[#B71C1C]"
              >
                Haan, sab hatao
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal: Delete Custom Folder Confirmation */}
      {deleteFolderCity && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4">
          <div className="w-full max-w-md rounded-2xl border border-white/15 bg-[#1E1E1E] p-6">
            <h2 className="font-display text-lg font-bold text-white">
              &ldquo;{deleteFolderCity}&rdquo; folder hatayein?
            </h2>
            <p className="mt-2 text-sm text-[#B0BEC5]">
              Is poore folder ki sab jagah hat jayengi. Agar ye select kiya hua hai, to wo bhi hat jayega.
            </p>
            <div className="mt-6 flex justify-end gap-3">
              <button
                type="button"
                onClick={() => setDeleteFolderCity(null)}
                className="min-h-[40px] rounded-lg px-4 py-2 text-sm font-medium text-[#B0BEC5] hover:bg-white/5"
              >
                Nahi
              </button>
              <button
                type="button"
                onClick={handleConfirmDeleteFolder}
                className="min-h-[40px] rounded-lg bg-[#C62828] px-4 py-2 text-sm font-bold text-white hover:bg-[#B71C1C]"
              >
                Haan, hatao
              </button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
};
