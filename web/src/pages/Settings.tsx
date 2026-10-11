import { useEffect, useState, type FormEvent } from 'react';
import { Download, HardDriveDownload, KeyRound, Link, LoaderCircle, Lock, Sparkles, UserPlus, Users } from 'lucide-react';
import { api, type AiStatus, type Me, type UserRow } from '../api';
import { CopyField, useToast } from '../ui';

export function Settings({ me, onAiChanged }: { me: Me; onAiChanged: (enabled: boolean) => void }) {
  return (
    <div className="stack">
      <div className="grid row-1-1">
        <Password />
        <Backup />
      </div>
      {me.admin && <Gemini onAiChanged={onAiChanged} />}
      {me.admin && <People />}
    </div>
  );
}

function Backup() {
  return (
    <div className="card">
      <div className="card-head">
        <div>
          <h3 className="card-title"><HardDriveDownload size={16} /> Back up your data</h3>
          <div className="card-sub">One JSON file with all your transactions, notes, categories, review items and insights</div>
        </div>
      </div>
      <div className="stack" style={{ gap: 14 }}>
        <p className="muted" style={{ margin: 0, fontSize: 13 }}>
          Only your own data is included. Passwords and keys are never part of it. Keep a copy somewhere safe,
          e.g. once a month.
        </p>
        <div>
          <a className="btn btn-primary btn-sm" href="/api/account/export" download>
            <Download size={14} /> Download my data
          </a>
        </div>
      </div>
    </div>
  );
}

const SOURCE_TEXT: Record<AiStatus['source'], string> = {
  settings: 'Using the key saved here',
  environment: 'Using GEMINI_API_KEY from the server environment',
  none: 'No key yet — categorising and weekly insights are off',
};

function Gemini({ onAiChanged }: { onAiChanged: (enabled: boolean) => void }) {
  const toast = useToast();
  const [status, setStatus] = useState<AiStatus | null>(null);
  const [key, setKey] = useState('');
  const [model, setModel] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.aiStatus().then((s) => (setStatus(s), setModel(s.model))).catch((e) => setError(e.message));
  }, []);

  function applied(s: AiStatus, message: string) {
    setStatus(s);
    setModel(s.model);
    setKey('');
    onAiChanged(s.enabled);
    toast('ok', message);
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      applied(await api.saveAi(key.trim(), model.trim()), 'Gemini key checked and saved');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!window.confirm('Remove the saved Gemini key?')) return;
    setBusy(true);
    try {
      applied(await api.clearAi(), 'Saved key removed');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card" onSubmit={save}>
      <div className="card-head">
        <div>
          <h3 className="card-title"><Sparkles size={16} /> Gemini</h3>
          <div className="card-sub">One key for everyone on this server. Get a free one at aistudio.google.com/apikey</div>
        </div>
        {status && <span className={`badge ${status.enabled ? 'badge-teal' : 'badge-amber'}`}>{status.enabled ? 'Connected' : 'Off'}</span>}
      </div>
      <div className="stack" style={{ gap: 14 }}>
        {error && <div className="alert">{error}</div>}
        {status && (
          <p className="muted" style={{ margin: 0, fontSize: 13 }}>
            {SOURCE_TEXT[status.source]}{status.keyHint ? ` (key ending ${status.keyHint.slice(1)})` : ''}.
          </p>
        )}
        <div className="grid row-1-1" style={{ gap: 14 }}>
          <label className="field"><span>{status?.source === 'settings' ? 'Replace API key' : 'API key'}</span>
            <input className="input" type="password" autoComplete="off" spellCheck={false} required
              placeholder="Paste your Gemini API key" value={key} onChange={(e) => setKey(e.target.value)} />
          </label>
          <label className="field"><span>Model</span>
            <input className="input" spellCheck={false} value={model} onChange={(e) => setModel(e.target.value)} />
          </label>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn-primary btn-sm" disabled={busy || !key.trim()}>
            {busy ? <LoaderCircle size={14} className="spinner" /> : <KeyRound size={14} />} Check &amp; save
          </button>
          {status?.source === 'settings' && (
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy} onClick={remove}>Remove saved key</button>
          )}
        </div>
      </div>
    </form>
  );
}

function Password() {
  const toast = useToast();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (next !== confirm) {
      setError('The new passwords do not match');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.changePassword(current, next);
      setCurrent('');
      setNext('');
      setConfirm('');
      toast('ok', 'Password changed; other browsers were signed out');
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card" onSubmit={submit}>
      <div className="card-head">
        <div>
          <h3 className="card-title"><Lock size={16} /> Password</h3>
          <div className="card-sub">10-64 characters. Changing it signs you out everywhere else.</div>
        </div>
      </div>
      <div className="stack" style={{ gap: 14 }}>
        {error && <div className="alert">{error}</div>}
        <label className="field"><span>Current password</span>
          <input className="input" type="password" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />
        </label>
        <label className="field"><span>New password</span>
          <input className="input" type="password" autoComplete="new-password" required minLength={10} maxLength={64} value={next} onChange={(e) => setNext(e.target.value)} />
        </label>
        <label className="field"><span>Repeat new password</span>
          <input className="input" type="password" autoComplete="new-password" required minLength={10} maxLength={64} value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        </label>
        <div>
          <button className="btn btn-primary btn-sm" disabled={busy}>
            {busy && <LoaderCircle size={14} className="spinner" />} Change password
          </button>
        </div>
      </div>
    </form>
  );
}

function People() {
  const toast = useToast();
  const [users, setUsers] = useState<UserRow[] | null>(null);
  const [invite, setInvite] = useState<{ link: string; expiresAt: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.users().then((r) => setUsers(r.users)).catch((e) => toast('err', e.message));
  }, [toast]);

  async function createInvite() {
    setBusy(true);
    try {
      const { code, expiresAt } = await api.createInvite();
      setInvite({ link: `${window.location.origin}/#/signup?invite=${encodeURIComponent(code)}`, expiresAt });
    } catch (e) {
      toast('err', (e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card">
      <div className="card-head">
        <div>
          <h3 className="card-title"><Users size={16} /> People</h3>
          <div className="card-sub">Everyone has their own login ID, phone key and dashboard; nobody sees anyone else's data</div>
        </div>
        <button className="btn btn-primary btn-sm" disabled={busy} onClick={createInvite}>
          {busy ? <LoaderCircle size={14} className="spinner" /> : <UserPlus size={14} />} Invite someone
        </button>
      </div>
      <div className="stack" style={{ gap: 14 }}>
        {invite && (
          <>
            <CopyField label="Invite link" value={invite.link} />
            <div className="alert alert-amber">
              <Link size={16} />
              <span>Works once, until {new Date(invite.expiresAt).toLocaleDateString('en-IN', { dateStyle: 'medium' })}.
                Send it privately; whoever opens it first picks their own login ID and password.</span>
            </div>
          </>
        )}
        <div className="mini-list">
          {(users ?? []).map((u) => (
            <div className="mini-row" key={u.login}>
              <span style={{ flex: 1 }}><b>{u.name}</b> <span className="faint">@{u.login}</span></span>
              {u.admin && <span className="badge badge-violet">admin</span>}
              <span className="faint" style={{ fontSize: 12 }}>
                joined {new Date(u.createdAt).toLocaleDateString('en-IN', { dateStyle: 'medium' })}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
