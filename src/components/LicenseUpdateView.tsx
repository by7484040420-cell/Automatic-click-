import React, { useCallback, useEffect, useState } from 'react';
import jsQR from 'jsqr';
import {
  CheckCircle2,
  Copy,
  ExternalLink,
  KeyRound,
  Lock,
  QrCode,
  RefreshCw,
  Settings2,
  Share2,
  ShieldCheck,
  ShieldOff,
  Smartphone,
  Sparkles,
  Trash2,
  Upload,
} from 'lucide-react';
import {
  adminConfirmOrder,
  adminRevokeDevice,
  applyLicenseExpiry,
  claimAdminPassword,
  createPaymentOrder,
  fetchAdminDashboard,
  fetchPublicPlans,
  getAdminAuthState,
  loginAdmin,
  pollPaymentOrderStatus,
  refreshLicenseAsync,
  rotateMerchantWebhookSecret,
  setCustomDeviceId,
  setPreviewUnlocked,
  updateAdminBusinessConfig,
} from '../lib/licenseManager';
import { playOrderSound } from '../lib/soundPlayer';
import {
  CURRENT_VERSION_NAME,
  checkForUpdate,
  snoozeUpdate,
} from '../lib/updateManager';
import type {
  AdminDashboardData,
  LicenseState,
  PaymentOrderData,
  PublicPlansInfo,
  SubscriptionPlan,
  UpdateInfo,
} from '../types';

interface LicenseUpdateViewProps {
  license: LicenseState;
  onLicenseChange: (next: LicenseState) => void;
  updateInfo: UpdateInfo | null;
  onUpdateInfoChange: (info: UpdateInfo | null) => void;
  onNotify: (message: string) => void;
  onEnterApp: () => void;
}

function isSafeUpiUri(uri: string): boolean {
  return uri.trim().toLowerCase().startsWith('upi://pay?');
}

function formatInr(amount: number): string {
  return Number.isInteger(amount) ? String(amount) : amount.toFixed(2);
}

function formatCountdown(expiresAt: number): string {
  const sec = Math.max(0, Math.floor((expiresAt - Date.now()) / 1000));
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

async function decodeUpiQrFromImageFile(file: File): Promise<{ upiVpa: string; businessName?: string }> {
  const objectUrl = URL.createObjectURL(file);
  try {
    const img = await new Promise<HTMLImageElement>((resolve, reject) => {
      const el = new Image();
      el.onload = () => resolve(el);
      el.onerror = () => reject(new Error('Photo load nahi ho paya'));
      el.src = objectUrl;
    });

    const maxDim = 1024;
    const scale = Math.min(1, maxDim / Math.max(img.naturalWidth || 1, img.naturalHeight || 1));
    const width = Math.max(1, Math.round(img.naturalWidth * scale));
    const height = Math.max(1, Math.round(img.naturalHeight * scale));

    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('Canvas unavailable');
    ctx.drawImage(img, 0, 0, width, height);
    const imgData = ctx.getImageData(0, 0, width, height);

    const decoded = jsQR(imgData.data, width, height, { inversionAttempts: 'attemptBoth' });
    if (!decoded || !decoded.data) {
      throw new Error('QR code scan nahi hua — saaf QR photo chunein ya UPI ID likhein');
    }

    const raw = decoded.data.trim();
    if (!raw.toLowerCase().startsWith('upi://pay?')) {
      throw new Error('Ye Paytm/UPI payment QR nahi lag raha');
    }
    const qs = new URLSearchParams(raw.slice(raw.indexOf('?') + 1));
    const pa = (qs.get('pa') || '').trim();
    const pn = (qs.get('pn') || '').trim();
    if (!pa || !pa.includes('@')) {
      throw new Error('QR me UPI ID (pa) nahi mili');
    }
    return { upiVpa: pa, businessName: pn || undefined };
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

export const LicenseUpdateView: React.FC<LicenseUpdateViewProps> = ({
  license,
  onLicenseChange,
  updateInfo,
  onUpdateInfoChange,
  onNotify,
  onEnterApp,
}) => {
  const [checkingLicense, setCheckingLicense] = useState(false);
  const [checkingUpdate, setCheckingUpdate] = useState(false);
  const [updateStatusMsg, setUpdateStatusMsg] = useState('');
  const [customIdInput, setCustomIdInput] = useState(license.deviceId);

  // Instant UPI Scanner state
  const [plansInfo, setPlansInfo] = useState<PublicPlansInfo | null>(null);
  const [selectedPlanId, setSelectedPlanId] = useState<string>('plan_7d');
  const [creatingOrder, setCreatingOrder] = useState(false);
  const [activeOrder, setActiveOrder] = useState<PaymentOrderData | null>(null);
  const [orderPaidMsg, setOrderPaidMsg] = useState<string>('');
  const [countdownText, setCountdownText] = useState<string>('');
  const [paymentError, setPaymentError] = useState<string>('');

  // Owner / Admin Auth & Business UPI Config state (token strictly in JS memory, NEVER localStorage)
  const [adminOpen, setAdminOpen] = useState(false);
  const [adminToken, setAdminToken] = useState<string | null>(null);
  const [authState, setAuthState] = useState<{
    authenticated: boolean;
    viaEnv: boolean;
    canClaimFirstRun: boolean;
  } | null>(null);
  const [passInput, setPassInput] = useState('');
  const [authBusy, setAuthBusy] = useState(false);
  const [adminDash, setAdminDash] = useState<AdminDashboardData | null>(null);
  const [editBizName, setEditBizName] = useState('');
  const [editBizUpi, setEditBizUpi] = useState('');
  const [editPlans, setEditPlans] = useState<SubscriptionPlan[]>([]);
  const [savingAdmin, setSavingAdmin] = useState(false);
  const [newWebhookSecret, setNewWebhookSecret] = useState<string>('');
  const [qrScanNotice, setQrScanNotice] = useState<string>('');
  const [scanningQrFile, setScanningQrFile] = useState(false);

  const loadPlans = useCallback(async () => {
    try {
      const info = await fetchPublicPlans();
      setPlansInfo(info);
      if (info.plans.length > 0 && !info.plans.some((p) => p.id === selectedPlanId)) {
        setSelectedPlanId(info.plans[0].id);
      }
    } catch {
      // Backend optional offline guard
    }
  }, [selectedPlanId]);

  useEffect(() => {
    void loadPlans();
  }, [loadPlans]);

  // 1-second auto-approval polling while a UPI QR order is PENDING
  useEffect(() => {
    if (!activeOrder || activeOrder.status !== 'PENDING') return;

    setCountdownText(formatCountdown(activeOrder.expiresAt));
    const tickTimer = window.setInterval(() => {
      setCountdownText(formatCountdown(activeOrder.expiresAt));
    }, 1000);

    const pollTimer = window.setInterval(async () => {
      try {
        const res = await pollPaymentOrderStatus(activeOrder.orderId);
        if (res.status === 'PAID' || res.licenseActive) {
          window.clearInterval(pollTimer);
          window.clearInterval(tickTimer);
          setActiveOrder((prev) => (prev ? { ...prev, status: 'PAID' } : null));
          const updated = applyLicenseExpiry(res.licenseExpiryMs);
          onLicenseChange(updated);
          playOrderSound();
          setOrderPaidMsg(
            `Payment Verified (${res.planName})! Approval turant active ho gaya.`
          );
          onNotify('Payment Verified! 1 second me approval active ho gaya');
        } else if (res.status === 'EXPIRED') {
          window.clearInterval(pollTimer);
          window.clearInterval(tickTimer);
          setActiveOrder((prev) => (prev ? { ...prev, status: 'EXPIRED' } : null));
        }
      } catch {
        // Retry next second silently
      }
    }, 1000);

    return () => {
      window.clearInterval(pollTimer);
      window.clearInterval(tickTimer);
    };
  }, [activeOrder, onLicenseChange, onNotify]);

  const loadAdminState = useCallback(
    async (tok: string | null) => {
      try {
        const state = await getAdminAuthState(tok);
        setAuthState(state);
        if (tok && state.authenticated) {
          const dash = await fetchAdminDashboard(tok);
          setAdminDash(dash);
          setEditBizName(dash.config.businessName);
          setEditBizUpi(dash.config.businessUpiVpa);
          setEditPlans(dash.plans);
        }
      } catch (err) {
        onNotify(err instanceof Error ? err.message : 'Admin panel load nahi hua');
      }
    },
    [onNotify]
  );

  useEffect(() => {
    if (adminOpen) {
      void loadAdminState(adminToken);
    }
  }, [adminOpen, adminToken, loadAdminState]);

  const handleCopyId = async () => {
    try {
      await navigator.clipboard.writeText(license.deviceId);
      onNotify('ID copy ho gayi');
    } catch {
      onNotify(`Device ID: ${license.deviceId}`);
    }
  };

  const handleShareWhatsApp = async () => {
    const text = `SwiftGate Device ID: ${license.deviceId}`;
    if (navigator.share) {
      try {
        await navigator.share({ title: 'SwiftGate Device ID', text });
        return;
      } catch {
        // Fallback to WhatsApp URL
      }
    }
    const url = `https://wa.me/?text=${encodeURIComponent(text)}`;
    const link = document.createElement('a');
    link.href = url;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.click();
  };

  const handleCheckApproval = async () => {
    setCheckingLicense(true);
    const next = await refreshLicenseAsync();
    onLicenseChange(next);
    setCheckingLicense(false);
    if (next.expiryMs > 0 && (next.isUnlimited || Date.now() < next.expiryMs)) {
      onNotify('Approval active hai!');
    } else if (next.lastError) {
      onNotify(next.lastError);
    } else {
      onNotify('Is Device ID par abhi approval nahi mila');
    }
  };

  const handleApplyDeviceId = async () => {
    setCustomDeviceId(customIdInput);
    await handleCheckApproval();
  };

  const handleTogglePreviewLock = () => {
    const nextUnlocked = !license.previewBypass;
    setPreviewUnlocked(nextUnlocked);
    onLicenseChange({
      ...license,
      previewBypass: nextUnlocked,
      isActive:
        (license.expiryMs > 0 && (license.isUnlimited || Date.now() < license.expiryMs)) ||
        nextUnlocked,
    });
    onNotify(
      nextUnlocked
        ? 'Web Preview Mode unlocked'
        : 'Approval Gate locked — ab UPI Payment / Approval ke baad hi app khulega'
    );
  };

  const handleGenerateQr = async (planId: string) => {
    setSelectedPlanId(planId);
    setPaymentError('');
    setOrderPaidMsg('');
    setCreatingOrder(true);
    try {
      const order = await createPaymentOrder(license.deviceId, planId);
      setActiveOrder(order);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'QR generate nahi ho paya';
      setPaymentError(msg);
      onNotify(msg);
    } finally {
      setCreatingOrder(false);
    }
  };

  const handleAdminAuthSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!passInput.trim()) return;
    setAuthBusy(true);
    try {
      const res = authState?.canClaimFirstRun
        ? await claimAdminPassword(passInput.trim())
        : await loginAdmin(passInput.trim());
      setAdminToken(res.token);
      setPassInput('');
      await loadAdminState(res.token);
      onNotify('Owner Business Panel unlocked');
    } catch (err) {
      onNotify(err instanceof Error ? err.message : 'Auth fail');
    } finally {
      setAuthBusy(false);
    }
  };

  const handleSaveBusinessConfig = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!adminToken) return;
    setSavingAdmin(true);
    try {
      await updateAdminBusinessConfig(adminToken, {
        businessName: editBizName,
        businessUpiVpa: editBizUpi,
        plans: editPlans,
      });
      await loadAdminState(adminToken);
      await loadPlans();
      onNotify('Business UPI Scanner & Plans save ho gaye!');
    } catch (err) {
      onNotify(err instanceof Error ? err.message : 'Save fail');
    } finally {
      setSavingAdmin(false);
    }
  };

  const handleRotateWebhookSecret = async () => {
    if (!adminToken) return;
    try {
      const res = await rotateMerchantWebhookSecret(adminToken);
      setNewWebhookSecret(res.webhookSecret);
      await loadAdminState(adminToken);
      onNotify('Naya Auto-Webhook Secret ban gaya — copy karke rakhein');
    } catch (err) {
      onNotify(err instanceof Error ? err.message : 'Secret generate fail');
    }
  };

  const handleQrPhotoUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    setScanningQrFile(true);
    setQrScanNotice('');
    try {
      const parsed = await decodeUpiQrFromImageFile(file);
      setEditBizUpi(parsed.upiVpa);
      if (parsed.businessName) {
        setEditBizName(parsed.businessName);
      }
      const msg = `Paytm QR se UPI ID mil gaya: ${parsed.upiVpa} — neeche "Save Karein" button dabayein`;
      setQrScanNotice(msg);
      onNotify(`QR se UPI ID mil gaya: ${parsed.upiVpa}`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'QR scan fail';
      setQrScanNotice(msg);
      onNotify(msg);
    } finally {
      setScanningQrFile(false);
    }
  };

  const handleConfirmOrderAdmin = async (orderId: string) => {
    if (!adminToken) return;
    try {
      await adminConfirmOrder(adminToken, orderId);
      await loadAdminState(adminToken);
      onNotify(`Order ${orderId} approve ho gaya!`);
    } catch (err) {
      onNotify(err instanceof Error ? err.message : 'Approve fail');
    }
  };

  const handleRevokeDeviceAdmin = async (deviceId: string) => {
    if (!adminToken) return;
    try {
      await adminRevokeDevice(adminToken, deviceId);
      await loadAdminState(adminToken);
      onNotify(`${deviceId} ka access band kiya gaya`);
    } catch (err) {
      onNotify(err instanceof Error ? err.message : 'Revoke fail');
    }
  };

  const handleCheckUpdate = async () => {
    setCheckingUpdate(true);
    const res = await checkForUpdate(true);
    onUpdateInfoChange(res.info);
    setUpdateStatusMsg(res.status);
    setCheckingUpdate(false);
  };

  const qrImageSrc = activeOrder?.qrDataUrl || activeOrder?.razorpayQrImageUrl || '';
  const webhookUrl =
    typeof window !== 'undefined' ? `${window.location.origin}/api/payments/merchant-webhook` : '';

  return (
    <div className="mx-auto max-w-3xl px-4 py-6 sm:px-6">
      <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-bold text-white">
            {license.isActive ? 'Instant UPI Approval & Updates' : 'Approval Zaroori Hai'}
          </h1>
          <p className="mt-1 text-sm text-[#9E9E9E]">
            Business UPI QR scan karke pay karein — payment aate hi 1 second me automatic approval milega.
          </p>
        </div>

        <button
          type="button"
          onClick={() => setAdminOpen((v) => !v)}
          className="flex min-h-[40px] items-center gap-2 rounded-xl border border-white/15 bg-[#1E1E1E] px-3.5 py-2 text-xs font-semibold text-white hover:bg-[#2A2A2A]"
        >
          <Settings2 className="h-4 w-4 text-[#81C784]" />
          <span>{adminOpen ? 'Owner Panel Band Karein' : 'Owner Business UPI Setup'}</span>
        </button>
      </div>

      {/* Instant Business UPI Payment & QR Scanner Card */}
      <div className="mb-6 rounded-2xl border border-[#43A047]/35 bg-[#1E1E1E] p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2.5">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-[#43A047]/20 text-[#81C784]">
              <QrCode className="h-5 w-5" />
            </div>
            <div>
              <h2 className="font-display text-lg font-bold text-white">
                Instant UPI Scanner (1-Sec Auto Approval)
              </h2>
              <p className="text-xs text-[#B0BEC5]">
                {plansInfo?.businessName || 'SwiftGate Business'}{' '}
                {plansInfo?.razorpayEnabled
                  ? '· Official Razorpay UPI Gateway Active'
                  : plansInfo?.businessUpiConfigured
                    ? `· UPI: ${plansInfo.businessUpiVpa}`
                    : '· Owner Panel me Business UPI ID set karein'}
              </p>
            </div>
          </div>

          {plansInfo?.instantAutoApproveReady && (
            <span className="inline-flex items-center gap-1 rounded-lg bg-[#235F37]/60 px-2.5 py-1 text-[11px] font-semibold text-[#5AD278]">
              <Sparkles className="h-3.5 w-3.5" />
              Auto-Approve Live
            </span>
          )}
        </div>

        {orderPaidMsg && (
          <div className="mt-4 flex items-center gap-2.5 rounded-xl border border-[#5AD278]/40 bg-[#235F37]/40 p-3.5 text-sm font-semibold text-[#5AD278]">
            <CheckCircle2 className="h-5 w-5 shrink-0" />
            <span>{orderPaidMsg}</span>
          </div>
        )}

        {/* Subscription Plans Grid */}
        <div className="mt-5 grid grid-cols-1 gap-2.5 sm:grid-cols-3">
          {(plansInfo?.plans || []).map((plan) => {
            const isSelected = selectedPlanId === plan.id && activeOrder?.plan.id === plan.id;
            return (
              <button
                key={plan.id}
                type="button"
                disabled={creatingOrder}
                onClick={() => void handleGenerateQr(plan.id)}
                className={`relative flex flex-col items-start rounded-xl border p-3.5 text-left transition-colors ${
                  isSelected
                    ? 'border-[#43A047] bg-[#235F37]/40'
                    : 'border-white/10 bg-[#141414] hover:border-white/25 hover:bg-[#252525]'
                }`}
              >
                {plan.badge && (
                  <span className="mb-1 text-[10px] font-bold uppercase tracking-wider text-[#FFB74D]">
                    {plan.badge}
                  </span>
                )}
                <span className="text-xs font-semibold text-[#E0E0E0]">{plan.name}</span>
                <span className="font-mono-tabular mt-1 text-xl font-bold text-white">
                  ₹{plan.priceInr}
                </span>
                <span className="mt-1 text-[11px] font-medium text-[#81C784]">
                  Pay & Unlock →
                </span>
              </button>
            );
          })}
        </div>

        {paymentError && (
          <div className="mt-4 rounded-xl border border-[#EF5350]/30 bg-[#C62828]/15 p-3 text-xs text-[#EF9A9A]">
            {paymentError}
          </div>
        )}

        {/* Active QR Display & 1-second Live Verification */}
        {activeOrder && activeOrder.status === 'PENDING' && (
          <div className="mt-5 rounded-xl border border-white/10 bg-[#141414] p-5">
            <div className="flex flex-col items-center gap-5 sm:flex-row sm:items-start">
              <div className="flex flex-col items-center rounded-2xl bg-white p-3 shadow-lg">
                {qrImageSrc ? (
                  <img
                    src={qrImageSrc}
                    alt={`UPI QR for ${activeOrder.orderId}`}
                    className="h-52 w-52 object-contain"
                  />
                ) : (
                  <div className="flex h-52 w-52 items-center justify-center text-xs text-black">
                    QR Loading...
                  </div>
                )}
                <span className="font-mono-tabular mt-2 text-xs font-bold text-[#121212]">
                  ₹{formatInr(activeOrder.amountInr)} · Ref {activeOrder.orderId}
                </span>
              </div>

              <div className="flex-1 text-center sm:text-left">
                <p className="text-xs font-semibold uppercase tracking-wider text-[#81C784]">
                  Kisi bhi UPI App se Scan ya Pay karein
                </p>
                <h3 className="mt-1 font-display text-lg font-bold text-white">
                  {activeOrder.plan.name} — ₹{formatInr(activeOrder.amountInr)}
                </h3>
                <p className="mt-1 text-xs text-[#B0BEC5]">
                  Device ID:{' '}
                  <span className="font-mono-tabular font-semibold text-white">
                    {activeOrder.deviceId}
                  </span>{' '}
                  · Valid:{' '}
                  <span className="font-mono-tabular text-[#FFB74D]">{countdownText}</span>
                </p>

                <div className="mt-4 flex flex-wrap justify-center gap-2.5 sm:justify-start">
                  {isSafeUpiUri(activeOrder.upiUri) && (
                    <a
                      href={activeOrder.upiUri}
                      className="flex min-h-[44px] items-center gap-2 rounded-xl bg-[#43A047] px-4 py-2.5 text-xs font-bold text-white hover:bg-[#388E3C]"
                    >
                      <Smartphone className="h-4 w-4" />
                      <span>PhonePe / GPay / Paytm Kholo</span>
                    </a>
                  )}
                  <button
                    type="button"
                    onClick={() => void handleGenerateQr(activeOrder.plan.id)}
                    className="flex min-h-[44px] items-center gap-1.5 rounded-xl bg-[#2A2A2A] px-3.5 py-2 text-xs font-semibold text-white hover:bg-[#363636]"
                  >
                    <RefreshCw className="h-3.5 w-3.5" />
                    <span>Naya QR</span>
                  </button>
                </div>

                <div className="mt-4 flex items-center justify-center gap-2 text-xs text-[#81C784] sm:justify-start">
                  <RefreshCw className="h-3.5 w-3.5 animate-spin" />
                  <span>
                    Har 1 second me payment check ho raha hai — payment hote hi app khud open ho jayega
                  </span>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Collapsible Owner Business Account & Webhook Admin Panel */}
      {adminOpen && (
        <div className="mb-6 rounded-2xl border border-[#FFB74D]/35 bg-[#1A1A1A] p-6">
          <div className="flex items-center gap-2">
            <KeyRound className="h-5 w-5 text-[#FFB74D]" />
            <h2 className="font-display text-lg font-bold text-white">
              Owner Business Account & Auto-Approve Settings
            </h2>
          </div>

          {!adminToken || !adminDash ? (
            <form onSubmit={handleAdminAuthSubmit} className="mt-4 space-y-3">
              <p className="text-xs text-[#B0BEC5]">
                {authState?.canClaimFirstRun
                  ? 'First-run setup: Apna Owner Admin Password banayein (kam se kam 6 characters) taaki aap apna Business UPI ID aur plans save kar sakein.'
                  : 'Apna Owner Admin Passcode daalkar Business UPI account settings kholein.'}
              </p>
              <div className="flex flex-col gap-2 sm:flex-row">
                <input
                  type="password"
                  value={passInput}
                  onChange={(e) => setPassInput(e.target.value)}
                  placeholder={
                    authState?.canClaimFirstRun
                      ? 'Naya Owner Password set karein'
                      : 'Owner Passcode daalein'
                  }
                  className="h-11 flex-1 rounded-xl border border-white/15 bg-[#2A2A2A] px-3.5 text-sm text-white focus:border-[#FFB74D] focus:outline-none"
                />
                <button
                  type="submit"
                  disabled={authBusy}
                  className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl bg-[#FFB74D] px-5 py-2.5 text-xs font-bold text-[#121212] hover:bg-[#FFA726] disabled:opacity-60"
                >
                  <Lock className="h-4 w-4" />
                  <span>
                    {authBusy
                      ? 'Wait...'
                      : authState?.canClaimFirstRun
                        ? 'Password Set & Unlock'
                        : 'Unlock Owner Panel'}
                  </span>
                </button>
              </div>
            </form>
          ) : (
            <div className="mt-5 space-y-6">
              {/* Business UPI VPA & Plans Form */}
              <form onSubmit={handleSaveBusinessConfig} className="space-y-4">
                <div className="rounded-xl border border-[#43A047]/35 bg-[#141414] p-3.5">
                  <div className="flex flex-wrap items-center justify-between gap-2.5">
                    <div>
                      <p className="text-xs font-bold text-[#81C784]">
                        Paytm Business Scanner Photo Se Direct UPI ID Nikalein
                      </p>
                      <p className="mt-0.5 text-[11px] text-[#9E9E9E]">
                        Apne Paytm for Business QR sticker ki photo/screenshot upload karein — UPI ID khud bhar jayegi.
                      </p>
                    </div>
                    <label className="inline-flex min-h-[38px] cursor-pointer items-center gap-2 rounded-lg bg-[#235F37] px-3.5 py-2 text-xs font-bold text-white hover:bg-[#2E7D32]">
                      <Upload className="h-3.5 w-3.5" />
                      <span>{scanningQrFile ? 'Scanning QR...' : 'Paytm QR Photo Upload'}</span>
                      <input
                        type="file"
                        accept="image/*"
                        onChange={handleQrPhotoUpload}
                        className="sr-only"
                        disabled={scanningQrFile}
                      />
                    </label>
                  </div>
                  {qrScanNotice && (
                    <p className="mt-2 text-xs font-medium text-[#FFB74D]">{qrScanNotice}</p>
                  )}
                </div>

                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <div>
                    <label className="mb-1 block text-xs font-semibold text-[#B0BEC5]">
                      Business / Dukaan ka Naam
                    </label>
                    <input
                      type="text"
                      value={editBizName}
                      onChange={(e) => setEditBizName(e.target.value)}
                      placeholder="SwiftGate Auto-Accept"
                      className="h-10 w-full rounded-lg border border-white/15 bg-[#2A2A2A] px-3 text-sm text-white focus:border-[#43A047] focus:outline-none"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-semibold text-[#B0BEC5]">
                      Paytm Business UPI ID (QR upload karein ya type karein)
                    </label>
                    <input
                      type="text"
                      value={editBizUpi}
                      onChange={(e) => setEditBizUpi(e.target.value)}
                      placeholder="jaise: paytmqr...@paytm ya name@okaxis"
                      className="font-mono-tabular h-10 w-full rounded-lg border border-white/15 bg-[#2A2A2A] px-3 text-sm text-white focus:border-[#43A047] focus:outline-none"
                    />
                  </div>
                </div>

                <div>
                  <label className="mb-2 block text-xs font-semibold text-[#B0BEC5]">
                    Plans & Rates (₹)
                  </label>
                  <div className="space-y-2">
                    {editPlans.map((plan, idx) => (
                      <div
                        key={plan.id}
                        className="grid grid-cols-3 gap-2 rounded-lg bg-[#141414] p-2.5"
                      >
                        <input
                          type="text"
                          value={plan.name}
                          onChange={(e) =>
                            setEditPlans((prev) =>
                              prev.map((p, i) => (i === idx ? { ...p, name: e.target.value } : p))
                            )
                          }
                          className="h-9 rounded bg-[#2A2A2A] px-2.5 text-xs text-white"
                          placeholder="Plan name"
                        />
                        <input
                          type="number"
                          value={plan.durationHours}
                          min={0}
                          onChange={(e) =>
                            setEditPlans((prev) =>
                              prev.map((p, i) =>
                                i === idx ? { ...p, durationHours: Number(e.target.value) } : p
                              )
                            )
                          }
                          className="font-mono-tabular h-9 rounded bg-[#2A2A2A] px-2.5 text-xs text-white"
                          title="Hours (0 = Lifetime)"
                          placeholder="Hours (0=Life)"
                        />
                        <input
                          type="number"
                          value={plan.priceInr}
                          min={1}
                          onChange={(e) =>
                            setEditPlans((prev) =>
                              prev.map((p, i) =>
                                i === idx ? { ...p, priceInr: Number(e.target.value) } : p
                              )
                            )
                          }
                          className="font-mono-tabular h-9 rounded bg-[#2A2A2A] px-2.5 text-xs text-white"
                          placeholder="Price ₹"
                        />
                      </div>
                    ))}
                  </div>
                </div>

                <button
                  type="submit"
                  disabled={savingAdmin}
                  className="min-h-[42px] rounded-xl bg-[#43A047] px-5 py-2 text-xs font-bold text-white hover:bg-[#388E3C]"
                >
                  {savingAdmin ? 'Saving...' : 'Business UPI & Plans Save Karein'}
                </button>
              </form>

              {/* 1-Second Auto-Approval Webhook Info */}
              <div className="rounded-xl border border-white/10 bg-[#141414] p-4 text-xs">
                <p className="font-bold text-white">1-Second Zero-Touch Auto-Approval Status</p>
                <div className="mt-2 grid grid-cols-1 gap-1.5 sm:grid-cols-2">
                  <p className="text-[#B0BEC5]">
                    Razorpay API Keys:{' '}
                    <span
                      className={
                        adminDash.integrations.razorpayConfigured ? 'text-[#5AD278]' : 'text-[#FFB74D]'
                      }
                    >
                      {adminDash.integrations.razorpayConfigured ? 'CONNECTED' : 'Not Set in Secrets'}
                    </span>
                  </p>
                  <p className="text-[#B0BEC5]">
                    GitHub Auto-Sync:{' '}
                    <span
                      className={
                        adminDash.integrations.githubTokenConfigured
                          ? 'text-[#5AD278]'
                          : 'text-[#9E9E9E]'
                      }
                    >
                      {adminDash.integrations.githubTokenConfigured ? 'ACTIVE' : 'Optional (Not Set)'}
                    </span>
                  </p>
                </div>

                <div className="mt-3 border-t border-white/10 pt-3">
                  <p className="font-semibold text-[#81C784]">
                    Sirf Paytm for Business Scanner se 1-Second Auto-Approve (0% Fee):
                  </p>
                  <p className="mt-1 text-[#9E9E9E]">
                    Aapka Paytm QR (<span className="font-mono-tabular text-white">{editBizUpi || 'paytmqr5i4hko@ptys'}</span>) pehle se set hai. Auto-approve chalu karne ke liye apne Paytm Business wale phone me SwiftGate app kholein → Approval screen par neeche{' '}
                    <span className="font-semibold text-white">Owner Paytm Auto-Approve Setup</span>{' '}
                    me bas apna yahi Owner Passcode daalkar{' '}
                    <span className="font-semibold text-white">Auto-Connect</span> dabayein aur Notification Access ON kar dein — kuch copy-paste karne ki zaroorat nahi!
                  </p>
                  <p className="font-mono-tabular mt-2 select-all break-all rounded bg-[#1E1E1E] p-2 text-[11px] text-white">
                    POST {webhookUrl}
                  </p>
                  <div className="mt-2 flex flex-wrap items-center gap-2">
                    <button
                      type="button"
                      onClick={() => void handleRotateWebhookSecret()}
                      className="rounded-lg bg-[#2A2A2A] px-3 py-1.5 text-[11px] font-semibold text-white hover:bg-[#363636]"
                    >
                      {adminDash.integrations.merchantWebhookKeySet
                        ? 'Webhook Secret Rotate Karein'
                        : 'Webhook Secret Generate Karein'}
                    </button>
                    {newWebhookSecret && (
                      <span className="font-mono-tabular select-all rounded bg-[#235F37]/50 px-2.5 py-1 text-[11px] text-[#5AD278]">
                        x-webhook-secret: {newWebhookSecret}
                      </span>
                    )}
                  </div>
                </div>
              </div>

              {/* Recent Orders & One-Click Confirm */}
              <div>
                <h3 className="text-xs font-bold uppercase tracking-wider text-[#B0BEC5]">
                  Recent UPI Orders ({adminDash.orders.length})
                </h3>
                <div className="mt-2 max-h-60 space-y-2 overflow-y-auto pr-1">
                  {adminDash.orders.length === 0 && (
                    <p className="text-xs text-[#9E9E9E]">Abhi koi payment order nahi bana hai.</p>
                  )}
                  {adminDash.orders.map((ord) => (
                    <div
                      key={ord.orderId}
                      className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-[#141414] px-3 py-2 text-xs"
                    >
                      <div>
                        <span className="font-mono-tabular font-bold text-white">{ord.orderId}</span>{' '}
                        · <span className="font-mono-tabular text-[#81C784]">{ord.deviceId}</span> ·{' '}
                        <span className="text-white">₹{formatInr(ord.amountInr)}</span> ({ord.planName})
                        {ord.utr && (
                          <span className="ml-2 text-[11px] text-[#9E9E9E]">UTR: {ord.utr}</span>
                        )}
                      </div>
                      <div className="flex items-center gap-2">
                        <span
                          className={`rounded px-2 py-0.5 text-[10px] font-bold ${
                            ord.status === 'PAID'
                              ? 'bg-[#235F37]/60 text-[#5AD278]'
                              : ord.status === 'EXPIRED'
                                ? 'bg-[#C62828]/30 text-[#EF5350]'
                                : 'bg-[#FFB74D]/20 text-[#FFB74D]'
                          }`}
                        >
                          {ord.status}
                        </span>
                        {ord.status === 'PENDING' && (
                          <button
                            type="button"
                            onClick={() => void handleConfirmOrderAdmin(ord.orderId)}
                            className="rounded bg-[#43A047] px-2.5 py-1 text-[11px] font-bold text-white hover:bg-[#388E3C]"
                          >
                            Verify & Approve
                          </button>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Active Licenses */}
              {adminDash.licenses.length > 0 && (
                <div>
                  <h3 className="text-xs font-bold uppercase tracking-wider text-[#B0BEC5]">
                    Approved Devices ({adminDash.licenses.length})
                  </h3>
                  <div className="mt-2 space-y-1.5">
                    {adminDash.licenses.map((lic) => (
                      <div
                        key={lic.deviceId}
                        className="flex items-center justify-between rounded-lg bg-[#141414] px-3 py-2 text-xs"
                      >
                        <div>
                          <span className="font-mono-tabular font-bold text-white">{lic.deviceId}</span>{' '}
                          · <span className="text-[#5AD278]">{lic.planName}</span>
                        </div>
                        <button
                          type="button"
                          onClick={() => void handleRevokeDeviceAdmin(lic.deviceId)}
                          className="flex items-center gap-1 rounded bg-[#C62828]/25 px-2 py-1 text-[11px] text-[#EF5350] hover:bg-[#C62828]/40"
                        >
                          <Trash2 className="h-3 w-3" />
                          <span>Revoke</span>
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* Existing Device ID & Manual/GitHub License Card */}
      <div className="rounded-2xl border border-white/10 bg-[#1E1E1E] p-6">
        <div className="text-center">
          <p className="text-xs font-medium tracking-wide text-[#9E9E9E]">DEVICE ID</p>
          <p className="font-mono-tabular mt-2 select-all text-3xl font-bold tracking-widest text-white">
            {license.deviceId}
          </p>
          <p
            className={`mt-3 text-sm font-semibold ${
              license.isActive ? 'text-[#5AD278]' : 'text-[#FFB74D]'
            }`}
          >
            {license.remainingText}
          </p>
        </div>

        <div className="mt-6 grid grid-cols-1 gap-2.5 sm:grid-cols-3">
          <button
            type="button"
            onClick={handleCopyId}
            className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl bg-[#2A2A2A] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#363636]"
          >
            <Copy className="h-4 w-4" />
            <span>ID Copy Karein</span>
          </button>

          <button
            type="button"
            onClick={handleShareWhatsApp}
            className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl bg-[#2A2A2A] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#363636]"
          >
            <Share2 className="h-4 w-4" />
            <span>ID WhatsApp par Bhejein</span>
          </button>

          <button
            type="button"
            onClick={handleCheckApproval}
            disabled={checkingLicense}
            className="flex min-h-[44px] items-center justify-center gap-2 rounded-xl bg-[#43A047] px-4 py-2.5 text-sm font-bold text-white hover:bg-[#388E3C] disabled:opacity-60"
          >
            <RefreshCw className={`h-4 w-4 ${checkingLicense ? 'animate-spin' : ''}`} />
            <span>{checkingLicense ? 'Check ho raha hai...' : 'Approval Check Karein'}</span>
          </button>
        </div>

        {(license.lastError || license.debugInfo) && (
          <div className="mt-4 rounded-xl bg-[#141414] p-3 text-xs text-[#B0BEC5]">
            {license.lastError && <p className="text-[#EF5350]">{license.lastError}</p>}
            {license.debugInfo && (
              <p className="font-mono-tabular mt-1 text-[#9E9E9E]">[{license.debugInfo}]</p>
            )}
          </div>
        )}

        {/* Device ID tester & Preview Gate toggle */}
        <div className="mt-5 border-t border-white/10 pt-4">
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <input
              type="text"
              value={customIdInput}
              onChange={(e) => setCustomIdInput(e.target.value.toUpperCase())}
              maxLength={12}
              placeholder="Custom Device ID test karein"
              className="font-mono-tabular h-10 flex-1 rounded-lg border border-white/10 bg-[#2A2A2A] px-3 text-sm text-white focus:border-[#43A047] focus:outline-none"
            />
            <button
              type="button"
              onClick={handleApplyDeviceId}
              className="min-h-[40px] rounded-lg bg-[#2A2A2A] px-4 py-2 text-xs font-semibold text-white hover:bg-[#363636]"
            >
              ID Apply & Check
            </button>
            <button
              type="button"
              onClick={handleTogglePreviewLock}
              className={`flex min-h-[40px] items-center justify-center gap-1.5 rounded-lg border px-3.5 py-2 text-xs font-semibold ${
                license.previewBypass
                  ? 'border-[#5AD278]/40 bg-[#235F37]/50 text-[#5AD278]'
                  : 'border-[#EF5350]/40 bg-[#C62828]/20 text-[#EF5350]'
              }`}
            >
              {license.previewBypass ? (
                <>
                  <ShieldCheck className="h-4 w-4" />
                  <span>Preview Unlocked</span>
                </>
              ) : (
                <>
                  <ShieldOff className="h-4 w-4" />
                  <span>Gate Locked</span>
                </>
              )}
            </button>
          </div>
          {license.isActive && (
            <button
              type="button"
              onClick={onEnterApp}
              className="mt-3 min-h-[42px] w-full rounded-xl border border-white/15 py-2 text-xs font-semibold text-white hover:bg-white/5"
            >
              Control Panel Kholein
            </button>
          )}
        </div>
      </div>

      {/* App Update Card */}
      <div className="mt-6 rounded-2xl border border-white/10 bg-[#1E1E1E] p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="font-display text-lg font-bold text-white">App Update Check</h2>
            <p className="mt-0.5 text-sm text-[#9E9E9E]">
              Aapka version:{' '}
              <span className="font-mono-tabular text-white">{CURRENT_VERSION_NAME}</span>
            </p>
          </div>
          <button
            type="button"
            onClick={handleCheckUpdate}
            disabled={checkingUpdate}
            className="min-h-[40px] rounded-lg bg-[#2A2A2A] px-4 py-2 text-xs font-semibold text-white hover:bg-[#363636]"
          >
            {checkingUpdate ? 'Checking...' : 'Update Check Karein'}
          </button>
        </div>

        {updateStatusMsg && (
          <p className="mt-3 text-xs text-[#B0BEC5]">{updateStatusMsg}</p>
        )}

        {updateInfo && (
          <div className="mt-4 rounded-xl border border-[#FFB74D]/40 bg-[#171717] p-4">
            <h3 className="font-display text-base font-bold text-white">
              Naya Update Available — v{updateInfo.name}
            </h3>
            {updateInfo.forced && (
              <p className="mt-1 text-xs font-bold text-[#EF5350]">
                Ye update zaroori hai. Update karne ke baad hi app chalega.
              </p>
            )}
            {updateInfo.notes.length > 0 && (
              <ul className="mt-3 space-y-1 text-sm text-[#E0E0E0]">
                {updateInfo.notes.map((note, idx) => (
                  <li key={idx}>• {note}</li>
                ))}
              </ul>
            )}
            <div className="mt-4 flex flex-wrap gap-2.5">
              <a
                href={updateInfo.url}
                target="_blank"
                rel="noopener noreferrer"
                className="flex min-h-[40px] items-center gap-2 rounded-lg bg-[#43A047] px-4 py-2 text-xs font-bold text-white hover:bg-[#388E3C]"
              >
                <span>Update Karein</span>
                <ExternalLink className="h-3.5 w-3.5" />
              </a>
              {!updateInfo.forced && (
                <button
                  type="button"
                  onClick={() => {
                    snoozeUpdate(updateInfo.code);
                    onUpdateInfoChange(null);
                    onNotify('Update 6 ghante ke liye snooze ho gaya');
                  }}
                  className="min-h-[40px] rounded-lg bg-[#2A2A2A] px-4 py-2 text-xs font-semibold text-[#B0BEC5] hover:text-white"
                >
                  Baad Me
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
