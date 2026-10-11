import { useEffect, useState, type ReactNode } from 'react';
import { Braces, Info, KeyRound, LoaderCircle, Smartphone, Terminal, Zap } from 'lucide-react';
import { api, type Me } from '../api';
import { CopyField, Modal, useCopy, useToast } from '../ui';

const MASK = '••••••••••••••••';

export function Help({ me, onMeChanged }: { me: Me; onMeChanged: (me: Me) => void }) {
  const [key, setKey] = useState<string | null | undefined>(undefined);
  const [showJson, setShowJson] = useState(false);
  const url = `${window.location.origin}/api/transactions/sms`;

  useEffect(() => {
    api.phoneKey().then((r) => setKey(r.phoneKey)).catch(() => setKey(null));
  }, []);

  return (
    <div className="stack">
      <Connection me={me} url={url} phoneKey={key} onKey={(k) => { setKey(k); onMeChanged({ ...me, hasPhoneKey: true }); }}
        onShowJson={() => setShowJson(true)} />
      <ShortcutSteps url={url} hasKey={!!key} />
      {showJson && <JsonHelp url={url} phoneKey={key ?? null} onClose={() => setShowJson(false)} />}
    </div>
  );
}

function Connection(props: {
  me: Me; url: string; phoneKey: string | null | undefined; onKey: (key: string) => void; onShowJson: () => void;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);

  async function generate() {
    if (props.phoneKey && !window.confirm('Your current phone key will stop working. Generate a new one?')) return;
    setBusy(true);
    try {
      props.onKey((await api.newPhoneKey()).phoneKey);
      toast('ok', 'New phone key ready; update your Shortcut');
    } catch (e) {
      toast('err', (e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card">
      <div className="card-head">
        <div>
          <h3 className="card-title"><Smartphone size={16} /> Connect your phone or an app</h3>
          <div className="card-sub">The phone key (API token) only lets something send bank SMS into your account. It can't read anything.</div>
        </div>
        <button className="btn btn-sm" onClick={props.onShowJson}><Info size={14} /> How to send JSON</button>
      </div>
      <div className="stack" style={{ gap: 14 }}>
        <div className="grid row-1-1" style={{ gap: 14 }}>
          <div className="field"><span>Signed in as</span><div style={{ fontWeight: 600 }}>{props.me.name} <span className="faint">@{props.me.login}</span></div></div>
          <CopyField label="Send SMS to (URL)" value={props.url} />
        </div>
        {props.phoneKey === undefined ? (
          <div className="faint"><LoaderCircle size={14} className="spinner" /> Loading your key…</div>
        ) : props.phoneKey ? (
          <CopyField label="Phone key (API token)" value={props.phoneKey} secret />
        ) : (
          <p className="muted" style={{ margin: 0, fontSize: 13 }}>
            {props.me.hasPhoneKey
              ? 'Your key was created before keys could be shown again. Generate a new one and update your Shortcut.'
              : 'You have no phone key yet. Generate one, then use it in your Shortcut.'}
          </p>
        )}
        <div>
          <button className="btn btn-primary btn-sm" disabled={busy || props.phoneKey === undefined} onClick={generate}>
            {busy ? <LoaderCircle size={14} className="spinner" /> : <KeyRound size={14} />}
            {props.phoneKey || props.me.hasPhoneKey ? ' Replace key' : ' Generate key'}
          </button>
        </div>
      </div>
    </div>
  );
}

function Step({ n, title, children }: { n: number; title: string; children: ReactNode }) {
  return (
    <div style={{ display: 'flex', gap: 14 }}>
      <span className="rank" style={{ flex: 'none' }}>{n}</span>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontWeight: 700, marginBottom: 4 }}>{title}</div>
        <div className="muted" style={{ fontSize: 13.5, lineHeight: 1.6 }}>{children}</div>
      </div>
    </div>
  );
}

const Code = ({ children }: { children: ReactNode }) => (
  <code style={{ background: 'var(--surface-2)', padding: '1px 6px', borderRadius: 6, fontSize: 12.5 }}>{children}</code>
);

function ShortcutSteps({ url, hasKey }: { url: string; hasKey: boolean }) {
  return (
    <div className="card">
      <div className="card-head">
        <div>
          <h3 className="card-title"><Zap size={16} /> iPhone Shortcut, step by step</h3>
          <div className="card-sub">Reads your bank SMS on the phone and sends them here. Sanchiti works out the amount, payee, bank and date.</div>
        </div>
      </div>
      <div className="stack" style={{ gap: 18 }}>
        <Step n={1} title="Create the shortcut">
          Open the <b>Shortcuts</b> app → tap <b>+</b> → name it <i>Send bank SMS</i>.
        </Step>
        <Step n={2} title="Find Messages">
          Add <b>Find Messages</b>. Add the filter <b>Date Received</b> · <b>is in the last</b> · <b>1 day</b> (or 14 days for the
          first run). Sort by <b>Date Received</b>.
        </Step>
        <Step n={3} title="Repeat with Each">
          Add <b>Repeat with Each</b> and pick <b>Messages</b>. Everything in steps 4–7 goes <i>inside</i> the repeat.
        </Step>
        <Step n={4} title="Lowercase the text (for the check only)">
          Add <b>Text</b> → insert <b>Repeat Item › Body</b>. Then add <b>Change Case</b> → <b>lowercase</b>.
          Shortcuts' “contains” is case-sensitive, so this makes “Spent” and “spent” both match.
        </Step>
        <Step n={5} title="Keep only debit messages">
          Add <b>If</b> → <b>Updated Text</b> · <b>contains</b> · <Code>debit</Code>. Tap the condition to add more and set it
          to <b>Any</b>: <Code>spent</Code>, <Code>sent</Code>, <Code>paid</Code>, <Code>deducted</Code> — all lowercase, no spaces.
        </Step>
        <Step n={6} title="Build one row per SMS (inside the If)">
          Add <b>Dictionary</b> with three <b>Text</b> items:
          <Code>body</Code> = <b>Repeat Item › Body</b>, <Code>sender</Code> = <b>Repeat Item › Sender</b>,{' '}
          <Code>received</Code> = <b>Repeat Item › Date Received</b>. Use the original Body, not Updated Text.
          Then add <b>Text</b> → insert the <b>Dictionary</b>.
        </Step>
        <Step n={7} title="Collect the rows">
          Add <b>Add to Variable</b>, variable name <Code>Rows</Code>. Its input must be the <b>Text from step 6</b>: tap it →
          <b> Select Variable</b> → tap that Text action. (If it says Updated Text, the server gets plain text and rejects it.)
        </Step>
        <Step n={8} title="Join the rows (after End Repeat)">
          Add <b>Combine Text</b> → <b>Rows</b>, separator <b>Custom</b> → <Code>,</Code>. Then <b>Text</b> → insert
          <b> Combined Text</b>.
        </Step>
        <Step n={9} title="Send to Sanchiti">
          Add <b>Get Contents of URL</b>:
          <ul style={{ margin: '6px 0 0', paddingLeft: 18 }}>
            <li>URL: <Code>{url}</Code> (add <Code>?dryRun=true</Code> for a first test that saves nothing)</li>
            <li>Method: <b>POST</b></li>
            <li>Headers: <Code>Authorization</Code> = <Code>Bearer YOUR_PHONE_KEY</Code> (the word Bearer, a space, then
              your key{hasKey ? ' from the card above' : ''}) and <Code>Content-Type</Code> = <Code>application/json</Code>.
              Don't leave an empty header row.</li>
            <li>Request Body: <b>File</b> → the <b>Text</b> from step 8.</li>
          </ul>
        </Step>
        <Step n={10} title="Run it every day">
          Optional: add <b>Show Result</b> to see what was saved. Then in the <b>Automation</b> tab → <b>+</b> →
          <b> Time of Day</b> (e.g. 9 PM daily) → <b>Run Immediately</b> → run <i>Send bank SMS</i>. Re-sending the same SMS is
          safe: duplicates are skipped, and anything unreadable waits in <b>Review</b>.
        </Step>
      </div>
    </div>
  );
}

function JsonHelp({ url, phoneKey, onClose }: { url: string; phoneKey: string | null; onClose: () => void }) {
  const copy = useCopy();
  const keyText = phoneKey ?? 'YOUR_PHONE_KEY';
  const body = `{"messages": [
  {
    "body": "Sent Rs.189.00 From HDFC Bank A/C *1234 To CTRLX On 01/10/26 Ref 600000000001",
    "sender": "HDFCBK",
    "received": "2026-10-01T18:30:00+05:30"
  }
]}`;
  const curl = `curl -X POST '${url}?dryRun=true' \\
  -H 'Authorization: Bearer ${keyText}' \\
  -H 'Content-Type: application/json' \\
  -d '${body.replace(/\n\s*/g, ' ')}'`;
  const pre = { background: 'var(--surface-2)', borderRadius: 10, padding: 12, fontSize: 12, overflowX: 'auto' as const, margin: 0 };

  return (
    <Modal onClose={onClose}
      hero={<><Braces size={22} /><h2 style={{ margin: '8px 0 4px' }}>Send SMS as JSON</h2><div className="muted">For scripts, Android apps or anything that can make an HTTP request.</div></>}
      foot={<button className="btn btn-primary" onClick={onClose}>Got it</button>}>
      <div className="stack" style={{ gap: 14, fontSize: 13.5 }}>
        <div><b>POST</b> <Code>{url}</Code></div>
        <div>
          <b>Headers</b>
          <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>
            <li><Code>Authorization: Bearer {phoneKey ? MASK : 'YOUR_PHONE_KEY'}</Code></li>
            <li><Code>Content-Type: application/json</Code></li>
          </ul>
        </div>
        <div>
          <b>Body</b>
          <pre style={pre}>{body}</pre>
          <ul style={{ margin: '6px 0 0', paddingLeft: 18 }} className="muted">
            <li><Code>body</Code> (required): the SMS text exactly as received.</li>
            <li><Code>sender</Code> (optional): e.g. HDFCBK; helps identify the bank.</li>
            <li><Code>received</Code> (optional): date or ISO date-time; used when the SMS itself has no date.</li>
            <li>A bare array <Code>[…]</Code> or objects joined by commas (what Shortcuts sends) also work. Up to 500 messages, 1 MB.</li>
            <li>Add <Code>?dryRun=true</Code> to see how messages would be read without saving anything.</li>
          </ul>
        </div>
        <div>
          <b>Response</b>: <Code>inserted</Code> (saved transactions), <Code>duplicateIndexes</Code> (already saved),{' '}
          <Code>skipped</Code> (not a debit, e.g. OTPs), <Code>review</Code> (couldn't be read; fix them in Review),{' '}
          <Code>parsed</Code> (dry run only).
        </div>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', marginBottom: 6 }}>
            <b style={{ flex: 1 }}><Terminal size={14} /> Try it with curl</b>
            <button className="btn btn-sm" onClick={() => copy(curl, 'curl command')}>Copy</button>
          </div>
          <pre style={pre}>{curl.replace(keyText, phoneKey ? MASK : keyText)}</pre>
        </div>
      </div>
    </Modal>
  );
}
