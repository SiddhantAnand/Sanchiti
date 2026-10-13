import { useCallback, useEffect, useMemo, useState } from 'react';
import { ArrowDownLeft, ArrowUpRight, Funnel, Landmark, LoaderCircle, Pencil, Plus, ReceiptText, Search, Sparkles, Trash, X } from 'lucide-react';
import { api, type Direction, type Transaction } from '../api';
import { CategoryIcon, UNCATEGORIZED, categoryMeta } from '../categories';
import { dayHeading, methodLabel, money, rangeLabel, type Period } from '../format';
import { Empty, Skeleton, useToast } from '../ui';
import { IN_KINDS, KINDS } from './Banks';

const PAGE = 50;

export function Transactions(props: {
  period: Period; refreshKey: number; categories: string[]; creditCategories: string[]; initialCategory: string;
  initialBank: string; initialKind: string; initialDirection: Direction; aiEnabled: boolean;
  onEdit: (tx: Transaction) => void; onAdd: (direction: Direction) => void; onChanged: () => void;
}) {
  const { period } = props;
  const toast = useToast();
  const [query, setQuery] = useState('');
  const [q, setQ] = useState('');
  const [direction, setDirection] = useState<Direction>(props.initialDirection);
  const [category, setCategory] = useState(props.initialCategory);
  const [bank] = useState(props.initialBank);
  const [kind] = useState(props.initialKind);
  const [items, setItems] = useState<Transaction[] | null>(null);
  const [total, setTotal] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const credit = direction === 'credit';
  const options = credit ? props.creditCategories : props.categories;

  useEffect(() => {
    const t = setTimeout(() => setQ(query.trim()), 250);
    return () => clearTimeout(t);
  }, [query]);

  const filter = useMemo(
    () => ({
      from: period.from, to: period.to, category: category || undefined, q: q || undefined,
      bank: bank || undefined, kind: kind || undefined, direction,
    }),
    [period.from, period.to, category, q, bank, kind, direction],
  );

  function clearBankFilter() {
    const params = new URLSearchParams();
    if (category) params.set('category', category);
    if (credit) params.set('direction', 'credit');
    const s = params.toString();
    window.location.hash = `/transactions${s ? `?${s}` : ''}`;
  }

  function switchTab(d: Direction) {
    if (d === direction) return;
    if (bank || kind) {
      window.location.hash = d === 'credit' ? '/transactions?direction=credit' : '/transactions';
      return;
    }
    setDirection(d);
    setCategory('');
  }

  const load = useCallback(
    async (offset: number, limit = PAGE) => {
      const page = await api.transactions({ ...filter, limit, offset });
      setTotal(page.total);
      setItems((prev) => (offset === 0 ? page.items : [...(prev ?? []), ...page.items]));
      setError(null);
    },
    [filter],
  );

  useEffect(() => {
    setItems(null);
    load(0).catch((e) => setError(e.message));
  }, [load, props.refreshKey]);

  const reload = () => load(0, Math.max(PAGE, items?.length ?? 0)).catch((e) => setError(e.message));

  async function changeCategory(tx: Transaction, value: string) {
    try {
      const updated = await api.updateTransaction(tx.id, { category: value || null });
      const auto = tx.direction === 'credit' ? `Set automatically: ${updated.category ?? 'Received'}` : `Gemini picked ${updated.category ?? 'nothing yet'}`;
      toast('ok', value ? `${updated.merchant ?? (tx.direction === 'credit' ? 'Sender' : 'Payee')} → ${value}` : auto);
      reload();
      props.onChanged();
    } catch (e) {
      toast('err', (e as Error).message);
    }
  }

  async function remove(tx: Transaction) {
    if (!window.confirm(`Delete ${money(tx.amount, tx.currency)} ${tx.direction === 'credit' ? 'from' : 'to'} ${tx.merchant ?? tx.reason}? This cannot be undone.`)) return;
    try {
      await api.deleteTransaction(tx.id);
      toast('ok', 'Transaction deleted');
      reload();
      props.onChanged();
    } catch (e) {
      toast('err', (e as Error).message);
    }
  }

  const groups = useMemo(() => {
    const map = new Map<string, Transaction[]>();
    for (const t of items ?? []) map.set(t.date, [...(map.get(t.date) ?? []), t]);
    return [...map.entries()];
  }, [items]);

  const shownTotal = (items ?? []).filter((t) => t.currency === 'INR').reduce((s, t) => s + t.amount, 0);
  const kindLabel = (credit ? IN_KINDS : KINDS).find((k) => k.key === kind)?.label;

  return (
    <div>
      <div className="toolbar">
        <div className="segmented tabs" role="tablist" aria-label="Debits or credits">
          <button role="tab" aria-selected={!credit} className={!credit ? 'on' : ''} onClick={() => switchTab('debit')}>
            <ArrowUpRight size={15} /> Debits
          </button>
          <button role="tab" aria-selected={credit} className={credit ? 'on' : ''} onClick={() => switchTab('credit')}>
            <ArrowDownLeft size={15} /> Credits
          </button>
        </div>
        <div className="input-icon search">
          <Search size={16} />
          <input className="input" placeholder={`Search ${credit ? 'sender' : 'payee'}, note or amount (2500, >1000, 500-2000)`} value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        <div className="input-icon">
          <Funnel size={15} />
          <select className="select input" style={{ paddingLeft: 36 }} value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">All categories</option>
            {!credit && <option value={UNCATEGORIZED}>Uncategorised only</option>}
            {options.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
        </div>
        {(bank || kind) && (
          <button className="btn btn-sm" onClick={clearBankFilter} title="Clear this filter">
            <Landmark size={14} /> {[bank, kindLabel].filter(Boolean).join(' · ')} <X size={14} />
          </button>
        )}
        <div style={{ marginLeft: 'auto' }} />
        <button className="btn btn-primary" onClick={() => props.onAdd(direction)}>
          <Plus size={16} /> {credit ? 'Add credit' : 'Add transaction'}
        </button>
      </div>

      <div className="card tx-card">
        {error && <div className="alert" style={{ margin: 16 }}>{error}</div>}
        {!items && !error && (
          <div style={{ padding: 22, display: 'flex', flexDirection: 'column', gap: 16 }}>
            {Array.from({ length: 7 }, (_, i) => <Skeleton key={i} h={36} />)}
          </div>
        )}
        {items && items.length === 0 && (
          <Empty icon={<ReceiptText size={28} />} title={credit ? 'No credits found' : 'No transactions found'}>
            <p>
              {q || category || bank || kind
                ? 'Nothing matches these filters.'
                : credit
                  ? `No money came in between ${rangeLabel(period.from, period.to)}. Credits arrive once your Shortcut also sends “credited” messages (see Help).`
                  : `Nothing recorded between ${rangeLabel(period.from, period.to)}.`}
            </p>
          </Empty>
        )}
        {groups.map(([date, txs]) => (
          <section key={date}>
            <div className="day-head">
              <span>{dayHeading(date)}</span>
              <span className="num">{money(txs.filter((t) => t.currency === 'INR').reduce((s, t) => s + t.amount, 0))}</span>
            </div>
            {txs.map((t) => {
              const color = categoryMeta(t.category).color;
              const payee = t.merchant ?? t.reason;
              return (
                <div className="tx-row" key={t.id} onClick={() => props.onEdit(t)}>
                  <CategoryIcon category={t.category} size={38} />
                  <div style={{ minWidth: 0 }}>
                    <div className="payee">{payee}</div>
                    {t.note ? <div className="note">“{t.note}”</div> : payee !== t.reason && <div className="reason">{t.reason}</div>}
                  </div>
                  <div className="col-cat" onClick={(e) => e.stopPropagation()}>
                    <select
                      className="chip-select"
                      aria-label="Category"
                      value={t.category ?? ''}
                      style={{ background: `${color}1F`, color: t.category ? `color-mix(in srgb, ${color} var(--chip-ink), var(--text))` : 'var(--text-2)' }}
                      onChange={(e) => changeCategory(t, e.target.value)}
                    >
                      <option value="">{credit ? 'Automatic' : props.aiEnabled ? '✦ Let Gemini decide' : UNCATEGORIZED}</option>
                      {options.map((c) => <option key={c} value={c}>{c}</option>)}
                    </select>
                    {t.categorySource === 'ai' && <Sparkles size={12} style={{ color: 'var(--gemini-text)', flex: 'none' }} aria-label="Categorised by Gemini" />}
                  </div>
                  <div className="cell-muted col-method">{methodLabel(t.paymentMethod, t.cardLast4) ?? '—'}</div>
                  <div className="cell-muted col-bank">{t.bank ?? '—'}</div>
                  <div className={`amount ${credit ? 'credit' : ''}`}>{credit ? '+' : ''}{money(t.amount, t.currency)}</div>
                  <div className="actions" onClick={(e) => e.stopPropagation()}>
                    <button className="icon-btn" aria-label="Edit" onClick={() => props.onEdit(t)}><Pencil size={15} /></button>
                    <button className="icon-btn danger" aria-label="Delete" onClick={() => remove(t)}><Trash size={15} /></button>
                  </div>
                </div>
              );
            })}
          </section>
        ))}
        {items && items.length > 0 && (
          <div className="list-foot">
            <span>Showing {items.length} of {total} · {money(shownTotal)} {credit ? 'received' : 'shown'}</span>
            {items.length < total && (
              <button className="btn btn-sm" disabled={loadingMore}
                onClick={() => { setLoadingMore(true); load(items.length).catch((e) => setError(e.message)).finally(() => setLoadingMore(false)); }}>
                {loadingMore && <LoaderCircle size={14} className="spinner" />} Load more
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
