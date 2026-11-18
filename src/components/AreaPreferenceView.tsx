import React, { useMemo, useState } from 'react';
import {
  Check,
  ChevronDown,
  ChevronRight,
  FolderPlus,
  Plus,
  RotateCcw,
  Search,
  Trash2,
} from 'lucide-react';
import {
  ALL_MARKER,
  addCustomLocalities,
  addCustomLocality,
  getDropSelections,
  getPickupKeywords,
  getPickupSelections,
  getRejectDropSelections,
  getRejectPickupSelections,
  removeCustomCity,
  setDropSelections,
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

  const [pickupNoRestriction, setPickupNoRestriction] = useState<boolean>(
    () => Object.keys(getPickupSelections()).length === 0 && getPickupKeywords().length === 0
  );
  const [keywordsText, setKeywordsText] = useState<string>(() =>
    getPickupKeywords().join(', ')
  );
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [expandedCities, setExpandedCities] = useState<Record<string, boolean>>({});
  const [inlineInputs, setInlineInputs] = useState<Record<string, string>>({});

  // Modals
  const [showResetConfirm, setShowResetConfirm] = useState(false);
  const [deleteFolderCity, setDeleteFolderCity] = useState<string | null>(null);
  const [showTickedFolderModal, setShowTickedFolderModal] = useState(false);
  const [tickedFolderName, setTickedFolderName] = useState('');
  const [showCreateFolderModal, setShowCreateFolderModal] = useState(false);
  const [newFolderName, setNewFolderName] = useState('');
  const [newFolderPlaces, setNewFolderPlaces] = useState('');

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

  const flattenedTicked = useMemo(() => {
    const out = new Set<string>();
    for (const map of [activeAcceptMap, activeRejectMap]) {
      for (const [city, set] of Object.entries(map)) {
        if (set.has(ALL_MARKER)) {
          for (const loc of allCities[city] ?? []) out.add(loc);
        } else {
          for (const loc of set) out.add(loc);
        }
      }
    }
    return Array.from(out);
  }, [activeAcceptMap, activeRejectMap, allCities]);

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
      if (!isCurrentlyAll && currentTab === 'PICKUP' && pickupNoRestriction) {
        setPickupNoRestriction(false);
      }
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
      if (!hasOwn && currentTab === 'PICKUP' && pickupNoRestriction) {
        setPickupNoRestriction(false);
      }
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
    if (mode === 'A' && currentTab === 'PICKUP' && pickupNoRestriction) {
      setPickupNoRestriction(false);
    }
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
    if (mode === 'A' && currentTab === 'PICKUP' && pickupNoRestriction) {
      setPickupNoRestriction(false);
    }
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

  const handleCreateFromTicked = () => {
    const name = tickedFolderName.trim();
    if (!name) {
      onNotify('Folder ka naam likhein');
      return;
    }
    addCustomLocalities(name, flattenedTicked);
    setAllCities(loadAllCitiesWithCustom());
    setShowTickedFolderModal(false);
    setTickedFolderName('');
    setExpandedCities((prev) => ({ ...prev, [name]: true }));
    onNotify(`"${name}" ban gaya (${flattenedTicked.length} jagah). Use A ya R se select karke SAVE dabao.`);
  };

  const handleCreateCustomFolder = () => {
    const name = newFolderName.trim();
    const places = newFolderPlaces
      .split(/[,\n]+/)
      .map((s) => s.trim())
      .filter(Boolean);
    if (!name) {
      onNotify('Folder ka naam likhein');
      return;
    }
    if (places.length === 0) {
      onNotify('Kam se kam ek jagah ka naam likhein');
      return;
    }
    addCustomLocalities(name, places);
    setAllCities(loadAllCitiesWithCustom());
    setShowCreateFolderModal(false);
    setNewFolderName('');
    setNewFolderPlaces('');
    setExpandedCities((prev) => ({ ...prev, [name]: true }));
    onNotify(`"${name}" ban gaya (${places.length} jagah). Kholkar A ya R select karo aur SAVE dabao.`);
  };

  const handleConfirmResetAll = () => {
    setWorkingPickupA({});
    setWorkingDropA({});
    setWorkingPickupR({});
    setWorkingDropR({});
    setPickupNoRestriction(true);
    setKeywordsText('');
    setPickupSelections({});
    setDropSelections({});
    setRejectPickupSelections({});
    setRejectDropSelections({});
    setPickupKeywords([]);
    setShowResetConfirm(false);
    onNotify('Sab location (A aur R) hat gaye');
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
    if (pickupNoRestriction) {
      setPickupSelections({});
      setPickupKeywords([]);
    } else {
      setPickupSelections(serializeSelectionMap(workingPickupA));
      const words = Array.from(
        new Set(
          keywordsText
            .split(/[,;\n]+/)
            .map((s) => s.trim())
            .filter(Boolean)
        )
      );
      setPickupKeywords(words);
    }
    const dropAOut = serializeSelectionMap(workingDropA);
    setDropSelections(dropAOut);
    setRejectPickupSelections(serializeSelectionMap(workingPickupR));
    setRejectDropSelections(serializeSelectionMap(workingDropR));

    if (Object.keys(dropAOut).length === 0) {
      onSaved('Dhyan dein: Drop (A) set kiye bina koi bhi order auto-accept nahi hoga');
    } else {
      onSaved('Saved (A & R rules updated)');
    }
  };

  const pickupACount = countMapEntries(workingPickupA);
  const pickupRCount = countMapEntries(workingPickupR);
  const dropACount = countMapEntries(workingDropA);
  const dropRCount = countMapEntries(workingDropR);

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
            Pickup: {pickupNoRestriction ? 'All A' : `${pickupACount} A`} / {pickupRCount} R
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

      {/* Pickup restriction & special keywords (only on PICKUP tab) */}
      {currentTab === 'PICKUP' && (
        <div className="mb-4 space-y-3 rounded-xl border border-white/10 bg-[#1E1E1E] p-4">
          <label className="flex min-h-[40px] cursor-pointer items-center gap-3 text-sm font-medium text-white select-none">
            <input
              type="checkbox"
              checked={pickupNoRestriction}
              onChange={(e) => setPickupNoRestriction(e.target.checked)}
              className="h-5 w-5 rounded accent-[#43A047]"
            />
            <span>Pickup khaali rakhein (koi bhi pickup chalega, sirf R wale reject honge)</span>
          </label>

          <div>
            <p className="mb-1.5 text-xs leading-relaxed text-[#FFB74D]">
              Khaas jagah ke naam (comma se alag). Inme se koi EK bhi pickup me mile to order chalega.
            </p>
            <textarea
              rows={2}
              value={keywordsText}
              onChange={(e) => {
                const val = e.target.value;
                setKeywordsText(val);
                if (val.trim() && pickupNoRestriction) {
                  setPickupNoRestriction(false);
                }
              }}
              placeholder="Jaise: Mote Lala, Gurudwara, Lal Qila, Metro"
              className="w-full rounded-lg border border-white/10 bg-[#2A2A2A] p-3 text-sm text-white placeholder-[#BDBDBD] focus:border-[#43A047] focus:outline-none"
            />
          </div>
        </div>
      )}

      {/* Custom Folder Actions */}
      <div className="mb-4 grid grid-cols-1 gap-2 sm:grid-cols-2">
        <button
          type="button"
          onClick={() => {
            if (flattenedTicked.length === 0) {
              onNotify('Pehle niche kisi city ke andar A ya R tick karo, phir ye button dabao');
              return;
            }
            setShowTickedFolderModal(true);
          }}
          className="flex min-h-[44px] items-center justify-center gap-2 rounded-lg border border-white/15 bg-[#1E1E1E] px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-[#2A2A2A]"
        >
          <Check className="h-4 w-4 text-[#5AD278]" />
          <span>Tick kiye hue se Naya Folder Banayein</span>
        </button>

        <button
          type="button"
          onClick={() => setShowCreateFolderModal(true)}
          className="flex min-h-[44px] items-center justify-center gap-2 rounded-lg border border-white/15 bg-[#1E1E1E] px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-[#2A2A2A]"
        >
          <FolderPlus className="h-4 w-4 text-[#FFB74D]" />
          <span>+ Naya Folder (naam type karke)</span>
        </button>
      </div>

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

                          <div className="flex shrink-0 items-center gap-1">
                            <button
                              type="button"
                              disabled={isAllA && !ownA}
                              onClick={() => handleToggleLocalityMode(city, locality, 'A')}
                              aria-label={`${locality} Accept (A)`}
                              aria-pressed={activeA && !activeR}
                              className={`flex h-8 w-8 items-center justify-center rounded border text-xs font-bold transition-colors ${
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
                              className={`flex h-8 w-8 items-center justify-center rounded border text-xs font-bold transition-colors ${
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

      {/* Modal: Create Folder from Ticked Items */}
      {showTickedFolderModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4">
          <div className="w-full max-w-md rounded-2xl border border-white/15 bg-[#1E1E1E] p-6">
            <h2 className="font-display text-lg font-bold text-white">
              Naya Folder - Tick ki hui {flattenedTicked.length} jagah
            </h2>
            <p className="mt-2 max-h-28 overflow-y-auto text-xs text-[#BDBDBD]">
              {flattenedTicked.join(', ')}
            </p>
            <input
              type="text"
              value={tickedFolderName}
              onChange={(e) => setTickedFolderName(e.target.value)}
              placeholder="Folder ka naam (jaise: Yash Delhi)"
              className="mt-4 h-11 w-full rounded-lg border border-white/15 bg-[#2A2A2A] px-3 text-sm text-white placeholder-[#9E9E9E] focus:border-[#43A047] focus:outline-none"
            />
            <div className="mt-5 flex justify-end gap-3">
              <button
                type="button"
                onClick={() => setShowTickedFolderModal(false)}
                className="min-h-[40px] rounded-lg px-4 py-2 text-sm font-medium text-[#B0BEC5] hover:bg-white/5"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleCreateFromTicked}
                className="min-h-[40px] rounded-lg bg-[#43A047] px-4 py-2 text-sm font-bold text-white hover:bg-[#388E3C]"
              >
                Banao
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal: Create Folder by Typing Places */}
      {showCreateFolderModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4">
          <div className="w-full max-w-md rounded-2xl border border-white/15 bg-[#1E1E1E] p-6">
            <h2 className="font-display text-lg font-bold text-white">Naya Folder</h2>
            <input
              type="text"
              value={newFolderName}
              onChange={(e) => setNewFolderName(e.target.value)}
              placeholder="Folder ka naam (jaise: Yash Central Delhi)"
              className="mt-4 h-11 w-full rounded-lg border border-white/15 bg-[#2A2A2A] px-3 text-sm text-white placeholder-[#9E9E9E] focus:border-[#43A047] focus:outline-none"
            />
            <textarea
              rows={5}
              value={newFolderPlaces}
              onChange={(e) => setNewFolderPlaces(e.target.value)}
              placeholder="Jagah ke naam, comma ya nayi line se alag karo&#10;(jaise: Chandni Chowk, Lal Qila, Nehru Place)"
              className="mt-3 w-full rounded-lg border border-white/15 bg-[#2A2A2A] p-3 text-sm text-white placeholder-[#9E9E9E] focus:border-[#43A047] focus:outline-none"
            />
            <div className="mt-5 flex justify-end gap-3">
              <button
                type="button"
                onClick={() => setShowCreateFolderModal(false)}
                className="min-h-[40px] rounded-lg px-4 py-2 text-sm font-medium text-[#B0BEC5] hover:bg-white/5"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleCreateCustomFolder}
                className="min-h-[40px] rounded-lg bg-[#43A047] px-4 py-2 text-sm font-bold text-white hover:bg-[#388E3C]"
              >
                Banao
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
