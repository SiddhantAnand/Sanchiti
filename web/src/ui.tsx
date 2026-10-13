import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { ArrowDownRight, ArrowUpRight, CircleAlert, CircleCheck, Copy, Eye, EyeOff, X } from 'lucide-react';

type Toast = { id: number; kind: 'ok' | 'err'; text: string };
const ToastContext = createContext<(kind: Toast['kind'], text: string) => void>(() => {});
export const useToast = () => useContext(ToastContext);

export function useCopy() {
  const toast = useToast();
  return async (text: string, what: string) => {
    try {
      await navigator.clipboard.writeText(text);
      toast('ok', `${what} copied`);
    } catch {
      toast('err', 'Copy is blocked on this connection; select the text and copy it by hand');
    }
  };
}

/** Read-only value with a Copy button; `secret` hides it until revealed. */
export function CopyField({ label, value, secret = false }: { label: string; value: string; secret?: boolean }) {
  const copy = useCopy();
  const [shown, setShown] = useState(!secret);
  return (
    <div className="field">
      <span>{label}</span>
      <div style={{ display: 'flex', gap: 8 }}>
        <input className="input num" readOnly type={shown ? 'text' : 'password'} value={value} onFocus={(e) => e.target.select()} />
        {secret && (
          <button type="button" className="btn btn-sm" onClick={() => setShown((s) => !s)} aria-label={shown ? 'Hide' : 'Show'}>
            {shown ? <EyeOff size={14} /> : <Eye size={14} />}
          </button>
        )}
        <button type="button" className="btn btn-sm" onClick={() => copy(value, label)}><Copy size={14} /> Copy</button>
      </div>
    </div>
  );
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = useCallback((kind: Toast['kind'], text: string) => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t, { id, kind, text }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4200);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast ${t.kind}`}>
            {t.kind === 'ok' ? <CircleCheck size={18} /> : <CircleAlert size={18} />}
            <span>{t.text}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

function useEscape(onClose: () => void) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);
}

export function Drawer(props: { title: ReactNode; sub?: ReactNode; onClose: () => void; children: ReactNode; foot: ReactNode }) {
  useEscape(props.onClose);
  return (
    <>
      <div className="overlay" onClick={props.onClose} />
      <aside className="drawer" role="dialog" aria-modal="true">
        <div className="drawer-head">
          <div>
            <h2 className="page-title" style={{ fontSize: 22 }}>{props.title}</h2>
            {props.sub && <div className="page-sub" style={{ marginTop: 4 }}>{props.sub}</div>}
          </div>
          <button className="icon-btn" onClick={props.onClose} aria-label="Close"><X size={18} /></button>
        </div>
        <div className="drawer-body">{props.children}</div>
        <div className="drawer-foot">{props.foot}</div>
      </aside>
    </>
  );
}

export function Modal(props: { onClose: () => void; hero: ReactNode; children: ReactNode; foot: ReactNode }) {
  useEscape(props.onClose);
  return (
    <>
      <div className="overlay" onClick={props.onClose} />
      <div className="modal" role="dialog" aria-modal="true">
        <div className="modal-hero">{props.hero}</div>
        <div className="modal-body">{props.children}</div>
        <div className="modal-foot">{props.foot}</div>
      </div>
    </>
  );
}

export function Empty({ icon, title, children }: { icon: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-icon">{icon}</div>
      <h3>{title}</h3>
      {children}
    </div>
  );
}

/** Change in spending: going up is shown as bad (rose), going down as good (teal). {@code goodUp} flips it for income. */
export function Delta({ current, previous, goodUp = false }: { current: number; previous: number; goodUp?: boolean }) {
  if (previous === 0) return <span className="delta flat">new</span>;
  const pct = ((current - previous) / previous) * 100;
  if (Math.abs(pct) < 0.5) return <span className="delta flat">±0%</span>;
  const up = pct > 0;
  return (
    <span className={`delta ${up !== goodUp ? 'up' : 'down'}`}>
      {up ? <ArrowUpRight size={13} /> : <ArrowDownRight size={13} />}
      {Math.abs(pct) >= 100 ? Math.round(Math.abs(pct)) : Math.abs(pct).toFixed(1)}%
    </span>
  );
}

export function Skeleton({ h = 16, w = '100%' }: { h?: number; w?: number | string }) {
  return <div className="skeleton" style={{ height: h, width: w }} />;
}
