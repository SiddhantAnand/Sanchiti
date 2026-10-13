import { useEffect, useState } from 'react';
import { Landmark } from 'lucide-react';
import { api, type Summary } from '../api';
import { money, moneyWhole, rangeLabel, type Period } from '../format';
import { Empty, Skeleton } from '../ui';

type BankRow = Summary['deductedByBank'][number];

export const KINDS = [
  { key: 'spent', label: 'Spending', color: 'var(--primary)' },
  { key: 'invested', label: 'Investments', color: '#34D399' },
  { key: 'ownTransfers', label: 'Own accounts & card bills', color: '#60A5FA' },
] as const;

function openTransactions(bank: string, kind: string) {
  window.location.hash = `/transactions?${new URLSearchParams({ bank, kind })}`;
}

const pct = (part: number, whole: number) => (whole ? Math.round((part / whole) * 1000) / 10 : 0);

export function Banks({ period, refreshKey }: { period: Period; refreshKey: number }) {
  const [rows, setRows] = useState<BankRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setRows(null);
    api.summary(period.from, period.to)
      .then((s) => live && (setRows(s.deductedByBank), setError(null)))
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
  }, [period.from, period.to, refreshKey]);

  if (error) return <div className="alert">{error}</div>;
  if (!rows) return <div className="card"><Skeleton h={220} /></div>;
  if (rows.length === 0) {
    return (
      <div className="card">
        <Empty icon={<Landmark size={28} />} title="No debits in this period">
          <p>Nothing left any account between {rangeLabel(period.from, period.to)}.</p>
        </Empty>
      </div>
    );
  }

  const total = rows.reduce((s, r) => s + r.total, 0);
  const sum = (key: (typeof KINDS)[number]['key']) => rows.reduce((s, r) => s + r[key], 0);

  return (
    <div className="stack">
      <div className="grid kpis">
        <div className="card kpi kpi-hero">
          <span className="kpi-label"><span className="kpi-icon" style={{ background: 'var(--primary-soft)', color: 'var(--primary)' }}><Landmark size={16} /></span>Total out</span>
          <div className="kpi-value">{moneyWhole(total)}</div>
          <div className="kpi-foot">{rows.reduce((s, r) => s + r.count, 0)} debits across {rows.length} bank{rows.length === 1 ? '' : 's'}</div>
        </div>
        {KINDS.map((p) => (
          <div className="card kpi" key={p.key}>
            <span className="kpi-label"><span className="dot" style={{ background: p.color }} />{p.label}</span>
            <div className="kpi-value">{moneyWhole(sum(p.key))}</div>
            <div className="kpi-foot">{pct(sum(p.key), total)}% of everything debited</div>
          </div>
        ))}
      </div>

      <div className="card">
        <div className="card-head">
          <div>
            <h3 className="card-title">Money out by bank</h3>
            <div className="card-sub">Every debit, including investments, self-transfers and card bills. Hover a bar for details.</div>
          </div>
        </div>
        <div className="stack" style={{ gap: 22 }}>
          {rows.map((r) => <Bank key={r.bank} row={r} />)}
        </div>
      </div>
    </div>
  );
}

function Bank({ row }: { row: BankRow }) {
  const [hover, setHover] = useState<{ key: (typeof KINDS)[number]['key']; center: number } | null>(null);
  const parts = KINDS.filter((p) => row[p.key] > 0);
  const shown = parts.find((p) => p.key === hover?.key);
  let start = 0;

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
        <b style={{ flex: 1 }}>{row.bank} <span className="faint" style={{ fontWeight: 400 }}>· {row.count} debit{row.count === 1 ? '' : 's'}</span></b>
        <span className="num" style={{ fontWeight: 700 }}>{money(row.total)}</span>
      </div>
      <div style={{ position: 'relative' }}>
        <div className="split" onMouseLeave={() => setHover(null)}>
          {parts.map((p) => {
            const width = (row[p.key] / row.total) * 100;
            const center = start + width / 2;
            start += width;
            return (
              <i key={p.key} role="link" aria-label={`${p.label} ${money(row[p.key])}: show transactions`}
                style={{ width: `${width}%`, background: p.color, cursor: 'pointer', opacity: hover && hover.key !== p.key ? 0.45 : 1 }}
                onMouseEnter={() => setHover({ key: p.key, center })} onClick={() => openTransactions(row.bank, p.key)} />
            );
          })}
        </div>
        {hover && shown && (
          <div className="chart-tip" style={{
            position: 'absolute', bottom: 'calc(100% + 2px)', left: `${Math.min(Math.max(hover.center, 8), 92)}%`,
            transform: 'translateX(-50%)', pointerEvents: 'none', whiteSpace: 'nowrap', zIndex: 5,
          }}>
            <div className="t">{row.bank}</div>
            <div className="r">
              <span><span className="dot" style={{ background: shown.color, marginRight: 6 }} />{shown.label}</span>
              <b className="num">{money(row[shown.key])}</b>
            </div>
            <div className="faint" style={{ marginTop: 4, fontSize: 11.5 }}>{pct(row[shown.key], row.total)}% of this bank's debits · click to list them</div>
          </div>
        )}
      </div>
      <div className="faint" style={{ display: 'flex', gap: 16, flexWrap: 'wrap', fontSize: 12.5 }}>
        {parts.map((p) => (
          <button type="button" key={p.key} className="faint" onClick={() => openTransactions(row.bank, p.key)}
            style={{ background: 'none', border: 0, padding: 0, font: 'inherit', cursor: 'pointer' }}>
            <span className="dot" style={{ background: p.color, marginRight: 6 }} />{p.label} {moneyWhole(row[p.key])}
          </button>
        ))}
      </div>
    </div>
  );
}
