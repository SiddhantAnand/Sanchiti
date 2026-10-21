import { useCallback, useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, CircleHelp, Ellipsis, Inbox, Landmark, LayoutDashboard, LoaderCircle, LogOut, ReceiptText, Settings as SettingsIcon, Sparkles, Users } from 'lucide-react';
import { api, setUnauthorizedHandler, type Direction, type Me, type ReviewItem, type Transaction } from './api';
import {
  MONTHS, PERIODS, dayKey, greeting, initials, isPeriodKey, period as makePeriod, periodDay, periodParts, rangeLabel, toIso,
  type PeriodKey,
} from './format';
import { GeminiModal } from './GeminiModal';
import { TxDrawer, type DrawerMode } from './TxDrawer';
import { Banks } from './pages/Banks';
import { Insights } from './pages/Insights';
import { Login } from './pages/Login';
import { Overview } from './pages/Overview';
import { Review } from './pages/Review';
import { Settings } from './pages/Settings';
import { Help } from './pages/Help';
import { Split, SplitTransaction } from './pages/Split';
import { Transactions } from './pages/Transactions';

type Route = 'overview' | 'transactions' | 'banks' | 'split' | 'review' | 'insights' | 'settings' | 'help';
const ROUTES: Route[] = ['overview', 'transactions', 'banks', 'split', 'review', 'insights', 'settings', 'help'];
/** Shown in the phone's bottom bar; the rest live under More. */
const TABS: Route[] = ['overview', 'transactions', 'split', 'review'];

function parseHash(): { route: Route; category: string; bank: string; kind: string; direction: Direction } {
  const [path, query] = window.location.hash.replace(/^#\/?/, '').split('?');
  const route = (ROUTES as string[]).includes(path) ? (path as Route) : 'overview';
  const params = new URLSearchParams(query);
  return {
    route, category: params.get('category') ?? '', bank: params.get('bank') ?? '', kind: params.get('kind') ?? '',
    direction: params.get('direction') === 'credit' ? 'credit' : 'debit',
  };
}

type Auth = { state: 'loading' } | { state: 'out' } | { state: 'in'; me: Me };

export default function App() {
  const [auth, setAuth] = useState<Auth>({ state: 'loading' });

  useEffect(() => {
    setUnauthorizedHandler(() => setAuth({ state: 'out' }));
    api.me()
      .then((me) => setAuth({ state: 'in', me }))
      .catch(() => setAuth({ state: 'out' }));
  }, []);

  if (auth.state === 'loading') {
    return <div style={{ display: 'grid', placeItems: 'center', height: '100vh' }}><LoaderCircle className="spinner" color="var(--primary)" /></div>;
  }
  if (auth.state === 'out') return <Login onSignedIn={(me) => setAuth({ state: 'in', me })} />;
  return <Shell me={auth.me} onMeChanged={(me) => setAuth({ state: 'in', me })} onSignedOut={() => setAuth({ state: 'out' })} />;
}

function Shell({ me, onMeChanged, onSignedOut }: { me: Me; onMeChanged: (me: Me) => void; onSignedOut: () => void }) {
  const [{ route, category, bank, kind, direction }, setLocation] = useState(parseHash);
  const [periodKey, setPeriodKey] = useState<PeriodKey>(() => {
    const saved = localStorage.getItem('period');
    return isPeriodKey(saved) ? saved : 'month';
  });
  const [refreshKey, setRefreshKey] = useState(0);
  const [ai, setAi] = useState(false);
  const [categories, setCategories] = useState<string[]>([]);
  const [creditCategories, setCreditCategories] = useState<string[]>([]);
  const [firstYear, setFirstYear] = useState(new Date().getFullYear());
  const [banks, setBanks] = useState<string[]>([]);
  const [reviewCount, setReviewCount] = useState(0);
  const [drawer, setDrawer] = useState<DrawerMode | null>(null);
  const [gemini, setGemini] = useState(false);
  const [splitTx, setSplitTx] = useState<Transaction | null>(null);
  const [moreOpen, setMoreOpen] = useState(false);

  const period = makePeriod(periodKey);
  const refresh = useCallback(() => setRefreshKey((k) => k + 1), []);

  /** Saves a new category and returns its stored spelling. */
  const addCategory = useCallback(async (name: string, direction: Direction) => {
    const c = await api.addCategory(name, direction);
    setCategories(c.categories);
    setCreditCategories(c.creditCategories);
    const list = direction === 'credit' ? c.creditCategories : c.categories;
    return list.find((x) => x.toLowerCase() === name.trim().toLowerCase()) ?? name.trim();
  }, []);
  const addBank = useCallback(async (name: string) => {
    const b = await api.addBank(name);
    setBanks(b.banks);
    const key = (s: string) => s.toLowerCase().replace(/\b(bank|ltd|limited|the|india)\b|[^a-z0-9]/g, '');
    return b.banks.find((x) => key(x) === key(name)) ?? name.trim();
  }, []);

  useEffect(() => {
    const h = () => setLocation(parseHash());
    window.addEventListener('hashchange', h);
    return () => window.removeEventListener('hashchange', h);
  }, []);
  useEffect(() => localStorage.setItem('period', periodKey), [periodKey]);
  useEffect(() => {
    api.health().then((h) => setAi(h.ai)).catch(() => {});
    api.categories().then((c) => { setCategories(c.categories); setCreditCategories(c.creditCategories); }).catch(() => {});
    api.banks().then((b) => setBanks(b.banks)).catch(() => {});
  }, []);
  useEffect(() => {
    api.review().then((r) => setReviewCount(r.items.length)).catch(() => {});
    api.span().then((s) => s.first && setFirstYear(Number(s.first.slice(0, 4)))).catch(() => {});
  }, [refreshKey]);

  const go = (r: string) => { setMoreOpen(false); window.location.hash = `/${r}`; };

  async function signOut() {
    await api.logout().catch(() => {});
    onSignedOut();
  }

  const first = me.name.split(/\s+/)[0];
  const titles: Record<Route, [string, string]> = {
    overview: [`${greeting()}, ${first}`, `Money in and out · ${rangeLabel(period.from, period.to)}`],
    transactions: ['Transactions', `Every debit and credit, editable · ${rangeLabel(period.from, period.to)}`],
    banks: ['Banks', `Money in and out of each account · ${rangeLabel(period.from, period.to)}`],
    split: ['Split', 'Shared expenses with friends · kept apart from your own spending'],
    review: ['Review queue', 'Bank SMS that could not be filed on their own'],
    insights: ['Insights', 'Weekly read on your spending, written by Gemini'],
    settings: ['Settings', me.admin ? 'Password, backup, Gemini and people you invite' : 'Password and backup'],
    help: ['Help', 'Your API token and how to send bank SMS from your phone or an app'],
  };
  const showPeriod = route === 'overview' || route === 'transactions' || route === 'banks';

  const nav: { id: Route; label: string; icon: typeof Inbox; badge?: number }[] = [
    { id: 'overview', label: 'Overview', icon: LayoutDashboard },
    { id: 'transactions', label: 'Transactions', icon: ReceiptText },
    { id: 'banks', label: 'Banks', icon: Landmark },
    { id: 'split', label: 'Split', icon: Users },
    { id: 'review', label: 'Review', icon: Inbox, badge: reviewCount },
    { id: 'insights', label: 'Insights', icon: Sparkles },
    { id: 'settings', label: 'Settings', icon: SettingsIcon },
    { id: 'help', label: 'Help', icon: CircleHelp },
  ];

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">₹</span>
          <div><div className="brand-name">Sanchiti</div><div className="brand-sub">Personal finance</div></div>
        </div>
        <div className="nav-label">Menu</div>
        <nav className="nav">
          {nav.map(({ id, label, icon: Icon, badge }) => (
            <button key={id} className={`nav-item ${route === id ? 'active' : ''}`} onClick={() => go(id)}>
              <Icon size={18} /> {label}
              {!!badge && <span className="badge badge-amber">{badge}</span>}
            </button>
          ))}
        </nav>

        <div className="sidebar-foot">
          <div className="ai-status">
            <span className={`ai-dot ${ai ? 'on' : ''}`} />
            <div>
              <div style={{ fontWeight: 600 }}>{ai ? 'Gemini connected' : 'Gemini off'}</div>
              <div className="faint" style={{ fontSize: 11.5 }}>{ai ? 'Auto-categorising new spends' : 'An admin can add a key in Settings'}</div>
            </div>
          </div>
          <div className="user-card">
            <span className="avatar">{initials(me.name)}</span>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="user-name">{me.name}</div>
              <div className="user-role">@{me.login}{me.admin ? ' · admin' : ''}</div>
            </div>
            <button className="icon-btn" onClick={signOut} aria-label="Sign out" title="Sign out"><LogOut size={16} /></button>
          </div>
        </div>
      </aside>

      <nav className="tabbar" aria-label="Menu">
        {nav.filter((n) => TABS.includes(n.id)).map(({ id, label, icon: Icon, badge }) => (
          <button key={id} className={route === id ? 'on' : ''} onClick={() => go(id)}>
            <span className="tab-icon"><Icon size={21} />{!!badge && <i className="tab-badge">{badge}</i>}</span>
            {label}
          </button>
        ))}
        <button className={moreOpen || !TABS.includes(route) ? 'on' : ''} onClick={() => setMoreOpen((o) => !o)} aria-expanded={moreOpen}>
          <span className="tab-icon"><Ellipsis size={21} /></span>
          More
        </button>
      </nav>
      {moreOpen && (
        <>
          <div className="overlay sheet-overlay" onClick={() => setMoreOpen(false)} />
          <div className="sheet" role="dialog" aria-label="More">
            <div className="sheet-grip" />
            <div className="user-card">
              <span className="avatar">{initials(me.name)}</span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="user-name">{me.name}</div>
                <div className="user-role">@{me.login}{me.admin ? ' · admin' : ''} · {ai ? 'Gemini connected' : 'Gemini off'}</div>
              </div>
            </div>
            {nav.filter((n) => !TABS.includes(n.id)).map(({ id, label, icon: Icon }) => (
              <button key={id} className={`nav-item ${route === id ? 'active' : ''}`} onClick={() => go(id)}>
                <Icon size={18} /> {label}
              </button>
            ))}
            <button className="nav-item" onClick={signOut}><LogOut size={18} /> Sign out</button>
          </div>
        </>
      )}

      <main className="main">
        <header className="topbar">
          <div>
            <h1 className="page-title">{titles[route][0]}</h1>
            <div className="page-sub">{titles[route][1]}</div>
          </div>
          <div className="topbar-actions">
            {showPeriod && (
              <div className="segmented" role="tablist" aria-label="Period">
                {PERIODS.map((p) => (
                  <button key={p.key} className={p.key === periodKey || (p.key === '1d' && periodDay(periodKey)) ? 'on' : ''}
                    onClick={() => setPeriodKey(p.key)}>{p.label}</button>
                ))}
              </div>
            )}
            {showPeriod && periodDay(periodKey) && (
              <DayPicker day={periodDay(periodKey)!} firstYear={firstYear} onPick={(d) => setPeriodKey(dayKey(d))} />
            )}
            {showPeriod && !periodDay(periodKey) && <MonthPicker periodKey={periodKey} firstYear={firstYear} onPick={setPeriodKey} />}
            <button className="btn btn-gemini" disabled={!ai} onClick={() => setGemini(true)} title={ai ? '' : 'Gemini is not configured'}>
              <Sparkles size={16} /> Send to Gemini
            </button>
          </div>
        </header>

        {route === 'overview' && (
          <Overview period={period} refreshKey={refreshKey} aiEnabled={ai} onGo={go}
            onOpenCategory={(c) => { window.location.hash = `/transactions?category=${encodeURIComponent(c)}`; }} />
        )}
        {route === 'transactions' && (
          <Transactions key={`${category}|${bank}|${kind}|${direction}`} period={period} refreshKey={refreshKey}
            categories={categories} creditCategories={creditCategories} onAddCategory={addCategory}
            initialCategory={category} initialBank={bank} initialKind={kind} initialDirection={direction}
            aiEnabled={ai} onChanged={refresh} onAdd={(d) => setDrawer({ kind: 'add', direction: d })}
            onEdit={(tx: Transaction) => setDrawer({ kind: 'edit', tx })} onSplit={setSplitTx} />
        )}
        {route === 'banks' && <Banks period={period} refreshKey={refreshKey} />}
        {route === 'split' && <Split me={me} />}
        {route === 'review' && (
          <Review refreshKey={refreshKey} onChanged={refresh} onFix={(item: ReviewItem) => setDrawer({ kind: 'review', item })} />
        )}
        {route === 'insights' && <Insights refreshKey={refreshKey} aiEnabled={ai} onAsk={() => setGemini(true)} />}
        {route === 'settings' && <Settings me={me} onAiChanged={setAi} />}
        {route === 'help' && <Help me={me} onMeChanged={onMeChanged} />}
      </main>

      {drawer && (
        <TxDrawer mode={drawer} categories={categories} creditCategories={creditCategories} banks={banks} aiEnabled={ai}
          onAddCategory={addCategory} onAddBank={addBank}
          onClose={() => setDrawer(null)} onSaved={() => { setDrawer(null); refresh(); }} />
      )}
      {gemini && <GeminiModal onClose={() => setGemini(false)} onDone={refresh} />}
      {splitTx && <SplitTransaction tx={splitTx} onClose={() => setSplitTx(null)} onDone={() => { setSplitTx(null); refresh(); }} />}
    </div>
  );
}

/** Previous / next day arrows around a date input; never goes past today. */
function DayPicker({ day, firstYear, onPick }: { day: string; firstYear: number; onPick: (iso: string) => void }) {
  const today = toIso(new Date());
  const shift = (n: number) => {
    const d = new Date(`${day}T00:00:00`);
    d.setDate(d.getDate() + n);
    onPick(toIso(d));
  };
  return (
    <div className="day-picker">
      <button className="icon-btn" aria-label="Previous day" onClick={() => shift(-1)}><ChevronLeft size={16} /></button>
      <input className="input" type="date" aria-label="Day" value={day} min={`${firstYear}-01-01`} max={today}
        onChange={(e) => e.target.value && onPick(e.target.value > today ? today : e.target.value)} />
      <button className="icon-btn" aria-label="Next day" disabled={day >= today} onClick={() => shift(1)}><ChevronRight size={16} /></button>
    </div>
  );
}

/** Year + month dropdowns; "All months" picks the whole year. A preset button clears them. */
function MonthPicker({ periodKey, firstYear, onPick }: {
  periodKey: PeriodKey; firstYear: number; onPick: (key: PeriodKey) => void;
}) {
  const now = new Date();
  const { year, month } = periodParts(periodKey);
  const lastYear = now.getFullYear();
  const years = Array.from({ length: Math.max(1, lastYear - Math.min(firstYear, lastYear) + 1) }, (_, i) => lastYear - i);
  const shownYear = year ?? lastYear;
  const pickMonth = (m: number | null) => onPick(m ? `m:${shownYear}-${String(m).padStart(2, '0')}` : `y:${shownYear}`);

  return (
    <div className={`month-picker ${year !== null ? 'on' : ''}`}>
      <select className="select" aria-label="Month" value={month ?? (year !== null ? 'all' : '')}
        onChange={(e) => pickMonth(e.target.value === 'all' ? null : Number(e.target.value))}>
        {year === null && <option value="" disabled>Month</option>}
        <option value="all">All months</option>
        {MONTHS.map((name, i) => (
          <option key={name} value={i + 1} disabled={shownYear === lastYear && i > now.getMonth()}>{name}</option>
        ))}
      </select>
      <select className="select" aria-label="Year" value={year ?? ''}
        onChange={(e) => {
          const y = Number(e.target.value);
          const m = month && !(y === lastYear && month > now.getMonth() + 1) ? month : null;
          onPick(m ? `m:${y}-${String(m).padStart(2, '0')}` : `y:${y}`);
        }}>
        {year === null && <option value="" disabled>Year</option>}
        {years.map((y) => <option key={y} value={y}>{y}</option>)}
      </select>
    </div>
  );
}
