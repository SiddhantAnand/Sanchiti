const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 });
const inrWhole = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
const compact = new Intl.NumberFormat('en-IN', { notation: 'compact', maximumFractionDigits: 1 });

export function money(n: number, currency = 'INR') {
  if (currency !== 'INR') {
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency, maximumFractionDigits: 2 }).format(n);
  }
  return Number.isInteger(n) ? inrWhole.format(n) : inr.format(n);
}

export const moneyWhole = (n: number) => inrWhole.format(Math.round(n));
export const moneyCompact = (n: number) => `₹${compact.format(n)}`;

export function toIso(d: Date) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export function parseIso(s: string) {
  const [y, m, d] = s.split('-').map(Number);
  return new Date(y, m - 1, d);
}

const addDays = (d: Date, n: number) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);

export const shortDate = (s: string) =>
  parseIso(s).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
export const longDate = (s: string) =>
  parseIso(s).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
export const dayHeading = (s: string) => {
  const today = toIso(new Date());
  if (s === today) return 'Today';
  if (s === toIso(addDays(new Date(), -1))) return 'Yesterday';
  return parseIso(s).toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long' });
};

export function rangeLabel(from: string, to: string) {
  const a = parseIso(from);
  const b = parseIso(to);
  const sameYear = a.getFullYear() === b.getFullYear();
  const fa = a.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: sameYear ? undefined : 'numeric' });
  const fb = b.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
  return `${fa} – ${fb}`;
}

export type PeriodKey = '7d' | '30d' | 'month' | 'lastMonth' | '90d' | 'ytd';
export type Period = { key: PeriodKey; label: string; from: string; to: string; prevFrom: string; prevTo: string };

export const PERIODS: { key: PeriodKey; label: string }[] = [
  { key: '7d', label: '7D' },
  { key: '30d', label: '30D' },
  { key: 'month', label: 'This month' },
  { key: 'lastMonth', label: 'Last month' },
  { key: '90d', label: '90D' },
  { key: 'ytd', label: 'YTD' },
];

export function period(key: PeriodKey, now = new Date()): Period {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  let from: Date;
  let to = today;
  switch (key) {
    case '7d': from = addDays(today, -6); break;
    case '30d': from = addDays(today, -29); break;
    case '90d': from = addDays(today, -89); break;
    case 'month': from = new Date(today.getFullYear(), today.getMonth(), 1); break;
    case 'lastMonth':
      from = new Date(today.getFullYear(), today.getMonth() - 1, 1);
      to = new Date(today.getFullYear(), today.getMonth(), 0);
      break;
    case 'ytd': from = new Date(today.getFullYear(), 0, 1); break;
  }
  const days = Math.round((to.getTime() - from.getTime()) / 86_400_000) + 1;
  const prevTo = addDays(from, -1);
  const prevFrom = addDays(prevTo, -(days - 1));
  return {
    key,
    label: PERIODS.find((p) => p.key === key)!.label,
    from: toIso(from),
    to: toIso(to),
    prevFrom: toIso(prevFrom),
    prevTo: toIso(prevTo),
  };
}

export function greeting(now = new Date()) {
  const h = now.getHours();
  if (h < 5) return 'Up late';
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  return 'Good evening';
}

export const initials = (name: string) =>
  name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]!.toUpperCase()).join('');

export function methodLabel(method: string | null, cardLast4?: string | null) {
  if (!method) return null;
  const m = method.toUpperCase();
  if (m === 'CARD') return cardLast4 ? `Card •• ${cardLast4}` : 'Card';
  if (m === 'UPI') return 'UPI';
  return m.charAt(0) + m.slice(1).toLowerCase().replace(/_/g, ' ');
}
