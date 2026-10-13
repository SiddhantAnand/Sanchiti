import { useEffect, useState } from 'react';
import {
  Area, AreaChart, CartesianGrid, Cell, Line, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { ArrowRight, CalendarDays, CircleQuestionMark, PiggyBank, ReceiptText, Sparkles, TrendingUp, Wallet } from 'lucide-react';
import { api, type Analysis, type Summary } from '../api';
import { CategoryIcon, categoryMeta } from '../categories';
import { methodLabel, money, moneyCompact, moneyWhole, rangeLabel, shortDate, type Period } from '../format';
import { Delta, Empty, Skeleton } from '../ui';

const METHOD_COLORS: Record<string, string> = { UPI: 'var(--primary)', CARD: '#2DD4BF', AUTOPAY: '#F5B544', NETBANKING: '#4FB3FF', CASH: '#7BD389' };
const BANK_COLORS = ['var(--primary)', '#2DD4BF', '#F5B544', '#4FB3FF', '#FF8A5B'];

type TipProps = { active?: boolean; payload?: readonly { value?: unknown; payload?: Record<string, unknown> }[] };

function TrendTip({ active, payload }: TipProps) {
  if (!active || !payload?.length) return null;
  const p = payload[0].payload as { date: string; total: number; prevDate?: string; prev?: number };
  return (
    <div className="chart-tip">
      <div className="t">{shortDate(p.date)}</div>
      <div className="r"><span><span className="dot" style={{ background: 'var(--primary)', display: 'inline-block', marginRight: 6 }} />Spent</span><b className="num">{money(p.total)}</b></div>
      {p.prevDate && (
        <div className="r faint" style={{ marginTop: 4 }}><span>{shortDate(p.prevDate)}</span><span className="num">{money(p.prev ?? 0)}</span></div>
      )}
    </div>
  );
}

function DonutTip({ active, payload }: TipProps) {
  if (!active || !payload?.length) return null;
  const p = payload[0].payload as { category: string; total: number; sharePct: number; count: number };
  return (
    <div className="chart-tip">
      <div className="t">{p.category}</div>
      <div className="r"><b className="num">{money(p.total)}</b><span className="faint">{p.sharePct}% · {p.count} txns</span></div>
    </div>
  );
}

export function Overview(props: {
  period: Period; refreshKey: number; aiEnabled: boolean; onOpenCategory: (c: string) => void; onGo: (route: string) => void;
}) {
  const { period } = props;
  const [cur, setCur] = useState<Summary | null>(null);
  const [prev, setPrev] = useState<Summary | null>(null);
  const [latest, setLatest] = useState<Analysis | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setCur(null);
    Promise.all([api.summary(period.from, period.to), api.summary(period.prevFrom, period.prevTo)])
      .then(([a, b]) => live && (setCur(a), setPrev(b), setError(null)))
      .catch((e) => live && setError(e.message));
    api.analyses().then((r) => live && setLatest(r.items[0] ?? null)).catch(() => {});
    return () => { live = false; };
  }, [period.from, period.to, period.prevFrom, period.prevTo, props.refreshKey]);

  if (error) return <div className="alert">{error}</div>;
  if (!cur || !prev) return <OverviewSkeleton />;

  const trend = cur.daily.map((d, i) => ({ ...d, prevDate: prev.daily[i]?.date, prev: prev.daily[i]?.total ?? 0 }));
  const peak = cur.daily.reduce((m, d) => (d.total > m.total ? d : m), cur.daily[0]);
  const top = cur.byCategory.slice(0, 6);
  const methodTotal = cur.byPaymentMethod.reduce((s, m) => s + m.total, 0) || 1;
  const bankTotal = cur.byBank.reduce((s, b) => s + b.total, 0) || 1;
  const maxCat = cur.byCategory[0]?.total || 1;

  if (cur.count === 0 && cur.investedCount === 0 && cur.ownTransferCount === 0) {
    return (
      <div className="card">
        <Empty icon={<CalendarDays size={28} />} title="Nothing spent in this period">
          <p>No transactions between {rangeLabel(cur.from, cur.to)}. Try a wider range above.</p>
        </Empty>
      </div>
    );
  }

  return (
    <div className="stack">
      <div className="grid kpis">
        <div className="card kpi kpi-hero">
          <div className="kpi-top">
            <span className="kpi-label"><span className="kpi-icon" style={{ background: 'var(--primary-soft)', color: 'var(--primary)' }}><Wallet size={16} /></span>Total spent</span>
            <Delta current={cur.total} previous={prev.total} />
          </div>
          <div className="kpi-value">{moneyWhole(cur.total)}</div>
          <div className="kpi-foot">vs {moneyWhole(prev.total)} in the previous {cur.daily.length} days</div>
        </div>
        <div className="card kpi">
          <div className="kpi-top">
            <span className="kpi-label"><span className="kpi-icon" style={{ background: 'rgba(79,179,255,.14)', color: '#4FB3FF' }}><TrendingUp size={16} /></span>Daily average</span>
            <Delta current={cur.averagePerDay} previous={prev.averagePerDay} />
          </div>
          <div className="kpi-value">{moneyWhole(cur.averagePerDay)}</div>
          <div className="kpi-foot">Peak {moneyWhole(peak.total)} on {shortDate(peak.date)}</div>
        </div>
        <div className="card kpi">
          <div className="kpi-top">
            <span className="kpi-label"><span className="kpi-icon" style={{ background: 'var(--amber-soft)', color: 'var(--amber)' }}><ReceiptText size={16} /></span>Transactions</span>
            {cur.uncategorizedCount > 0 && (
              <span className="badge badge-amber"><CircleQuestionMark size={12} />{cur.uncategorizedCount} uncategorised</span>
            )}
          </div>
          <div className="kpi-value">{cur.count}</div>
          <div className="kpi-foot">Avg ticket {moneyWhole(cur.count ? cur.total / cur.count : 0)}</div>
        </div>
        <div className="card kpi">
          <div className="kpi-top">
            <span className="kpi-label"><span className="kpi-icon" style={{ background: 'var(--teal-soft)', color: 'var(--teal)' }}><PiggyBank size={16} /></span>Invested</span>
            {cur.investedCount > 0 && <span className="badge badge-teal">{cur.investedCount} investment{cur.investedCount === 1 ? '' : 's'}</span>}
          </div>
          <div className="kpi-value" style={{ color: cur.invested ? 'var(--teal)' : undefined }}>{moneyWhole(cur.invested)}</div>
          <div className="kpi-foot">
            {cur.ownTransferCount > 0
              ? `${moneyWhole(cur.ownTransfers)} more to own accounts & card bills · not counted as spending`
              : 'Kept out of the spending total'}
          </div>
        </div>
      </div>

      <div className="grid row-2-1">
        <div className="card card-fill">
          <div className="card-head">
            <div>
              <h3 className="card-title">Spending trend</h3>
              <div className="card-sub">{rangeLabel(cur.from, cur.to)}</div>
            </div>
            <div className="legend">
              <span><i style={{ background: 'var(--primary)' }} />This period</span>
              <span><i style={{ background: 'var(--text-3)' }} />Previous</span>
            </div>
          </div>
          <div className="chart">
            <ResponsiveContainer>
              <AreaChart data={trend} margin={{ top: 8, right: 4, left: -8, bottom: 0 }}>
                <defs>
                  <linearGradient id="spend" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="var(--primary)" stopOpacity={0.4} />
                    <stop offset="100%" stopColor="var(--primary)" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke="rgba(148,163,209,0.08)" vertical={false} />
                <XAxis dataKey="date" tickFormatter={shortDate} tick={{ fill: 'var(--text-3)', fontSize: 11.5 }} axisLine={false} tickLine={false} minTickGap={24} />
                <YAxis tickFormatter={moneyCompact} tick={{ fill: 'var(--text-3)', fontSize: 11.5 }} axisLine={false} tickLine={false} width={58} />
                <Tooltip content={<TrendTip />} cursor={{ stroke: 'rgba(139,123,255,.4)', strokeDasharray: '4 4' }} />
                <Line type="monotone" dataKey="prev" stroke="var(--text-3)" strokeWidth={1.5} strokeDasharray="4 4" dot={false} isAnimationActive={false} />
                <Area type="monotone" dataKey="total" stroke="var(--primary)" strokeWidth={2.5} fill="url(#spend)"
                  activeDot={{ r: 5, fill: 'var(--primary)', stroke: 'var(--surface)', strokeWidth: 2 }} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </div>

        <div className="card">
          <div className="card-head">
            <div>
              <h3 className="card-title">Where it went</h3>
              <div className="card-sub">{cur.byCategory.length} categories</div>
            </div>
          </div>
          <div className="donut-wrap">
            <ResponsiveContainer>
              <PieChart>
                <Pie data={cur.byCategory} dataKey="total" nameKey="category" innerRadius={68} outerRadius={96}
                  paddingAngle={2} cornerRadius={5} stroke="none">
                  {cur.byCategory.map((c) => <Cell key={c.category} fill={categoryMeta(c.category).color} />)}
                </Pie>
                <Tooltip content={<DonutTip />} />
              </PieChart>
            </ResponsiveContainer>
            <div className="donut-center">
              <div><div className="v">{moneyCompact(cur.total)}</div><div className="l">spent</div></div>
            </div>
          </div>
          <div className="cat-legend">
            {top.map((c) => (
              <div className="cat-legend-row" key={c.category}>
                <span className="dot" style={{ background: categoryMeta(c.category).color }} />
                <span>{c.category}</span>
                <span className="num">{moneyWhole(c.total)}</span>
                <span className="pct">{c.sharePct}%</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="grid row-2-1">
        <div className="card">
          <div className="card-head">
            <div>
              <h3 className="card-title">Categories</h3>
              <div className="card-sub">Click one to see its transactions</div>
            </div>
          </div>
          <div className="bars">
            {cur.byCategory.map((c) => {
              const color = categoryMeta(c.category).color;
              return (
                <button className="bar-row" key={c.category} onClick={() => props.onOpenCategory(c.category)}>
                  <CategoryIcon category={c.category} />
                  <div>
                    <div className="name"><span>{c.category}</span><span className="meta">{c.count} txn{c.count === 1 ? '' : 's'} · {c.sharePct}%</span></div>
                    <div className="track"><i style={{ width: `${Math.max(2, (c.total / maxCat) * 100)}%`, background: color }} /></div>
                  </div>
                  <span className="amt">{moneyWhole(c.total)}</span>
                </button>
              );
            })}
          </div>
        </div>

        <div className="stack">
          <GeminiTeaser latest={latest} aiEnabled={props.aiEnabled} onGo={() => props.onGo('insights')} />
          <div className="card">
            <h3 className="card-title">How you paid</h3>
            <div className="split">
              {cur.byPaymentMethod.map((m) => (
                <i key={m.method} style={{ width: `${(m.total / methodTotal) * 100}%`, background: METHOD_COLORS[m.method] ?? '#7C86A2' }} />
              ))}
            </div>
            <div className="cat-legend" style={{ marginTop: 0 }}>
              {cur.byPaymentMethod.map((m) => (
                <div className="cat-legend-row" key={m.method}>
                  <span className="dot" style={{ background: METHOD_COLORS[m.method] ?? '#7C86A2' }} />
                  <span>{m.method === 'UNKNOWN' ? 'Unknown' : methodLabel(m.method)} <span className="faint">· {m.count}</span></span>
                  <span className="num">{moneyWhole(m.total)}</span>
                  <span className="pct">{Math.round((m.total / methodTotal) * 100)}%</span>
                </div>
              ))}
            </div>
            {cur.byBank.length > 0 && (
              <>
                <h3 className="card-title" style={{ marginTop: 22 }}>By bank</h3>
                <div className="split">
                  {cur.byBank.map((b, i) => <i key={b.bank} style={{ width: `${(b.total / bankTotal) * 100}%`, background: BANK_COLORS[i % BANK_COLORS.length] }} />)}
                </div>
                <div className="cat-legend" style={{ marginTop: 0 }}>
                  {cur.byBank.map((b, i) => (
                    <div className="cat-legend-row" key={b.bank}>
                      <span className="dot" style={{ background: BANK_COLORS[i % BANK_COLORS.length] }} />
                      <span>{b.bank} <span className="faint">· {b.count}</span></span>
                      <span className="num">{moneyWhole(b.total)}</span>
                      <span className="pct">{Math.round((b.total / bankTotal) * 100)}%</span>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>
        </div>
      </div>

      <div className="grid row-1-1">
        <div className="card">
          <div className="card-head"><h3 className="card-title">Biggest spends</h3></div>
          <div className="mini-list">
            {cur.largest.map((l, i) => (
              <div className="mini-row" key={i}>
                <CategoryIcon category={l.category} />
                <div className="grow">
                  <div className="title">{l.payee}</div>
                  <div className="sub">{shortDate(l.date)} · {l.category ?? 'Uncategorized'}</div>
                </div>
                <span className="num" style={{ fontWeight: 700 }}>{money(l.amount)}</span>
              </div>
            ))}
          </div>
        </div>
        <div className="card">
          <div className="card-head"><h3 className="card-title">Regulars</h3><span className="card-sub">Payees you paid more than once</span></div>
          {cur.frequentPayees.length === 0 ? (
            <p className="muted" style={{ margin: 0 }}>No repeat payees in this period.</p>
          ) : (
            <div className="mini-list">
              {cur.frequentPayees.map((p, i) => (
                <div className="mini-row" key={p.payee}>
                  <span className="rank">{i + 1}</span>
                  <div className="grow">
                    <div className="title">{p.payee}</div>
                    <div className="sub">{p.count} payments · avg {moneyWhole(p.total / p.count)}</div>
                  </div>
                  <span className="num" style={{ fontWeight: 700 }}>{moneyWhole(p.total)}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function GeminiTeaser({ latest, aiEnabled, onGo }: { latest: Analysis | null; aiEnabled: boolean; onGo: () => void }) {
  return (
    <div className="card gemini-card">
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 12 }}>
        <span className="gemini-mark"><Sparkles size={17} /></span>
        <div>
          <div className="card-title">Gemini insight</div>
          <div className="card-sub">{latest ? rangeLabel(latest.periodFrom, latest.periodTo) : aiEnabled ? 'No analysis yet' : 'Gemini is not configured'}</div>
        </div>
      </div>
      {latest ? (
        <>
          <p className="headline" style={{ fontSize: 16 }}>{latest.insights.headline}</p>
          <p className="muted" style={{ margin: '0 0 14px', fontSize: 13 }}>{latest.insights.summary}</p>
        </>
      ) : (
        <p className="muted" style={{ margin: '0 0 14px', fontSize: 13 }}>
          Let Gemini read your week and tell you what stood out, what to watch and where to save.
        </p>
      )}
      <button className="btn btn-sm" onClick={onGo}>Open insights <ArrowRight size={14} /></button>
    </div>
  );
}

function OverviewSkeleton() {
  return (
    <div className="stack">
      <div className="grid kpis">
        {[0, 1, 2, 3].map((i) => (
          <div className="card" key={i}><Skeleton w={120} /><div style={{ height: 16 }} /><Skeleton h={30} w={160} /><div style={{ height: 12 }} /><Skeleton w={180} h={12} /></div>
        ))}
      </div>
      <div className="grid row-2-1">
        <div className="card"><Skeleton h={300} /></div>
        <div className="card"><Skeleton h={300} /></div>
      </div>
    </div>
  );
}
