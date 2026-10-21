import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Check, CircleCheck, Crown, HandCoins, Link2, LoaderCircle, Pencil, Plus, Receipt, Search, Trash, UserPlus, Users, X,
} from 'lucide-react';
import { api, type Me, type NewSplitExpense, type SplitGroup, type SplitGroupSummary, type Transaction } from '../api';
import { money, moneyWhole, shortDate, toIso } from '../format';
import { Drawer, Empty, Skeleton, usePrompt, useToast } from '../ui';

type Person = { login: string; name: string };
type SplitExpense = SplitGroup['expenses'][number];

export function Split({ me }: { me: Me }) {
  const toast = useToast();
  const prompt = usePrompt();
  const [groups, setGroups] = useState<SplitGroupSummary[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [group, setGroup] = useState<SplitGroup | null>(null);
  const [people, setPeople] = useState<Person[]>([]);
  const [creating, setCreating] = useState(false);
  const [drawer, setDrawer] = useState<'expense' | 'settle' | null>(null);
  const [settlePrefill, setSettlePrefill] = useState<{ from: number; to: number; amount: number } | null>(null);
  const [editing, setEditing] = useState<SplitExpense | null>(null);

  const loadGroups = useCallback(async () => {
    const r = await api.splitGroups();
    setGroups(r.groups);
    setSelected((s) => s ?? r.groups[0]?.id ?? null);
  }, []);

  useEffect(() => {
    loadGroups().catch((e) => toast('err', e.message));
    api.splitPeople().then((r) => setPeople(r.people)).catch(() => {});
  }, [loadGroups, toast]);

  useEffect(() => {
    if (selected === null) return setGroup(null);
    setGroup(null);
    api.splitGroup(selected).then(setGroup).catch((e) => toast('err', e.message));
  }, [selected, toast]);

  /** Runs a change that returns the updated group, then refreshes the list's balances. */
  async function change(action: () => Promise<SplitGroup | void>, ok?: string) {
    try {
      const g = await action();
      if (g) setGroup(g);
      if (ok) toast('ok', ok);
      loadGroups().catch(() => {});
    } catch (e) {
      toast('err', (e as Error).message);
    }
  }

  if (!groups) return <div className="grid row-1-2"><div className="card"><Skeleton h={240} /></div><div className="card"><Skeleton h={240} /></div></div>;

  return (
    <div className="split-layout">
      <div className="card split-groups">
        <div className="card-head">
          <h3 className="card-title">Groups</h3>
          <button className="btn btn-sm btn-primary" onClick={() => setCreating(true)}><Plus size={14} /> New</button>
        </div>
        {creating && (
          <NewGroup onCancel={() => setCreating(false)} onCreated={(id) => { setCreating(false); setSelected(id); loadGroups(); }} />
        )}
        {groups.length === 0 && !creating && (
          <p className="muted" style={{ margin: 0, fontSize: 13 }}>No groups yet. Create one for a trip, a flat or a dinner club.</p>
        )}
        <div className="stack" style={{ gap: 6 }}>
          {groups.map((g) => (
            <button key={g.id} className={`split-group-row ${g.id === selected ? 'on' : ''}`} onClick={() => setSelected(g.id)}>
              <span className="split-avatar"><Users size={15} /></span>
              <span style={{ flex: 1, minWidth: 0, textAlign: 'left' }}>
                <span className="title">{g.name}</span>
                <span className="sub">{g.members} people{g.isOwner ? '' : ` · by ${g.ownerName}`}</span>
              </span>
              <Owes amount={g.myBalance} />
            </button>
          ))}
        </div>
      </div>

      <div className="stack">
        {selected === null && (
          <div className="card">
            <Empty icon={<HandCoins size={28} />} title="Split shared costs">
              <p>Create a group, add friends by name, and record who paid for what. Link a friend to their login later so they
                can see the group too; they never see anything else of yours.</p>
            </Empty>
          </div>
        )}
        {selected !== null && !group && <div className="card"><Skeleton h={300} /></div>}
        {group && (
          <GroupView group={group} people={people} me={me}
            onRename={async () => {
              const name = await prompt({ title: 'Rename group', label: 'Group name', initial: group.name });
              if (name) change(() => api.renameSplitGroup(group.id, name), 'Group renamed');
            }}
            onDelete={() => {
              if (!window.confirm(`Delete "${group.name}" with all its expenses? This cannot be undone.`)) return;
              change(async () => { await api.deleteSplitGroup(group.id); setSelected(null); }, 'Group deleted');
            }}
            onChange={change}
            onAddExpense={() => setDrawer('expense')}
            onEditExpense={setEditing}
            onSettle={(prefill) => { setSettlePrefill(prefill ?? null); setDrawer('settle'); }} />
        )}
      </div>

      {drawer === 'expense' && group && (
        <ExpenseDrawer group={group} onClose={() => setDrawer(null)}
          onSave={(e) => change(async () => { const g = await api.addSplitExpense(group.id, e); setDrawer(null); return g; }, 'Expense added')} />
      )}
      {editing && group && (
        <ExpenseDrawer group={group} expense={editing} onClose={() => setEditing(null)}
          onSave={(e) => change(async () => { const g = await api.updateSplitExpense(group.id, editing.id, e); setEditing(null); return g; }, 'Expense updated')} />
      )}
      {drawer === 'settle' && group && (
        <SettleDrawer group={group} prefill={settlePrefill} onClose={() => setDrawer(null)}
          onSave={(s) => change(async () => { const g = await api.settleSplit(group.id, s); setDrawer(null); return g; })} />
      )}
    </div>
  );
}

function Owes({ amount }: { amount: number }) {
  if (Math.abs(amount) < 0.005) return <span className="faint" style={{ fontSize: 12 }}>settled</span>;
  return (
    <span className="num" style={{ fontSize: 12.5, fontWeight: 700, color: amount > 0 ? 'var(--teal)' : 'var(--rose)' }}>
      {amount > 0 ? `you get ${moneyWhole(amount)}` : `you owe ${moneyWhole(-amount)}`}
    </span>
  );
}

function NewGroup({ onCancel, onCreated }: { onCancel: () => void; onCreated: (id: number) => void }) {
  const toast = useToast();
  const [name, setName] = useState('');
  const [members, setMembers] = useState('');
  const [busy, setBusy] = useState(false);
  async function create() {
    setBusy(true);
    try {
      const names = members.split(',').map((s) => s.trim()).filter(Boolean);
      const r = await api.createSplitGroup(name.trim(), names);
      toast('ok', 'Group created');
      onCreated(r.id);
    } catch (e) {
      toast('err', (e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="split-new">
      <input className="input" autoFocus placeholder="Group name, e.g. Goa trip" maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
      <input className="input" placeholder="Friends, comma separated (optional)" value={members} onChange={(e) => setMembers(e.target.value)} />
      <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
        <button className="btn btn-sm btn-ghost" onClick={onCancel}>Cancel</button>
        <button className="btn btn-sm btn-primary" disabled={!name.trim() || busy} onClick={create}>
          {busy && <LoaderCircle size={14} className="spinner" />} Create
        </button>
      </div>
    </div>
  );
}

function GroupView(props: {
  group: SplitGroup; people: Person[]; me: Me; onRename: () => void; onDelete: () => void;
  onChange: (action: () => Promise<SplitGroup | void>, ok?: string) => void;
  onAddExpense: () => void; onEditExpense: (e: SplitExpense) => void;
  onSettle: (prefill?: { from: number; to: number; amount: number }) => void;
}) {
  const { group: g, onChange } = props;
  const prompt = usePrompt();
  const name = (id: number) => {
    const m = g.members.find((x) => x.id === id);
    return m ? (m.isMe ? 'You' : m.name) : 'Someone';
  };
  const balance = (id: number) => g.balances.find((b) => b.memberId === id)?.net ?? 0;
  const mine = g.balances.find((b) => b.memberId === g.myMemberId);
  const pending = g.settlements.filter((s) => s.status === 'PENDING');
  const deleteRequests = g.expenses.filter((e) => e.deleteRequest);
  const addedBy = (e: SplitExpense) => (e.mine ? 'You' : e.addedBy !== null ? name(e.addedBy) : g.ownerName);
  const total = g.expenses.reduce((s, e) => s + e.amount, 0);
  const linkedLogins = new Set(g.members.map((m) => m.login).filter(Boolean));
  const [debtView, setDebtView] = useState<'simplified' | 'direct'>('simplified');
  const shownDebts = debtView === 'simplified' ? g.debts : g.directDebts;
  const anyDebts = g.debts.length > 0 || g.directDebts.length > 0;

  async function addMember() {
    const n = await prompt({ title: 'Add a person', label: 'Name', placeholder: 'e.g. Rahul' });
    if (n) onChange(() => api.addSplitMember(g.id, n), `${n} added`);
  }

  return (
    <>
      <div className="card">
        <div className="card-head">
          <div>
            <h3 className="card-title" style={{ fontSize: 18 }}>{g.name}</h3>
            <div className="card-sub">{g.isOwner ? 'Your group' : `${g.ownerName}'s group`} · {g.expenses.length} expenses · {money(total)} total</div>
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            {g.isOwner && <button className="icon-btn" aria-label="Rename" onClick={props.onRename}><Pencil size={15} /></button>}
            {g.isOwner && <button className="icon-btn danger" aria-label="Delete group" onClick={props.onDelete}><Trash size={15} /></button>}
          </div>
        </div>
        {mine && (
          <div className="split-me">
            <div><span>You paid</span><b className="num">{money(mine.paid)}</b></div>
            <div><span>Your share</span><b className="num">{money(mine.share)}</b><small>what you actually spent</small></div>
            <div>
              <span>{mine.net > 0.004 ? 'You get back' : mine.net < -0.004 ? 'You owe' : 'Balance'}</span>
              <b className="num" style={{ color: mine.net > 0.004 ? 'var(--teal)' : mine.net < -0.004 ? 'var(--rose)' : undefined }}>
                {Math.abs(mine.net) > 0.004 ? money(Math.abs(mine.net)) : 'Settled'}
              </b>
            </div>
          </div>
        )}
        <div className="split-members">
          {g.members.map((m) => {
            const net = balance(m.id);
            const b = g.balances.find((x) => x.memberId === m.id);
            return (
              <div className="split-member" key={m.id}>
                <span className="avatar" style={{ width: 30, height: 30, fontSize: 12 }}>{m.name.slice(0, 1).toUpperCase()}</span>
                <div className="split-member-main">
                  <div className="title">{m.isMe ? `${m.name} (you)` : m.name}</div>
                  <div className="sub">
                    {m.login ? <><Link2 size={11} /> @{m.login}</> : 'name only'}
                    {b && ` · paid ${money(b.paid)} · share ${money(b.share)}`}
                    {' · '}
                    <span style={{ color: net > 0.004 ? 'var(--teal)' : net < -0.004 ? 'var(--rose)' : undefined }}>
                      {net > 0.004 ? `gets ${money(net)}` : net < -0.004 ? `owes ${money(-net)}` : 'settled'}
                    </span>
                  </div>
                </div>
                {g.isOwner && !m.isMe && (
                  <>
                    <select className="select split-link" aria-label={`Link ${m.name} to a login`} value={m.login ?? ''}
                      onChange={(e) => onChange(() => api.updateSplitMember(g.id, m.id, { login: e.target.value }),
                        e.target.value ? `${m.name} linked to @${e.target.value}` : `${m.name} unlinked`)}>
                      <option value="">{m.login ? 'Unlink login' : 'Link login…'}</option>
                      {props.people.filter((p) => p.login === m.login || !linkedLogins.has(p.login)).map((p) => (
                        <option key={p.login} value={p.login}>{p.name} (@{p.login})</option>
                      ))}
                    </select>
                    <button className="icon-btn danger" aria-label={`Remove ${m.name}`}
                      onClick={() => onChange(() => api.removeSplitMember(g.id, m.id), `${m.name} removed`)}><X size={14} /></button>
                  </>
                )}
              </div>
            );
          })}
          {g.isOwner && <button className="btn btn-sm" onClick={addMember}><UserPlus size={14} /> Add person</button>}
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
          <button className="btn btn-primary" onClick={props.onAddExpense}><Receipt size={15} /> Add expense</button>
          <button className="btn" onClick={() => props.onSettle()}><HandCoins size={15} /> Settle up</button>
        </div>
      </div>

      {(anyDebts || pending.length > 0 || deleteRequests.length > 0) && (
        <div className="card">
          <div className="card-head" style={{ marginBottom: 6 }}>
            <h3 className="card-title">Who owes whom</h3>
            {anyDebts && (
              <div className="segmented" role="tablist" aria-label="How to show debts">
                <button role="tab" aria-selected={debtView === 'simplified'} className={debtView === 'simplified' ? 'on' : ''}
                  onClick={() => setDebtView('simplified')}>Simplified</button>
                <button role="tab" aria-selected={debtView === 'direct'} className={debtView === 'direct' ? 'on' : ''}
                  onClick={() => setDebtView('direct')}>Direct</button>
              </div>
            )}
          </div>
          {anyDebts && (
            <div className="card-sub">
              {debtView === 'simplified'
                ? 'The fewest payments that settle everyone up.'
                : 'What each person owes each other person, from the expenses between the two of them.'}
            </div>
          )}
          <div className="mini-list" style={{ marginTop: 10 }}>
            {anyDebts && shownDebts.length === 0 && (
              <div className="mini-row"><div className="grow sub">Nobody owes anyone here.</div></div>
            )}
            {shownDebts.map((d, i) => (
              <div className="mini-row" key={i}>
                <div className="grow"><div className="title">{name(d.from)} → {name(d.to)}</div></div>
                <span className="num" style={{ fontWeight: 700 }}>{money(d.amount)}</span>
                <button className="btn btn-sm" onClick={() => props.onSettle(d)}>Record payment</button>
              </div>
            ))}
            {pending.map((s) => (
              <div className="mini-row" key={`p${s.id}`}>
                <div className="grow">
                  <div className="title">{name(s.from)} paid {name(s.to)} {money(s.amount)}</div>
                  <div className="sub">{shortDate(s.date)} · {s.canDecide ? 'waiting for your approval' : `waiting for ${name(s.to)} to approve`}</div>
                </div>
                {s.canDecide && (
                  <>
                    <button className="btn btn-sm btn-primary" onClick={() => onChange(() => api.decideSettlement(g.id, s.id, true), 'Payment approved')}><Check size={14} /> Approve</button>
                    <button className="btn btn-sm" onClick={() => onChange(() => api.decideSettlement(g.id, s.id, false), 'Payment rejected')}>Reject</button>
                  </>
                )}
                {!s.canDecide && s.mine && (
                  <button className="icon-btn danger" aria-label="Cancel" onClick={() => onChange(() => api.deleteSettlement(g.id, s.id), 'Payment cancelled')}><X size={14} /></button>
                )}
              </div>
            ))}
            {deleteRequests.map((e) => (
              <div className="mini-row" key={`d${e.id}`}>
                <div className="grow">
                  <div className="title">
                    {e.deleteRequest!.mine ? 'You' : e.deleteRequest!.by !== null ? name(e.deleteRequest!.by) : 'Someone'} asked to delete “{e.description}”
                  </div>
                  <div className="sub">
                    {shortDate(e.date)} · {money(e.amount)} · {e.deleteRequest!.canDecide ? 'waiting for your approval' : `waiting for ${addedBy(e)} to approve`}
                  </div>
                </div>
                {e.deleteRequest!.canDecide && (
                  <>
                    <button className="btn btn-sm btn-danger" onClick={() => onChange(() => api.decideSplitDelete(g.id, e.id, true), 'Expense deleted')}><Trash size={14} /> Delete</button>
                    <button className="btn btn-sm" onClick={() => onChange(() => api.decideSplitDelete(g.id, e.id, false), 'Kept the expense')}>Keep</button>
                  </>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
      {!anyDebts && pending.length === 0 && deleteRequests.length === 0 && g.expenses.length > 0 && (
        <div className="alert" style={{ background: 'var(--teal-soft)', color: 'var(--teal)', borderColor: 'transparent' }}>
          <CircleCheck size={16} /> Everyone is settled up.
        </div>
      )}

      <div className="card">
        <h3 className="card-title">Activity</h3>
        {g.expenses.length === 0 && g.settlements.length === 0 ? (
          <p className="muted" style={{ fontSize: 13 }}>No expenses yet. Add the first one.</p>
        ) : (
          <div className="mini-list" style={{ marginTop: 10 }}>
            {[
              ...g.expenses.map((e) => ({ kind: 'e' as const, date: e.date, id: e.id, e })),
              ...g.settlements.filter((s) => s.status !== 'PENDING').map((s) => ({ kind: 's' as const, date: s.date, id: s.id, s })),
            ].sort((a, b) => b.date.localeCompare(a.date) || b.id - a.id).map((row) => row.kind === 'e' ? (
              <div className="mini-row" key={`e${row.id}`}>
                <span className="split-avatar"><Receipt size={14} /></span>
                <div className="grow">
                  <div className="title">
                    {row.e.description}
                    {row.e.deleteRequest && <span className="badge badge-rose" style={{ marginLeft: 8, fontSize: 10.5, verticalAlign: 'middle' }}>Delete requested</span>}
                  </div>
                  <div className="sub">
                    {shortDate(row.e.date)} · {name(row.e.paidBy)} paid {money(row.e.amount)}
                    {g.myMemberId !== null && ` · your share ${money(row.e.shares.find((x) => x.memberId === g.myMemberId)?.amount ?? 0)}`}
                    {!row.e.mine && ` · added by ${addedBy(row.e)}`}
                    {row.e.transactionId && ' · from your transactions'}
                  </div>
                </div>
                <span className="num" style={{ fontWeight: 700 }}>{money(row.e.amount)}</span>
                {row.e.mine ? (
                  <>
                    <button className="icon-btn" aria-label="Edit expense" onClick={() => props.onEditExpense(row.e)}><Pencil size={14} /></button>
                    <button className="icon-btn danger" aria-label="Delete expense"
                      onClick={() => window.confirm(`Delete "${row.e.description}"?`) && onChange(() => api.deleteSplitExpense(g.id, row.e.id), 'Expense deleted')}>
                      <Trash size={14} />
                    </button>
                  </>
                ) : !row.e.deleteRequest && (
                  <button className="icon-btn danger" aria-label="Ask to delete" title={`Ask ${addedBy(row.e)} to delete it`}
                    onClick={() => window.confirm(`Ask ${addedBy(row.e)} to delete "${row.e.description}"? It stays until they approve.`)
                      && onChange(() => api.deleteSplitExpense(g.id, row.e.id), `Asked ${addedBy(row.e)} to approve`)}>
                    <Trash size={14} />
                  </button>
                )}
              </div>
            ) : (
              <div className="mini-row" key={`s${row.id}`}>
                <span className="split-avatar"><HandCoins size={14} /></span>
                <div className="grow">
                  <div className="title">{name(row.s.from)} paid {name(row.s.to)}</div>
                  <div className="sub">{shortDate(row.s.date)} · {row.s.status === 'APPROVED' ? 'settlement' : 'rejected'}</div>
                </div>
                <span className="num" style={{ fontWeight: 700, textDecoration: row.s.status === 'REJECTED' ? 'line-through' : undefined }}>{money(row.s.amount)}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </>
  );
}

export function ExpenseDrawer({ group: g, initialTx, expense, onClose, onSave }: {
  group: SplitGroup; initialTx?: Transaction; expense?: SplitExpense; onClose: () => void; onSave: (e: NewSplitExpense) => void;
}) {
  const shareAmounts = expense?.shares.map((s) => s.amount) ?? [];
  const wasEqual = !expense || Math.max(...shareAmounts) - Math.min(...shareAmounts) < 0.015;
  const [description, setDescription] = useState(expense?.description ?? (initialTx ? initialTx.merchant ?? initialTx.reason : ''));
  const [amount, setAmount] = useState(expense ? String(expense.amount) : initialTx ? String(initialTx.amount) : '');
  const [date, setDate] = useState(expense?.date ?? initialTx?.date ?? toIso(new Date()));
  const [paidBy, setPaidBy] = useState<number>(expense?.paidBy ?? g.myMemberId ?? g.members[0].id);
  const [split, setSplit] = useState<'equal' | 'exact'>(wasEqual ? 'equal' : 'exact');
  const [included, setIncluded] = useState<number[]>(expense && wasEqual ? expense.shares.map((s) => s.memberId) : g.members.map((m) => m.id));
  const [exact, setExact] = useState<Record<number, string>>(
    expense && !wasEqual ? Object.fromEntries(expense.shares.map((s) => [s.memberId, String(s.amount)])) : {});
  const [tx, setTx] = useState<Transaction | null>(initialTx ?? null);
  const [linkedId, setLinkedId] = useState<string | null>(expense?.transactionId ?? null);
  const [q, setQ] = useState('');
  const [matches, setMatches] = useState<Transaction[] | null>(null);

  useEffect(() => {
    if (!q.trim()) return setMatches(null);
    const t = setTimeout(() => {
      api.transactions({ q: q.trim(), direction: 'debit', limit: 8 }).then((p) => setMatches(p.items)).catch(() => setMatches([]));
    }, 250);
    return () => clearTimeout(t);
  }, [q]);

  const total = Number(amount) || 0;
  const exactSum = useMemo(() => Object.values(exact).reduce((s, v) => s + (Number(v) || 0), 0), [exact]);
  const each = included.length ? total / included.length : 0;
  const valid = description.trim() && total > 0 && (split === 'equal' ? included.length > 0 : Math.abs(exactSum - total) < 0.005);

  function pickTransaction(t: Transaction) {
    setTx(t);
    setDescription(t.merchant ?? t.reason);
    setAmount(String(t.amount));
    setDate(t.date);
    if (g.myMemberId !== null) setPaidBy(g.myMemberId);
    setQ('');
    setMatches(null);
  }

  function save() {
    const linked = tx?.id ?? linkedId;
    onSave({
      description: description.trim(), amount, date, paidBy, split,
      members: split === 'equal' ? included : undefined,
      shares: split === 'exact' ? Object.entries(exact).filter(([, v]) => Number(v) > 0).map(([id, v]) => ({ memberId: Number(id), amount: v })) : undefined,
      transactionId: linked && paidBy === g.myMemberId ? linked : undefined,
    });
  }

  return (
    <Drawer title={expense ? 'Edit expense' : 'Add expense'} sub={`${g.name} · not added to your Transactions or Overview`} onClose={onClose}
      foot={<><button className="btn btn-ghost" onClick={onClose}>Cancel</button><button className="btn btn-primary" disabled={!valid} onClick={save}>{expense ? 'Save changes' : 'Save expense'}</button></>}>
      {g.myMemberId !== null && (
        <div className="field">
          <span>Use one of your transactions <span className="hint">optional · fills in the details</span></span>
          {tx || linkedId ? (
            <div className="split-picked">
              <span style={{ flex: 1 }}>{tx ? `${tx.merchant ?? tx.reason} · ${shortDate(tx.date)} · ${money(tx.amount)}` : 'Linked to one of your transactions'}</span>
              <button className="icon-btn" aria-label="Clear" onClick={() => { setTx(null); setLinkedId(null); }}><X size={14} /></button>
            </div>
          ) : (
            <div className="input-icon">
              <Search size={15} />
              <input className="input" style={{ paddingLeft: 36 }} placeholder="Search your debits: payee or amount" value={q} onChange={(e) => setQ(e.target.value)} />
            </div>
          )}
          {matches && (
            <div className="split-matches">
              {matches.length === 0 && <div className="faint" style={{ padding: 8, fontSize: 12.5 }}>No matching debits</div>}
              {matches.map((t) => (
                <button key={t.id} type="button" onClick={() => pickTransaction(t)}>
                  <span style={{ flex: 1, textAlign: 'left' }}>{t.merchant ?? t.reason}</span>
                  <span className="faint">{shortDate(t.date)}</span>
                  <b className="num">{money(t.amount)}</b>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
      <div className="form-grid">
        <label className="field full">
          <span>Description</span>
          <input className="input" maxLength={60} placeholder="e.g. Dinner, Cab to airport" value={description} onChange={(e) => setDescription(e.target.value)} />
        </label>
        <label className="field">
          <span>Amount</span>
          <input className="input num" inputMode="decimal" placeholder="0" value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ''))} />
        </label>
        <label className="field">
          <span>Date</span>
          <input className="input" type="date" value={date} max={toIso(new Date())} onChange={(e) => setDate(e.target.value)} />
        </label>
        <label className="field full">
          <span>Paid by</span>
          <select className="select" value={paidBy} onChange={(e) => setPaidBy(Number(e.target.value))}>
            {g.members.map((m) => <option key={m.id} value={m.id}>{m.isMe ? `${m.name} (you)` : m.name}</option>)}
          </select>
        </label>
      </div>
      <div className="field">
        <span>Split</span>
        <div className="segmented" style={{ alignSelf: 'flex-start' }}>
          <button type="button" className={split === 'equal' ? 'on' : ''} onClick={() => setSplit('equal')}>Equally</button>
          <button type="button" className={split === 'exact' ? 'on' : ''} onClick={() => setSplit('exact')}>Exact amounts</button>
        </div>
      </div>
      <div className="split-shares">
        {g.members.map((m) => (
          <label key={m.id} className="split-share">
            {split === 'equal' ? (
              <input type="checkbox" checked={included.includes(m.id)}
                onChange={(e) => setIncluded((xs) => (e.target.checked ? [...xs, m.id] : xs.filter((x) => x !== m.id)))} />
            ) : <span style={{ width: 16 }} />}
            <span style={{ flex: 1 }}>{m.isMe ? `${m.name} (you)` : m.name}</span>
            {split === 'equal' ? (
              <span className="num faint">{included.includes(m.id) ? money(Math.round(each * 100) / 100) : '—'}</span>
            ) : (
              <input className="input num" style={{ width: 110, height: 34 }} inputMode="decimal" placeholder="0"
                value={exact[m.id] ?? ''} onChange={(e) => setExact((x) => ({ ...x, [m.id]: e.target.value.replace(/[^\d.]/g, '') }))} />
            )}
          </label>
        ))}
        {split === 'exact' && total > 0 && Math.abs(exactSum - total) >= 0.005 && (
          <div className="faint" style={{ fontSize: 12.5 }}>{money(total - exactSum)} {total > exactSum ? 'left to assign' : 'too much'}</div>
        )}
      </div>
    </Drawer>
  );
}

function SettleDrawer({ group: g, prefill, onClose, onSave }: {
  group: SplitGroup; prefill: { from: number; to: number; amount: number } | null; onClose: () => void;
  onSave: (s: { from: number; to: number; amount: string; date: string }) => void;
}) {
  const others = g.members.filter((m) => m.id !== g.myMemberId);
  const [from, setFrom] = useState<number>(prefill?.from ?? g.myMemberId ?? g.members[0].id);
  const [to, setTo] = useState<number>(prefill?.to ?? (others[0]?.id ?? g.members[0].id));
  const [amount, setAmount] = useState(prefill ? String(prefill.amount) : '');
  const [date, setDate] = useState(toIso(new Date()));
  const payee = g.members.find((m) => m.id === to);
  const needsApproval = payee && !payee.isMe && (payee.login || !g.isOwner);

  return (
    <Drawer title="Settle up" sub="Record money paid back between two people" onClose={onClose}
      foot={<><button className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={!(Number(amount) > 0) || from === to} onClick={() => onSave({ from, to, amount, date })}>Record payment</button></>}>
      <div className="form-grid">
        <label className="field">
          <span>Who paid</span>
          <select className="select" value={from} onChange={(e) => setFrom(Number(e.target.value))}>
            {g.members.map((m) => <option key={m.id} value={m.id}>{m.isMe ? `${m.name} (you)` : m.name}</option>)}
          </select>
        </label>
        <label className="field">
          <span>Paid to</span>
          <select className="select" value={to} onChange={(e) => setTo(Number(e.target.value))}>
            {g.members.map((m) => <option key={m.id} value={m.id}>{m.isMe ? `${m.name} (you)` : m.name}</option>)}
          </select>
        </label>
        <label className="field">
          <span>Amount</span>
          <input className="input num" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ''))} />
        </label>
        <label className="field">
          <span>Date</span>
          <input className="input" type="date" value={date} max={toIso(new Date())} onChange={(e) => setDate(e.target.value)} />
        </label>
      </div>
      {needsApproval && (
        <div className="alert alert-amber" style={{ marginTop: 8 }}>
          <Crown size={15} />
          <span>{payee?.login ? `${payee.name} has to approve this before it counts.` : `${g.ownerName} (the group owner) approves this on ${payee?.name}'s behalf.`}</span>
        </div>
      )}
    </Drawer>
  );
}

/** From a transaction row: pick a group, then split the transaction there. The transaction itself is not changed. */
export function SplitTransaction({ tx, onClose, onDone }: { tx: Transaction; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const [groups, setGroups] = useState<SplitGroupSummary[] | null>(null);
  const [group, setGroup] = useState<SplitGroup | null>(null);

  useEffect(() => {
    api.splitGroups().then((r) => setGroups(r.groups)).catch((e) => toast('err', e.message));
  }, [toast]);

  async function save(e: NewSplitExpense) {
    if (!group) return;
    try {
      await api.addSplitExpense(group.id, e);
      toast('ok', `Added to ${group.name}`);
      onDone();
    } catch (err) {
      toast('err', (err as Error).message);
    }
  }

  if (group) return <ExpenseDrawer group={group} initialTx={tx} onClose={onClose} onSave={save} />;
  return (
    <Drawer title="Split this transaction" sub={`${tx.merchant ?? tx.reason} · ${shortDate(tx.date)} · ${money(tx.amount)}`} onClose={onClose}
      foot={<button className="btn btn-ghost" onClick={onClose}>Cancel</button>}>
      <p className="muted" style={{ margin: 0, fontSize: 13 }}>
        Pick a group. The transaction stays exactly as it is in your Transactions; the split lives only in the group.
      </p>
      {!groups && <Skeleton h={120} />}
      {groups && groups.length === 0 && (
        <Empty icon={<Users size={26} />} title="No groups yet">
          <p>Create a group in the Split tab first, then come back.</p>
        </Empty>
      )}
      <div className="stack" style={{ gap: 6 }}>
        {groups?.map((g) => (
          <button key={g.id} className="split-group-row"
            onClick={() => api.splitGroup(g.id).then(setGroup).catch((e) => toast('err', e.message))}>
            <span className="split-avatar"><Users size={15} /></span>
            <span style={{ flex: 1, minWidth: 0, textAlign: 'left' }}>
              <span className="title">{g.name}</span>
              <span className="sub">{g.members} people{g.isOwner ? '' : ` · by ${g.ownerName}`}</span>
            </span>
            <Owes amount={g.myBalance} />
          </button>
        ))}
      </div>
    </Drawer>
  );
}
