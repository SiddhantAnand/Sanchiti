import { useEffect, useState, type ReactNode } from 'react';
import { CircleCheck, Clock, Lightbulb, LoaderCircle, Sparkles, TriangleAlert, Zap } from 'lucide-react';
import { api, type Analysis } from '../api';
import { categoryMeta } from '../categories';
import { money, moneyWhole, rangeLabel } from '../format';
import { Delta, Empty, Skeleton, useToast } from '../ui';

export function Insights(props: { refreshKey: number; aiEnabled: boolean; onAsk: () => void }) {
  const toast = useToast();
  const [items, setItems] = useState<Analysis[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = () =>
    api.analyses()
      .then((r) => { setItems(r.items); setSelected((s) => s ?? r.items[0]?.id ?? null); setError(null); })
      .catch((e) => setError(e.message));

  useEffect(() => { load(); }, [props.refreshKey]);

  async function generate() {
    setRunning(true);
    try {
      const res = await api.runAnalysis(undefined);
      if (res.status === 'skipped') toast('err', res.reason ?? 'Nothing to analyse');
      else toast('ok', res.status === 'cached' ? 'Nothing changed since the last analysis' : 'Fresh insights are ready');
      if (res.analysis) setSelected(res.analysis.id);
      await load();
    } catch (e) {
      toast('err', (e as Error).message);
    } finally {
      setRunning(false);
    }
  }

  if (error) return <div className="alert">{error}</div>;
  if (!items) return <div className="card"><Skeleton h={320} /></div>;

  if (items.length === 0) {
    return (
      <div className="card gemini-card">
        <Empty icon={<Sparkles size={28} />} title="No insights yet">
          <p>Gemini reads a week of your spending — categories, payees, the week before — and tells you what stood out
            and where you could save.</p>
          <button className="btn btn-gemini" style={{ marginTop: 12 }} disabled={!props.aiEnabled || running} onClick={generate}>
            {running ? <LoaderCircle size={16} className="spinner" /> : <Sparkles size={16} />} Analyse last week
          </button>
          {!props.aiEnabled && <p className="faint" style={{ fontSize: 12.5 }}>Set GEMINI_API_KEY in backend/.env to enable.</p>}
        </Empty>
      </div>
    );
  }

  const a = items.find((x) => x.id === selected) ?? items[0];
  const ins = a.insights;
  const week = a.stats.week;
  const vs = a.stats.vsPreviousWeek;

  return (
    <div className="grid row-2-1" style={{ alignItems: 'start' }}>
      <div className="stack">
        <div className="card gemini-card">
          <div className="card-head">
            <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
              <span className="gemini-mark"><Sparkles size={17} /></span>
              <div>
                <div className="card-title">{rangeLabel(a.periodFrom, a.periodTo)}</div>
                <div className="card-sub">Generated {new Date(a.createdAt).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })} · {a.model}</div>
              </div>
            </div>
            <button className="btn btn-sm" disabled={!props.aiEnabled || running} onClick={generate}>
              {running ? <LoaderCircle size={14} className="spinner" /> : <Zap size={14} />} Analyse last week
            </button>
          </div>
          <h2 className="headline" style={{ fontSize: 22 }}>{ins.headline}</h2>
          <p className="muted" style={{ margin: 0, fontSize: 14.5, lineHeight: 1.65 }}>{ins.summary}</p>
          {week && (
            <div style={{ display: 'flex', gap: 28, marginTop: 20, flexWrap: 'wrap' }}>
              <Stat label="Spent" value={moneyWhole(week.total)} extra={vs && <Delta current={week.total} previous={vs.previousTotal} />} />
              <Stat label="Transactions" value={String(week.count)} />
              {week.invested > 0 && <Stat label="Invested" value={moneyWhole(week.invested)} />}
              {vs && <Stat label="Week before" value={moneyWhole(vs.previousTotal)} />}
            </div>
          )}
          {week && week.byCategory.length > 0 && (
            <div className="split" style={{ marginTop: 18, marginBottom: 0 }}>
              {week.byCategory.map((c) => (
                <i key={c.category} title={`${c.category} ${c.sharePct}%`} style={{ width: `${c.sharePct}%`, background: categoryMeta(c.category).color }} />
              ))}
            </div>
          )}
        </div>

        <div className="insight-lists">
          <List title="Highlights" icon={<CircleCheck size={15} color="var(--teal)" />} items={ins.highlights} />
          <List title="Watch out" icon={<TriangleAlert size={15} color="var(--amber)" />} items={ins.concerns} />
          <List title="Ways to save" icon={<Lightbulb size={15} color="var(--primary)" />} items={ins.savingTips} />
        </div>

        {ins.unusualSpends.length > 0 && (
          <div className="card">
            <div className="card-head"><h3 className="card-title">Out of pattern</h3></div>
            <div className="mini-list">
              {ins.unusualSpends.map((u, i) => (
                <div className="mini-row" key={i}>
                  <span className="kpi-icon" style={{ background: 'var(--rose-soft)', color: 'var(--rose)' }}><TriangleAlert size={15} /></span>
                  <div className="grow">
                    <div className="title">{u.reason}</div>
                    <div className="sub" style={{ whiteSpace: 'normal' }}>{u.why}</div>
                  </div>
                  <span className="num" style={{ fontWeight: 700 }}>{money(u.amount)}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      <div className="card">
        <div className="card-head">
          <h3 className="card-title" style={{ display: 'flex', gap: 8, alignItems: 'center' }}><Clock size={15} /> History</h3>
          <span className="badge">{items.length}</span>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {items.map((x) => (
            <button key={x.id} className={`history-item ${x.id === a.id ? 'on' : ''}`} onClick={() => setSelected(x.id)}>
              <span className="faint" style={{ fontSize: 11.5 }}>{rangeLabel(x.periodFrom, x.periodTo)}</span>
              <span className="h">{x.insights.headline}</span>
            </button>
          ))}
        </div>
        <button className="btn btn-gemini" style={{ width: '100%', marginTop: 14 }} disabled={!props.aiEnabled} onClick={props.onAsk}>
          <Sparkles size={16} /> Send latest changes to Gemini
        </button>
      </div>
    </div>
  );
}

function Stat({ label, value, extra }: { label: string; value: string; extra?: ReactNode }) {
  return (
    <div>
      <div className="faint" style={{ fontSize: 12 }}>{label}</div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span className="num" style={{ fontSize: 20, fontWeight: 800 }}>{value}</span>{extra}
      </div>
    </div>
  );
}

function List({ title, icon, items }: { title: string; icon: ReactNode; items: string[] }) {
  return (
    <div className="card insight-list">
      <h4>{icon}{title}</h4>
      {items.length ? <ul>{items.map((t, i) => <li key={i}>{t}</li>)}</ul> : <p className="faint" style={{ margin: 0, fontSize: 13 }}>Nothing to flag.</p>}
    </div>
  );
}
