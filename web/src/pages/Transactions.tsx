import { useCallback, useEffect, useMemo, useState } from 'react';
import { Funnel, Landmark, LoaderCircle, Pencil, Plus, ReceiptText, Search, Sparkles, Trash, X } from 'lucide-react';
import { api, type Transaction } from '../api';
import { CategoryIcon, UNCATEGORIZED, categoryMeta } from '../categories';
import { dayHeading, methodLabel, money, rangeLabel, type Period } from '../format';
import { Empty, Skeleton, useToast } from '../ui';
import { KINDS } from './Banks';

const PAGE = 50;

export function Transactions(props: {
  period: Period; refreshKey: number; categories: string[]; initialCategory: string; initialBank: string;
  initialKind: string; aiEnabled: boolean; onEdit: (tx: Transaction) => void; onAdd: () => void; onChanged: () => void;
}) {
  const { period } = props;
  const toast = useToast();
  const [query, setQuery] = useState('');
  const [q, setQ] = useState('');
  const [category, setCategory] = useState(props.initialCategory);
  const [bank] = useState(props.initialBank);
  const [kind] = useState(props.initialKind);
  const [items, setItems] = useState<Transaction[] | null>(null);
  const [total, setTotal] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const t = setTimeout(() => setQ(query.trim()), 250);
    return () => clearTimeout(t);
  }, [query]);

  const filter = useMemo(
    () => ({
      from: period.from, to: period.to, category: category || undefined, q: q || undefined,
      bank: bank || undefined, kind: kind || undefined,
    }),
    [period.from, period.to, category, q, bank, kind],
  );

  function clearBankFilter() {
    window.location.hash = category ? `/transactions?category=${encodeURIComponent(category)}` : '/transactions';
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
      toast('ok', value ? `${updated.merchant ?? 'Payee'} → ${value}` : `Gemini picked ${updated.category ?? 'nothing yet'}`);
      reload();
      props.onChanged();
    } catch (e) {
      toast('err', (e as Error).message);
    }
  }

  async function remove(tx: Transaction) {
    if (!window.confirm(`Delete ${money(tx.amount, tx.currency)} to ${tx.merchant ?? tx.reason}? This cannot be undone.`)) return;
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

  return (
    <div>
      <div className="toolbar">
        <div className="input-icon search">
          <Search size={16} />
          <input className="input" placeholder="Search payee, note or amount (2500, >1000, 500-2000)" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        <div className="input-icon">
          <Funnel size={15} />
          <select className="select input" style={{ paddingLeft: 36 }} value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">All categories</option>
            <option value={UNCATEGORIZED}>Uncategorised only</option>
            {props.categories.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
        </div>
        {(bank || kind) && (
          <button className="btn btn-sm" onClick={clearBankFilter} title="Clear this filter">
            <Landmark size={14} /> {[bank, KINDS.find((k) => k.key === kind)?.label].filter(Boolean).join(' · ')} <X size={14} />
          </button>
        )}
        <div style={{ marginLeft: 'auto' }} />
        <button className="btn btn-primary" onClick={props.onAdd}><Plus size={16} /> Add transaction</button>
      </div>

      <div className="card tx-card">
        {error && <div className="alert" style={{ margin: 16 }}>{error}</div>}
        {!items && !error && (
          <div style={{ padding: 22, display: 'flex', flexDirection: 'column', gap: 16 }}>
            {Array.from({ length: 7 }, (_, i) => <Skeleton key={i} h={36} />)}
          </div>
        )}
        {items && items.length === 0 && (
          <Empty icon={<ReceiptText size={28} />} title="No transactions found">
            <p>{q || category || bank || kind ? 'Nothing matches these filters.' : `Nothing recorded between ${rangeLabel(period.from, period.to)}.`}</p>
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
                      style={{ background: `${color}1F`, color: t.category ? color : 'var(--text-2)' }}
                      onChange={(e) => changeCategory(t, e.target.value)}
                    >
                      <option value="">{props.aiEnabled ? '✦ Let Gemini decide' : UNCATEGORIZED}</option>
                      {props.categories.map((c) => <option key={c} value={c}>{c}</option>)}
                    </select>
                    {t.categorySource === 'ai' && <Sparkles size={12} style={{ color: '#b9a8ff', flex: 'none' }} aria-label="Categorised by Gemini" />}
                  </div>
                  <div className="cell-muted col-method">{methodLabel(t.paymentMethod, t.cardLast4) ?? '—'}</div>
                  <div className="cell-muted col-bank">{t.bank ?? '—'}</div>
                  <div className="amount">{money(t.amount, t.currency)}</div>
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
            <span>Showing {items.length} of {total} · {money(shownTotal)} shown</span>
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
