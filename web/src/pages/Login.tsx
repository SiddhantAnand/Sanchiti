import { useState, type FormEvent } from 'react';
import { AtSign, CircleAlert, Eye, EyeOff, KeyRound, Lock, LoaderCircle, ShieldCheck, Sparkles, User, Utensils, ShoppingBag, Car } from 'lucide-react';
import { api, type Me } from '../api';

// An admin's invite link looks like /#/signup?invite=CODE.
function inviteFromLink() {
  const [path, query] = window.location.hash.replace(/^#\/?/, '').split('?');
  return path === 'signup' ? new URLSearchParams(query).get('invite') ?? '' : null;
}

export function Login({ onSignedIn }: { onSignedIn: (me: Me) => void }) {
  const linkInvite = inviteFromLink();
  const [mode, setMode] = useState<'in' | 'up'>(linkInvite === null ? 'in' : 'up');
  const [invite, setInvite] = useState(linkInvite ?? '');
  const [login, setLogin] = useState('');
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [reveal, setReveal] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const me = mode === 'in'
        ? await api.login(login, password)
        : await api.signup({ invite: invite.trim(), login, name, password });
      if (mode === 'up') window.location.hash = '/overview';
      onSignedIn(me);
    } catch (err) {
      setError((err as Error).message);
      setPassword('');
    } finally {
      setBusy(false);
    }
  }

  function switchMode() {
    setMode((m) => (m === 'in' ? 'up' : 'in'));
    setError(null);
  }

  return (
    <div className="login">
      <section className="login-hero" aria-hidden="true">
        <div className="brand" style={{ padding: 0 }}>
          <span className="brand-mark">₹</span>
          <div><div className="brand-name">Sanchiti</div><div className="brand-sub">Personal finance</div></div>
        </div>

        <div>
          <h1 className="hero-title">Your money, <em>finally understood.</em></h1>
          <p className="hero-sub">Every UPI ping and card swipe, sorted into categories by Gemini — with a weekly read on
            what changed and where you can save.</p>

          <div className="preview">
            <div className="float-card float-a">
              <div className="faint" style={{ fontSize: 12 }}>Spent this month</div>
              <div className="num" style={{ fontSize: 26, fontWeight: 800, margin: '4px 0 10px' }}>₹38,240</div>
              <svg viewBox="0 0 240 54" width="100%" height="54">
                <defs>
                  <linearGradient id="hero-g" x1="0" x2="0" y1="0" y2="1">
                    <stop offset="0" stopColor="var(--primary)" stopOpacity=".5" /><stop offset="1" stopColor="var(--primary)" stopOpacity="0" />
                  </linearGradient>
                </defs>
                <path d="M0 40 C20 34 30 44 50 30 S80 12 100 26 S140 44 160 22 S200 8 240 16 V54 H0Z" fill="url(#hero-g)" />
                <path d="M0 40 C20 34 30 44 50 30 S80 12 100 26 S140 44 160 22 S200 8 240 16" fill="none" stroke="var(--primary)" strokeWidth="2.5" />
              </svg>
            </div>
            <div className="float-card float-b">
              <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginBottom: 10 }}>
                <span className="gemini-mark" style={{ width: 28, height: 28 }}><Sparkles size={14} /></span>
                <b style={{ fontSize: 13 }}>Gemini noticed</b>
              </div>
              <div className="muted" style={{ fontSize: 12.5, lineHeight: 1.5 }}>Food delivery is up 32% on last week — three late-night orders after 11 pm.</div>
            </div>
            <div className="float-card float-c">
              {[
                { icon: Utensils, c: '#FF8A5B', n: 'Food & Dining', v: '₹9,410' },
                { icon: ShoppingBag, c: '#C77DFF', n: 'Shopping', v: '₹6,120' },
                { icon: Car, c: '#4FB3FF', n: 'Transport', v: '₹2,380' },
              ].map(({ icon: Icon, c, n, v }) => (
                <div key={n} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '5px 0', fontSize: 12.5 }}>
                  <span className="cat-icon" style={{ width: 26, height: 26, color: c, background: `${c}22` }}><Icon size={13} /></span>
                  <span style={{ flex: 1 }}>{n}</span><b className="num">{v}</b>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div className="hero-foot">
          <span><ShieldCheck size={14} /> Private to you</span>
          <span><Lock size={14} /> Runs on your own server</span>
        </div>
      </section>

      <section className="login-panel">
        <div className="login-box">
          <h2>{mode === 'in' ? 'Welcome back' : 'Create your account'}</h2>
          <p className="muted" style={{ margin: 0 }}>
            {mode === 'in' ? 'Sign in to review your spending.' : 'You need an invite code from the admin.'}
          </p>
          <form onSubmit={submit}>
            {error && <div className="alert"><CircleAlert size={16} /><span>{error}</span></div>}
            {mode === 'up' && (
              <label className="field">
                <span>Invite code</span>
                <div className="input-icon">
                  <KeyRound size={16} />
                  <input className="input" style={{ height: 46 }} autoComplete="off" required
                    value={invite} onChange={(e) => setInvite(e.target.value)} autoFocus={!invite} />
                </div>
              </label>
            )}
            <label className="field">
              <span>Login ID</span>
              <div className="input-icon">
                <AtSign size={16} />
                <input className="input" style={{ height: 46 }} autoComplete="username" required
                  placeholder={mode === 'up' ? 'e.g. priya.k (3-32 letters, digits, . _ -)' : 'Your login ID'}
                  autoCapitalize="none" spellCheck={false}
                  value={login} onChange={(e) => setLogin(e.target.value)} autoFocus={mode === 'in' || !!invite} />
              </div>
            </label>
            {mode === 'up' && (
              <label className="field">
                <span>Your name</span>
                <div className="input-icon">
                  <User size={16} />
                  <input className="input" style={{ height: 46 }} autoComplete="name" required maxLength={60}
                    placeholder="As it appears on bank transfers" value={name} onChange={(e) => setName(e.target.value)} />
                </div>
              </label>
            )}
            <label className="field">
              <span>Password</span>
              <div className="input-icon">
                <Lock size={16} />
                <input className="input" style={{ height: 46, paddingRight: 44 }} type={reveal ? 'text' : 'password'}
                  autoComplete={mode === 'in' ? 'current-password' : 'new-password'}
                  minLength={mode === 'up' ? 10 : undefined} maxLength={64}
                  placeholder={mode === 'up' ? 'At least 10 characters' : undefined}
                  required value={password} onChange={(e) => setPassword(e.target.value)} />
                <button type="button" className="icon-btn input-affix" onClick={() => setReveal((r) => !r)} aria-label={reveal ? 'Hide password' : 'Show password'}>
                  {reveal ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
            </label>
            <button className="btn btn-primary btn-lg" disabled={busy}>
              {busy && <LoaderCircle size={17} className="spinner" />} {mode === 'in' ? 'Sign in' : 'Create account'}
            </button>
            <button type="button" className="btn btn-ghost" onClick={switchMode}>
              {mode === 'in' ? 'Have an invite? Create an account' : 'Already have an account? Sign in'}
            </button>
          </form>
        </div>
      </section>
    </div>
  );
}
