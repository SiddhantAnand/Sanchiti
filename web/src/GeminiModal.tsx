import { useEffect, useState } from 'react';
import { CircleAlert, CircleCheck, LoaderCircle, Sparkles } from 'lucide-react';
import { api } from './api';
import { Modal, useToast } from './ui';
import { toIso } from './format';

type StepState = { state: 'idle' | 'running' | 'done' | 'error'; text?: string };

export function GeminiModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const [pending, setPending] = useState<number | null>(null);
  const [doCategorize, setDoCategorize] = useState(true);
  const [doAnalyse, setDoAnalyse] = useState(true);
  const [window_, setWindow] = useState<'last7' | 'lastWeek'>('last7');
  const [steps, setSteps] = useState<{ categorize: StepState; analyse: StepState } | null>(null);
  const running = steps !== null && Object.values(steps).some((s) => s.state === 'running');
  const finished = steps !== null && !running;

  useEffect(() => {
    api.transactions({ category: 'Uncategorized', limit: 1 })
      .then((p) => { setPending(p.total); setDoCategorize(p.total > 0); })
      .catch(() => setPending(0));
  }, []);

  async function run() {
    const s = { categorize: { state: 'idle' } as StepState, analyse: { state: 'idle' } as StepState };
    setSteps({ ...s });
    if (doCategorize) {
      setSteps((x) => x && { ...x, categorize: { state: 'running' } });
      try {
        const r = await api.categorize();
        setSteps((x) => x && { ...x, categorize: { state: 'done', text: `Categorised ${r.categorized} transaction${r.categorized === 1 ? '' : 's'}${r.pending ? ` · ${r.pending} still pending` : ''}` } });
      } catch (e) {
        setSteps((x) => x && { ...x, categorize: { state: 'error', text: (e as Error).message } });
      }
    }
    if (doAnalyse) {
      setSteps((x) => x && { ...x, analyse: { state: 'running' } });
      try {
        const r = await api.runAnalysis(window_ === 'last7' ? toIso(new Date()) : undefined);
        const text = r.status === 'skipped' ? r.reason ?? 'Nothing to analyse'
          : r.status === 'cached' ? 'No changes since the last analysis — kept it'
          : `“${r.analysis?.insights.headline}”`;
        setSteps((x) => x && { ...x, analyse: { state: r.status === 'skipped' ? 'error' : 'done', text } });
      } catch (e) {
        setSteps((x) => x && { ...x, analyse: { state: 'error', text: (e as Error).message } });
      }
    }
    toast('ok', 'Gemini is done');
    onDone();
  }

  const icon = (s?: StepState) =>
    s?.state === 'running' ? <LoaderCircle size={16} className="spinner" color="var(--primary)" />
      : s?.state === 'done' ? <CircleCheck size={16} color="var(--teal)" />
        : s?.state === 'error' ? <CircleAlert size={16} color="var(--rose)" /> : null;

  return (
    <Modal
      onClose={() => !running && onClose()}
      hero={
        <div style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
          <span className="gemini-mark" style={{ width: 44, height: 44, borderRadius: 13 }}><Sparkles size={20} /></span>
          <div>
            <h2 className="page-title" style={{ fontSize: 21 }}>Send to Gemini</h2>
            <div className="muted" style={{ fontSize: 13 }}>Review your edits and new entries with AI</div>
          </div>
        </div>
      }
      foot={
        finished ? <button className="btn btn-primary" onClick={onClose}>Done</button> : (
          <>
            <button className="btn btn-ghost" disabled={running} onClick={onClose}>Cancel</button>
            <button className="btn btn-gemini" disabled={running || (!doCategorize && !doAnalyse)} onClick={run}>
              {running ? <LoaderCircle size={16} className="spinner" /> : <Sparkles size={16} />} {running ? 'Working…' : 'Send'}
            </button>
          </>
        )
      }
    >
      <label className="check-row">
        <input type="checkbox" checked={doCategorize} disabled={running || finished} onChange={(e) => setDoCategorize(e.target.checked)} />
        <div style={{ flex: 1 }}>
          <div style={{ fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
            Categorise transactions {icon(steps?.categorize)}
          </div>
          <div className="muted" style={{ fontSize: 12.5 }}>
            {steps?.categorize.text ?? (pending === null ? 'Checking…' : pending === 0
              ? 'Everything already has a category.'
              : `${pending} transaction${pending === 1 ? '' : 's'} waiting for a category.`)}
          </div>
        </div>
      </label>
      <label className="check-row">
        <input type="checkbox" checked={doAnalyse} disabled={running || finished} onChange={(e) => setDoAnalyse(e.target.checked)} />
        <div style={{ flex: 1 }}>
          <div style={{ fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
            Refresh spending insights {icon(steps?.analyse)}
          </div>
          <div className="muted" style={{ fontSize: 12.5, marginBottom: 8 }}>
            {steps?.analyse.text ?? 'Gemini compares the week with the one before and suggests where to save.'}
          </div>
          {!steps && (
            <div className="segmented" onClick={(e) => e.preventDefault()}>
              <button type="button" className={window_ === 'last7' ? 'on' : ''} onClick={() => setWindow('last7')}>Last 7 days</button>
              <button type="button" className={window_ === 'lastWeek' ? 'on' : ''} onClick={() => setWindow('lastWeek')}>Last full week (Mon–Sun)</button>
            </div>
          )}
        </div>
      </label>
    </Modal>
  );
}
