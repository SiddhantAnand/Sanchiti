import { useEffect, useState } from 'react';
import { CircleCheck, Inbox, Trash, TriangleAlert, Wrench } from 'lucide-react';
import { api, reviewParts, type ReviewItem } from '../api';
import { money } from '../format';
import { Empty, Skeleton, useToast } from '../ui';

const FIELDS: [string, string][] = [
  ['date', 'Date'], ['amount', 'Amount'], ['reason', 'Description'], ['bankName', 'Bank'],
  ['paymentMethod', 'Method'], ['cardLast4', 'Card'], ['upiRef', 'UPI ref'],
];

function show(key: string, v: unknown) {
  if (v === null || v === undefined || v === '') return <span className="faint">missing</span>;
  if (key === 'amount' && typeof v === 'number') return <span className="num">{money(v)}</span>;
  return String(v);
}

export function Review(props: { refreshKey: number; onFix: (item: ReviewItem) => void; onChanged: () => void }) {
  const toast = useToast();
  const [items, setItems] = useState<ReviewItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.review().then((r) => (setItems(r.items), setError(null))).catch((e) => setError(e.message));
  }, [props.refreshKey]);

  async function discard(item: ReviewItem) {
    if (!window.confirm('Discard this record? It will not be saved as a transaction.')) return;
    try {
      await api.discardReview(item.id);
      setItems((xs) => xs?.filter((x) => x.id !== item.id) ?? null);
      toast('ok', 'Record discarded');
      props.onChanged();
    } catch (e) {
      toast('err', (e as Error).message);
    }
  }

  if (error) return <div className="alert">{error}</div>;
  if (!items) return <div className="stack">{[0, 1].map((i) => <div className="card" key={i}><Skeleton h={120} /></div>)}</div>;
  if (items.length === 0) {
    return (
      <div className="card">
        <Empty icon={<CircleCheck size={28} />} title="All clear">
          <p>Every bank SMS your phone sent was filed automatically. Messages that can't be read (no amount or payee,
            or an unknown bank) will wait here for you.</p>
        </Empty>
      </div>
    );
  }

  return (
    <div className="stack">
      <div className="alert alert-amber" style={{ alignItems: 'center' }}>
        <Inbox size={16} />
        <span>{items.length} record{items.length === 1 ? '' : 's'} could not be filed. Fix the details and save, or discard what isn't a real spend.</span>
      </div>
      {items.map((item) => {
        const { sms, fields: p } = reviewParts(item.payload);
        return (
          <div className="card review-card" key={item.id}>
            <div className="review-issue">
              <TriangleAlert size={16} />
              <span style={{ flex: 1 }}>{item.issue}</span>
              <span className="faint" style={{ fontSize: 12 }}>
                received {new Date(item.receivedAt).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })}
              </span>
            </div>
            <div className="review-body">
              {sms && <p className="muted" style={{ margin: '0 0 12px', whiteSpace: 'pre-wrap', fontSize: 13 }}>{sms}</p>}
              <div className="review-grid">
                {FIELDS.map(([key, label]) => (
                  <div className={`field ${key === 'reason' ? 'wide' : ''}`} key={key}>
                    <span>{label}</span>
                    <div style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis' }}>{show(key, p[key])}</div>
                  </div>
                ))}
              </div>
              <div className="review-foot">
                <span className="faint" style={{ fontSize: 12 }}>#{item.id}</span>
                <div style={{ display: 'flex', gap: 8 }}>
                  <button className="btn btn-danger btn-sm" onClick={() => discard(item)}><Trash size={14} /> Discard</button>
                  <button className="btn btn-primary btn-sm" onClick={() => props.onFix(item)}><Wrench size={14} /> Fix &amp; save</button>
                </div>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
