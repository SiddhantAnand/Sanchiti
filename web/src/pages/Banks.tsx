import { useEffect, useState } from 'react';
import { ArrowDownLeft, ArrowLeftRight, ArrowUpRight, Landmark, Scale } from 'lucide-react';
import { api, type Summary } from '../api';
import { money, moneyWhole, rangeLabel, type Period } from '../format';
import { Empty, Skeleton } from '../ui';

type Part = { key: string; label: string; color: string };

export const KINDS = [
  { key: 'spent', label: 'Spending', color: 'var(--primary)' },
  { key: 'invested', label: 'Investments', color: '#34D399' },
  { key: 'ownTransfers', label: 'Own accounts & card bills', color: '#60A5FA' },
] as const;

export const IN_KINDS = [
  { key: 'received', label: 'Received', color: 'var(--teal)' },
  { key: 'ownTransfers', label: 'From own accounts', color: '#60A5FA' },
] as const;

type BankRow = {
  bank: string;
  out: Summary['deductedByBank'][number] | null;
  in: Summary['creditedByBank'][number] | null;
};

function openTransactions(bank: string, kind: string, direction: 'debit' | 'credit') {
  window.location.hash = `/transactions?${new URLSearchParams({ bank, kind, direction })}`;
}

const pct = (part: number, whole: number) => (whole ? Math.round((part / whole) * 1000) / 10 : 0);

export function Banks({ period, refreshKey }: { period: Period; refreshKey: number }) {
  const [summary, setSummary] = useState<Summary | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setSummary(null);
    api.summary(period.from, period.to)
      .then((s) => live && (setSummary(s), setError(null)))
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
  }, [period.from, period.to, refreshKey]);

  if (error) return <div className="alert">{error}</div>;
  if (!summary) return <div className="card"><Skeleton h={220} /></div>;

  const names = [...new Set([...summary.deductedByBank.map((r) => r.bank), ...summary.creditedByBank.map((r) => r.bank)])];
  const rows: BankRow[] = names
    .map((bank) => ({
      bank,
      out: summary.deductedByBank.find((r) => r.bank === bank) ?? null,
      in: summary.creditedByBank.find((r) => r.bank === bank) ?? null,
    }))
    .sort((a, b) => (b.out?.total ?? 0) + (b.in?.total ?? 0) - (a.out?.total ?? 0) - (a.in?.total ?? 0));

  if (rows.length === 0) {
    return (
      <div className="card">
        <Empty icon={<Landmark size={28} />} title="No money moved in this period">
          <p>Nothing went in or out of any account between {rangeLabel(period.from, period.to)}.</p>
        </Empty>
      </div>
    );
  }

  const totalOut = summary.deductedByBank.reduce((s, r) => s + r.total, 0);
  const totalIn = summary.creditedByBank.reduce((s, r) => s + r.total, 0);
  const debits = summary.deductedByBank.reduce((s, r) => s + r.count, 0);
  const credits = summary.creditedByBank.reduce((s, r) => s + r.count, 0);
  const flow = totalIn - totalOut;

  return (
    <div className="stack">
      <div className="grid kpis">
        <div className="card kpi kpi-hero">
          <span className="kpi-label"><span className="kpi-icon" style={{ background: 'var(--primary-soft)', color: 'var(--primary)' }}><ArrowUpRight size={16} /></span>Money out</span>
          <div className="kpi-value">{moneyWhole(totalOut)}</div>
          <div className="kpi-foot">{debits} debit{debits === 1 ? '' : 's'} · {moneyWhole(summary.total)} of it spending</div>
        </div>
        <div className="card kpi">
          <span className="kpi-label"><span className="kpi-icon" style={{ background: 'var(--teal-soft)', color: 'var(--teal)' }}><ArrowDownLeft size={16} /></span>Money in</span>
          <div className="kpi-value" style={{ color: totalIn ? 'var(--teal)' : undefined }}>{moneyWhole(totalIn)}</div>
          <div className="kpi-foot">{credits} credit{credits === 1 ? '' : 's'} · {moneyWhole(summary.received)} from others</div>
        </div>
        <div className="card kpi">
          <span className="kpi-label"><span className="kpi-icon" style={{ background: 'var(--amber-soft)', color: 'var(--amber)' }}><Scale size={16} /></span>Net flow</span>
          <div className="kpi-value" style={{ color: flow > 0 ? 'var(--teal)' : flow < 0 ? 'var(--rose)' : undefined }}>
            {flow > 0 ? '+' : flow < 0 ? '−' : ''}{moneyWhole(Math.abs(flow))}
          </div>
          <div className="kpi-foot">Everything in minus everything out</div>
        </div>
        <div className="card kpi">
          <span className="kpi-label"><span className="kpi-icon" style={{ background: 'rgba(96,165,250,.14)', color: '#60A5FA' }}><ArrowLeftRight size={16} /></span>Own accounts & card bills</span>
          <div className="kpi-value">{moneyWhole(summary.ownTransfers)}</div>
          <div className="kpi-foot">
            {summary.ownTransfersIn > 0
              ? `${moneyWhole(summary.ownTransfersIn)} of it arrived in another of your accounts`
              : 'Self transfers and card bills, not spending'}
          </div>
        </div>
      </div>

      <div className="card">
        <div className="card-head">
          <div>
            <h3 className="card-title">Money in and out by bank</h3>
            <div className="card-sub">Every debit and credit. A move between your own accounts shows as out of one bank and into the other. Click a bar to list it.</div>
          </div>
        </div>
        <div className="stack" style={{ gap: 26 }}>
          {rows.map((r) => <Bank key={r.bank} row={r} />)}
        </div>
      </div>
    </div>
  );
}

function Bank({ row }: { row: BankRow }) {
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 6 }}>
        <b style={{ flex: 1 }}>{row.bank}</b>
        {row.in && <span className="num" style={{ fontWeight: 700, color: 'var(--teal)' }}>+{money(row.in.total)}</span>}
        {row.out && <span className="num" style={{ fontWeight: 700 }}>−{money(row.out.total)}</span>}
      </div>
      {row.out && (
        <Flow bank={row.bank} label="Out" direction="debit" count={row.out.count} total={row.out.total}
          parts={KINDS} value={(k) => row.out![k as (typeof KINDS)[number]['key']]} />
      )}
      {row.in && (
        <Flow bank={row.bank} label="In" direction="credit" count={row.in.count} total={row.in.total}
          parts={IN_KINDS} value={(k) => row.in![k as (typeof IN_KINDS)[number]['key']]} />
      )}
    </div>
  );
}

function Flow(props: {
  bank: string; label: string; direction: 'debit' | 'credit'; count: number; total: number;
  parts: readonly Part[]; value: (key: string) => number;
}) {
  const [hover, setHover] = useState<{ key: string; center: number } | null>(null);
  const parts = props.parts.filter((p) => props.value(p.key) > 0);
  const shown = parts.find((p) => p.key === hover?.key);
  const noun = props.direction === 'credit' ? 'credit' : 'debit';
  let start = 0;

  return (
    <div className="flow-row">
      <span className="flow-label">{props.label}</span>
      <div style={{ position: 'relative', flex: 1, minWidth: 0 }}>
        <div className="split" onMouseLeave={() => setHover(null)}>
          {parts.map((p) => {
            const width = (props.value(p.key) / props.total) * 100;
            const center = start + width / 2;
            start += width;
            return (
              <i key={p.key} role="link" aria-label={`${p.label} ${money(props.value(p.key))}: show transactions`}
                style={{ width: `${width}%`, background: p.color, cursor: 'pointer', opacity: hover && hover.key !== p.key ? 0.45 : 1 }}
                onMouseEnter={() => setHover({ key: p.key, center })}
                onClick={() => openTransactions(props.bank, p.key, props.direction)} />
            );
          })}
        </div>
        {hover && shown && (
          <div className="chart-tip" style={{
            position: 'absolute', bottom: 'calc(100% + 2px)', left: `${Math.min(Math.max(hover.center, 8), 92)}%`,
            transform: 'translateX(-50%)', pointerEvents: 'none', whiteSpace: 'nowrap', zIndex: 5,
          }}>
            <div className="t">{props.bank}</div>
            <div className="r">
              <span><span className="dot" style={{ background: shown.color, marginRight: 6 }} />{shown.label}</span>
              <b className="num">{money(props.value(shown.key))}</b>
            </div>
            <div className="faint" style={{ marginTop: 4, fontSize: 11.5 }}>{pct(props.value(shown.key), props.total)}% of this bank's {noun}s · click to list them</div>
          </div>
        )}
        <div className="faint" style={{ display: 'flex', gap: 16, flexWrap: 'wrap', fontSize: 12.5 }}>
          <span>{props.count} {noun}{props.count === 1 ? '' : 's'}</span>
          {parts.map((p) => (
            <button type="button" key={p.key} className="faint" onClick={() => openTransactions(props.bank, p.key, props.direction)}
              style={{ background: 'none', border: 0, padding: 0, font: 'inherit', cursor: 'pointer' }}>
              <span className="dot" style={{ background: p.color, marginRight: 6 }} />{p.label} {moneyWhole(props.value(p.key))}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
