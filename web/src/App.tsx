import { useCallback, useEffect, useState } from 'react';
import { CircleHelp, Inbox, Landmark, LayoutDashboard, LoaderCircle, LogOut, ReceiptText, Settings as SettingsIcon, Sparkles } from 'lucide-react';
import { api, setUnauthorizedHandler, type Me, type ReviewItem, type Transaction } from './api';
import { PERIODS, greeting, initials, period as makePeriod, rangeLabel, type PeriodKey } from './format';
import { GeminiModal } from './GeminiModal';
import { TxDrawer, type DrawerMode } from './TxDrawer';
import { Banks } from './pages/Banks';
import { Insights } from './pages/Insights';
import { Login } from './pages/Login';
import { Overview } from './pages/Overview';
import { Review } from './pages/Review';
import { Settings } from './pages/Settings';
import { Help } from './pages/Help';
import { Transactions } from './pages/Transactions';

type Route = 'overview' | 'transactions' | 'banks' | 'review' | 'insights' | 'settings' | 'help';
const ROUTES: Route[] = ['overview', 'transactions', 'banks', 'review', 'insights', 'settings', 'help'];

function parseHash(): { route: Route; category: string; bank: string; kind: string } {
  const [path, query] = window.location.hash.replace(/^#\/?/, '').split('?');
  const route = (ROUTES as string[]).includes(path) ? (path as Route) : 'overview';
  const params = new URLSearchParams(query);
  return { route, category: params.get('category') ?? '', bank: params.get('bank') ?? '', kind: params.get('kind') ?? '' };
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
  const [{ route, category, bank, kind }, setLocation] = useState(parseHash);
  const [periodKey, setPeriodKey] = useState<PeriodKey>(() => (localStorage.getItem('period') as PeriodKey) || 'month');
  const [refreshKey, setRefreshKey] = useState(0);
  const [ai, setAi] = useState(false);
  const [categories, setCategories] = useState<string[]>([]);
  const [banks, setBanks] = useState<string[]>([]);
  const [reviewCount, setReviewCount] = useState(0);
  const [drawer, setDrawer] = useState<DrawerMode | null>(null);
  const [gemini, setGemini] = useState(false);

  const period = makePeriod(PERIODS.some((p) => p.key === periodKey) ? periodKey : 'month');
  const refresh = useCallback(() => setRefreshKey((k) => k + 1), []);

  useEffect(() => {
    const h = () => setLocation(parseHash());
    window.addEventListener('hashchange', h);
    return () => window.removeEventListener('hashchange', h);
  }, []);
  useEffect(() => localStorage.setItem('period', periodKey), [periodKey]);
  useEffect(() => {
    api.health().then((h) => setAi(h.ai)).catch(() => {});
    api.categories().then((c) => setCategories(c.categories)).catch(() => {});
    api.banks().then((b) => setBanks(b.banks)).catch(() => {});
  }, []);
  useEffect(() => {
    api.review().then((r) => setReviewCount(r.items.length)).catch(() => {});
  }, [refreshKey]);

  const go = (r: string) => { window.location.hash = `/${r}`; };

  async function signOut() {
    await api.logout().catch(() => {});
    onSignedOut();
  }

  const first = me.name.split(/\s+/)[0];
  const titles: Record<Route, [string, string]> = {
    overview: [`${greeting()}, ${first}`, `Here's where your money went · ${rangeLabel(period.from, period.to)}`],
    transactions: ['Transactions', `Every spend, editable · ${rangeLabel(period.from, period.to)}`],
    banks: ['Banks', `Everything that left each account · ${rangeLabel(period.from, period.to)}`],
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
        {nav.map(({ id, label, icon: Icon, badge }) => (
          <button key={id} className={`nav-item ${route === id ? 'active' : ''}`} onClick={() => go(id)}>
            <Icon size={18} /> {label}
            {!!badge && <span className="badge badge-amber">{badge}</span>}
          </button>
        ))}

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
                  <button key={p.key} className={p.key === periodKey ? 'on' : ''} onClick={() => setPeriodKey(p.key)}>{p.label}</button>
                ))}
              </div>
            )}
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
          <Transactions key={`${category}|${bank}|${kind}`} period={period} refreshKey={refreshKey} categories={categories}
            initialCategory={category} initialBank={bank} initialKind={kind}
            aiEnabled={ai} onChanged={refresh} onAdd={() => setDrawer({ kind: 'add' })}
            onEdit={(tx: Transaction) => setDrawer({ kind: 'edit', tx })} />
        )}
        {route === 'banks' && <Banks period={period} refreshKey={refreshKey} />}
        {route === 'review' && (
          <Review refreshKey={refreshKey} onChanged={refresh} onFix={(item: ReviewItem) => setDrawer({ kind: 'review', item })} />
        )}
        {route === 'insights' && <Insights refreshKey={refreshKey} aiEnabled={ai} onAsk={() => setGemini(true)} />}
        {route === 'settings' && <Settings me={me} onAiChanged={setAi} />}
        {route === 'help' && <Help me={me} onMeChanged={onMeChanged} />}
      </main>

      {drawer && (
        <TxDrawer mode={drawer} categories={categories} banks={banks} aiEnabled={ai}
          onClose={() => setDrawer(null)} onSaved={() => { setDrawer(null); refresh(); }} />
      )}
      {gemini && <GeminiModal onClose={() => setGemini(false)} onDone={refresh} />}
    </div>
  );
}
