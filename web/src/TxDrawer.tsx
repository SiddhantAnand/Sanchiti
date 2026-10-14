import { useMemo, useState } from 'react';
import { ArrowDownLeft, ArrowUpRight, CircleAlert, CreditCard, Landmark, LoaderCircle, Smartphone, Sparkles, Wallet } from 'lucide-react';
import { api, ApiError, reviewParts, type Direction, type ReviewItem, type Transaction, type TransactionFields } from './api';
import { Drawer, usePrompt, useToast } from './ui';
import { CategoryIcon, NEW_CATEGORY } from './categories';
import { longDate, money, toIso } from './format';

const NEW_BANK = '__new_bank__';

export type DrawerMode =
  | { kind: 'add'; direction?: Direction }
  | { kind: 'edit'; tx: Transaction }
  | { kind: 'review'; item: ReviewItem };

const METHODS = [
  { value: 'UPI', label: 'UPI', icon: Smartphone },
  { value: 'CARD', label: 'Card', icon: CreditCard },
  { value: 'NETBANKING', label: 'Net banking', icon: Landmark },
  { value: 'CASH', label: 'Cash', icon: Wallet },
  { value: 'AUTOPAY', label: 'Autopay', icon: Sparkles },
];

const GEMINI = '';

const str = (v: unknown) => (v === null || v === undefined ? '' : String(v));

function isoDate(v: unknown) {
  const s = str(v).trim();
  if (/^\d{4}-\d{2}-\d{2}/.test(s)) return s.slice(0, 10);
  const m = s.match(/^(\d{2})[-/](\d{2})[-/](\d{4})$/);
  return m ? `${m[3]}-${m[2]}-${m[1]}` : '';
}

function initialFields(mode: DrawerMode): TransactionFields {
  if (mode.kind === 'edit') {
    const t = mode.tx;
    return {
      date: t.date, amount: String(t.amount), reason: t.reason, currency: t.currency, bankName: t.bank,
      paymentMethod: t.paymentMethod, cardLast4: t.cardLast4, upiRef: t.upiRef, note: t.note, direction: t.direction,
    };
  }
  if (mode.kind === 'review') {
    const p = reviewParts(mode.item.payload).fields;
    return {
      date: isoDate(p.date), amount: str(p.amount), reason: str(p.reason), currency: 'INR',
      bankName: str(p.bankName) || null, paymentMethod: str(p.paymentMethod).toUpperCase() || null,
      cardLast4: str(p.cardLast4) || null, upiRef: str(p.upiRef) || null, note: null,
      direction: p.direction === 'credit' ? 'credit' : 'debit',
    };
  }
  return {
    date: toIso(new Date()), amount: '', reason: '', currency: 'INR', bankName: null,
    paymentMethod: 'UPI', cardLast4: null, upiRef: null, note: null, direction: mode.direction ?? 'debit',
  };
}

export function TxDrawer(props: {
  mode: DrawerMode; categories: string[]; creditCategories: string[]; banks: string[]; aiEnabled: boolean;
  onAddCategory: (name: string, direction: Direction) => Promise<string>; onAddBank: (name: string) => Promise<string>;
  onClose: () => void; onSaved: () => void;
}) {
  const { mode } = props;
  const toast = useToast();
  const prompt = usePrompt();
  const initial = useMemo(() => initialFields(mode), [mode]);
  const initialCategory = mode.kind === 'edit' ? mode.tx.category ?? GEMINI : GEMINI;
  const [f, setF] = useState<TransactionFields>(initial);
  const [category, setCategory] = useState(initialCategory);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(mode.kind === 'review' ? mode.item.issue : null);
  const [duplicate, setDuplicate] = useState(false);

  const set = <K extends keyof TransactionFields>(k: K, v: TransactionFields[K]) => setF((x) => ({ ...x, [k]: v }));
  const blank = (s: string) => (s.trim() === '' ? null : s.trim());
  const credit = f.direction === 'credit';
  const options = credit ? props.creditCategories : props.categories;

  function setDirection(d: Direction) {
    set('direction', d);
    const fits = (d === 'credit' ? props.creditCategories : props.categories).includes(category);
    if (!fits) setCategory(GEMINI);
  }

  async function pickCategory(value: string) {
    if (value !== NEW_CATEGORY) return setCategory(value);
    const name = await prompt({
      title: 'New category', label: `Name it (for ${credit ? 'money in' : 'spending'})`,
      placeholder: credit ? 'e.g. Rent received, Freelance' : 'e.g. Pet care, Gym, Kids',
    });
    if (!name) return;
    try {
      setCategory(await props.onAddCategory(name, f.direction));
    } catch (e) {
      toast('err', (e as Error).message);
    }
  }

  async function pickBank(value: string) {
    if (value !== NEW_BANK) return set('bankName', value || null);
    const name = await prompt({ title: 'Add a bank', label: 'Bank name', placeholder: 'e.g. Axis Bank, Kotak Mahindra Bank' });
    if (!name) return;
    try {
      set('bankName', await props.onAddBank(name));
    } catch (e) {
      toast('err', (e as Error).message);
    }
  }

  const bankOptions = f.bankName && !props.banks.includes(f.bankName) ? [...props.banks, f.bankName] : props.banks;

  async function save(allowDuplicates = false) {
    setBusy(true);
    setError(null);
    const fields: TransactionFields = {
      ...f,
      reason: f.reason.trim(),
      amount: f.amount.trim(),
      cardLast4: f.paymentMethod === 'CARD' ? f.cardLast4 : null,
      upiRef: f.paymentMethod === 'UPI' ? f.upiRef : null,
    };
    try {
      let id: string | undefined;
      if (mode.kind === 'add') {
        const res = await api.createTransaction(fields, allowDuplicates);
        if (!res.inserted.length) {
          setDuplicate(true);
          return;
        }
        id = res.inserted[0].id;
      } else if (mode.kind === 'review') {
        id = (await api.resolveReview(mode.item.id, fields, allowDuplicates)).id;
      } else {
        const patch: Record<string, unknown> = {};
        for (const k of Object.keys(fields) as (keyof TransactionFields)[]) {
          if ((fields[k] ?? null) !== (initial[k] ?? null)) patch[k] = fields[k];
        }
        if (category !== initialCategory) patch.category = category || null;
        if (Object.keys(patch).length) await api.updateTransaction(mode.tx.id, patch);
        toast('ok', Object.keys(patch).length ? 'Transaction updated' : 'No changes to save');
        props.onSaved();
        return;
      }
      if (category && id) await api.updateTransaction(id, { category });
      toast('ok', mode.kind === 'review' ? 'Saved from review' : 'Transaction added');
      props.onSaved();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) setDuplicate(true);
      else setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const title = mode.kind === 'add' ? 'Add transaction' : mode.kind === 'edit' ? 'Edit transaction' : 'Fix & save';
  const sub =
    mode.kind === 'edit'
      ? `${longDate(mode.tx.date)} · ${money(mode.tx.amount, mode.tx.currency)}`
      : mode.kind === 'review'
        ? 'Correct the details the sender got wrong'
        : 'Record a spend or credit the bank feed missed';

  return (
    <Drawer
      title={title}
      sub={sub}
      onClose={props.onClose}
      foot={
        <>
          <button className="btn btn-ghost" onClick={props.onClose}>Cancel</button>
          <button className="btn btn-primary" disabled={busy} onClick={() => save(false)}>
            {busy && <LoaderCircle size={16} className="spinner" />}
            {mode.kind === 'edit' ? 'Save changes' : 'Save transaction'}
          </button>
        </>
      }
    >
      {error && (
        <div className={mode.kind === 'review' && error === mode.item.issue ? 'alert alert-amber' : 'alert'}>
          <CircleAlert size={16} />
          <span>{error}</span>
        </div>
      )}
      {duplicate && (
        <div className="alert alert-amber" style={{ alignItems: 'center' }}>
          <CircleAlert size={16} />
          <span style={{ flex: 1 }}>This looks like a transaction that is already saved.</span>
          <button className="btn btn-sm" disabled={busy} onClick={() => save(true)}>Save anyway</button>
        </div>
      )}

      <div className="segmented dir-toggle" role="radiogroup" aria-label="Direction">
        <button type="button" className={!credit ? 'on' : ''} aria-checked={!credit} role="radio" onClick={() => setDirection('debit')}>
          <ArrowUpRight size={15} /> Money out
        </button>
        <button type="button" className={credit ? 'on' : ''} aria-checked={credit} role="radio" onClick={() => setDirection('credit')}>
          <ArrowDownLeft size={15} /> Money in
        </button>
      </div>

      <label className="field">
        <span>Amount</span>
        <div className="amount-input">
          <span>₹</span>
          <input
            className="input num"
            inputMode="decimal"
            placeholder="0"
            value={f.amount}
            onChange={(e) => set('amount', e.target.value.replace(/[^\d.]/g, ''))}
            autoFocus={mode.kind === 'add'}
          />
        </div>
      </label>

      <div className="form-grid">
        <label className="field full">
          <span>{credit ? 'Received from / description' : 'Paid to / description'}</span>
          <input className="input" placeholder={credit ? 'e.g. Salary, Rahul, Amazon refund' : 'e.g. Swiggy, Rent, Chai at Tea Tower'}
            maxLength={200} value={f.reason} onChange={(e) => set('reason', e.target.value)} />
        </label>
        <label className="field">
          <span>Date</span>
          <input className="input" type="date" value={f.date} max={toIso(new Date())} onChange={(e) => set('date', e.target.value)} />
        </label>
        <label className="field">
          <span>Category</span>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <CategoryIcon category={category || null} size={40} />
            <select className="select" value={category} onChange={(e) => pickCategory(e.target.value)}>
              <option value={GEMINI}>{credit ? 'Automatic' : props.aiEnabled ? '✦ Let Gemini decide' : 'Uncategorized'}</option>
              {options.map((c) => <option key={c} value={c}>{c}</option>)}
              <option value={NEW_CATEGORY}>＋ New category…</option>
            </select>
          </div>
        </label>

        <div className="field full">
          <span>Payment method</span>
          <div className="method-pick">
            {METHODS.map(({ value, label, icon: Icon }) => (
              <button type="button" key={value} className={f.paymentMethod === value ? 'on' : ''}
                onClick={() => set('paymentMethod', f.paymentMethod === value ? null : value)}>
                <Icon size={14} /> {label}
              </button>
            ))}
          </div>
        </div>

        {f.paymentMethod === 'CARD' && (
          <label className="field">
            <span>Card last 4</span>
            <input className="input num" inputMode="numeric" maxLength={4} placeholder="1234"
              value={f.cardLast4 ?? ''} onChange={(e) => set('cardLast4', blank(e.target.value.replace(/\D/g, '')))} />
          </label>
        )}
        {f.paymentMethod === 'UPI' && (
          <label className="field">
            <span>UPI reference <span className="hint">optional</span></span>
            <input className="input num" maxLength={64} placeholder="12-digit UTR"
              value={f.upiRef ?? ''} onChange={(e) => set('upiRef', blank(e.target.value))} />
          </label>
        )}
        <label className="field">
          <span>Bank</span>
          <select className="select" value={f.bankName ?? ''} onChange={(e) => pickBank(e.target.value)}>
            <option value="">Not specified</option>
            {bankOptions.map((b) => (
              <option key={b} value={b}>{props.banks.includes(b) ? b : `${b} (unknown)`}</option>
            ))}
            <option value={NEW_BANK}>＋ Add bank…</option>
          </select>
        </label>
        <label className="field">
          <span>Currency</span>
          <input className="input" maxLength={3} value={f.currency} onChange={(e) => set('currency', e.target.value.toUpperCase())} />
        </label>

        <label className="field full">
          <span>Notes <span className="hint">anything worth remembering — who it was for, what it was</span></span>
          <textarea className="textarea" maxLength={500} placeholder="e.g. Split with Rahul, he owes ₹150"
            value={f.note ?? ''} onChange={(e) => set('note', e.target.value || null)} />
        </label>
      </div>

      {mode.kind === 'edit' && (
        <div className="meta-box">
          {mode.tx.categorySource === 'ai' && <span className="badge badge-gemini"><Sparkles size={12} /> Categorised by Gemini</span>}
          {mode.tx.categorySource === 'manual' && <span className="badge badge-violet">Category set by you</span>}
          {mode.tx.categorySource === 'rule' && <span className="badge">Category set automatically</span>}
          {mode.tx.upiRef && <span className="badge">UPI ref {mode.tx.upiRef}</span>}
          <span className="faint">Added {new Date(mode.tx.createdAt).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })}</span>
        </div>
      )}
      {mode.kind === 'review' && (
        <details>
          <summary>What the sender sent</summary>
          <pre className="raw">{JSON.stringify(mode.item.payload, null, 2)}</pre>
        </details>
      )}
    </Drawer>
  );
}
