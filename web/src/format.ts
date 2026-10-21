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
  const fb = b.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
  if (from === to) return fb;
  const sameYear = a.getFullYear() === b.getFullYear();
  const fa = a.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: sameYear ? undefined : 'numeric' });
  return `${fa} – ${fb}`;
}

export type PresetKey = '1d' | '7d' | '30d' | 'month' | 'lastMonth' | '90d' | 'ytd';
/** A preset, a single day ("d:2026-10-20"), a calendar month ("m:2026-09") or a whole year ("y:2026"). */
export type PeriodKey = PresetKey | `d:${string}` | `m:${string}` | `y:${string}`;
export type Period = { key: PeriodKey; label: string; from: string; to: string; prevFrom: string; prevTo: string };

export const PERIODS: { key: PresetKey; label: string }[] = [
  { key: '1d', label: '1D' },
  { key: '7d', label: '7D' },
  { key: '30d', label: '30D' },
  { key: 'month', label: 'This month' },
  { key: 'lastMonth', label: 'Last month' },
  { key: '90d', label: '90D' },
  { key: 'ytd', label: 'YTD' },
];

export const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const DAY_KEY = /^d:(\d{4}-\d{2}-\d{2})$/;
const MONTH_KEY = /^m:(\d{4})-(\d{2})$/;
const YEAR_KEY = /^y:(\d{4})$/;

export function isPeriodKey(key: string | null): key is PeriodKey {
  if (!key) return false;
  if (PERIODS.some((p) => p.key === key)) return true;
  const d = key.match(DAY_KEY);
  if (d) return !Number.isNaN(parseIso(d[1]).getTime());
  const m = key.match(MONTH_KEY);
  if (m) return Number(m[2]) >= 1 && Number(m[2]) <= 12;
  return YEAR_KEY.test(key);
}

/** The year and month (1-12) a month or year key points at; nulls for presets. */
export function periodParts(key: PeriodKey): { year: number | null; month: number | null } {
  const m = key.match(MONTH_KEY);
  if (m) return { year: Number(m[1]), month: Number(m[2]) };
  const y = key.match(YEAR_KEY);
  return y ? { year: Number(y[1]), month: null } : { year: null, month: null };
}

/** The ISO date a one-day key covers ("1d" is today), else null. */
export function periodDay(key: PeriodKey, now = new Date()): string | null {
  if (key === '1d') return toIso(now);
  return key.match(DAY_KEY)?.[1] ?? null;
}

/** Key for one day; today stays "1d" so it keeps following the date. */
export const dayKey = (iso: string): PeriodKey => (iso === toIso(new Date()) ? '1d' : `d:${iso}`);

export function period(key: PeriodKey, now = new Date()): Period {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const day = key.match(DAY_KEY)?.[1];
  if (day) {
    return {
      key,
      label: shortDate(day),
      from: day,
      to: day,
      prevFrom: toIso(addDays(parseIso(day), -1)),
      prevTo: toIso(addDays(parseIso(day), -1)),
    };
  }
  const { year, month } = periodParts(key);
  if (year !== null) {
    // A calendar month or year, compared with the one before it; never runs past today.
    const from = month ? new Date(year, month - 1, 1) : new Date(year, 0, 1);
    const end = month ? new Date(year, month, 0) : new Date(year, 11, 31);
    const prevFrom = month ? new Date(year, month - 2, 1) : new Date(year - 1, 0, 1);
    const prevTo = addDays(from, -1);
    return {
      key,
      label: month ? `${MONTHS[month - 1]} ${year}` : String(year),
      from: toIso(from),
      to: toIso(end > today && from <= today ? today : end),
      prevFrom: toIso(prevFrom),
      prevTo: toIso(prevTo),
    };
  }

  let from: Date;
  let to = today;
  switch (key as PresetKey) {
    case '1d': from = today; break;
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
