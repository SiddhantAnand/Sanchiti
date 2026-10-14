export type Direction = 'debit' | 'credit';

export type Transaction = {
  id: string;
  date: string;
  amount: number;
  currency: string;
  direction: Direction;
  reason: string;
  merchant: string | null;
  paymentMethod: string | null;
  upiRef: string | null;
  cardLast4: string | null;
  bank: string | null;
  category: string | null;
  categorySource: 'ai' | 'manual' | 'rule' | null;
  note: string | null;
  createdAt: string;
};

export type Page = { total: number; items: Transaction[] };

export type Summary = {
  from: string;
  to: string;
  currency: string;
  total: number;
  count: number;
  averagePerDay: number;
  invested: number;
  investedCount: number;
  ownTransfers: number;
  ownTransferCount: number;
  received: number;
  receivedCount: number;
  ownTransfersIn: number;
  net: number;
  byCategory: { category: string; total: number; count: number; sharePct: number }[];
  receivedByCategory: { category: string; total: number; count: number; sharePct: number }[];
  byBank: { bank: string; total: number; count: number }[];
  deductedByBank: { bank: string; total: number; count: number; spent: number; invested: number; ownTransfers: number }[];
  creditedByBank: { bank: string; total: number; count: number; received: number; ownTransfers: number }[];
  byPaymentMethod: { method: string; total: number; count: number }[];
  daily: { date: string; total: number }[];
  largest: { date: string; amount: number; payee: string; category: string | null }[];
  largestReceived: { date: string; amount: number; payee: string; category: string | null }[];
  frequentPayees: { payee: string; count: number; total: number }[];
  uncategorizedCount: number;
  otherCurrencies: { currency: string; total: number; count: number }[];
};

export type Insights = {
  headline: string;
  summary: string;
  highlights: string[];
  concerns: string[];
  savingTips: string[];
  unusualSpends: { reason: string; amount: number; why: string }[];
};

export type Analysis = {
  id: number;
  periodFrom: string;
  periodTo: string;
  model: string;
  stats: {
    week?: { total: number; count: number; invested: number; byCategory: Summary['byCategory'] };
    vsPreviousWeek?: { previousTotal: number; totalChange: number; totalChangePct: number | null };
  };
  insights: Insights;
  createdAt: string;
};

export type ReviewItem = { id: number; payload: unknown; issue: string; receivedAt: string };

/** Review items are bank SMS: the raw message, or {sms, parsed} when it was read but could not be accepted. */
export function reviewParts(payload: unknown): { sms: string | null; fields: Record<string, unknown> } {
  const obj = (v: unknown) => (v && typeof v === 'object' ? v : {}) as Record<string, unknown>;
  const p = obj(payload);
  const sms = p.sms ? obj(p.sms) : p;
  return {
    sms: typeof sms.body === 'string' ? sms.body : null,
    fields: { date: sms.received ?? sms.receiver ?? sms.date, ...obj(p.parsed) },
  };
}

export type IngestResponse = { inserted: Transaction[]; duplicateIndexes: number[] };

export type Me = { login: string; name: string; admin: boolean; hasPhoneKey: boolean };

export type UserRow = { login: string; name: string; admin: boolean; createdAt: string };

export type AiStatus = { enabled: boolean; source: 'settings' | 'environment' | 'none'; keyHint: string | null; model: string };

export type TransactionFields = {
  date: string;
  amount: string;
  reason: string;
  currency: string;
  bankName: string | null;
  paymentMethod: string | null;
  cardLast4: string | null;
  upiRef: string | null;
  note: string | null;
  direction: Direction;
};

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method !== 'GET') headers['X-Requested-With'] = 'fetch';
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 204) return undefined as T;
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    if (res.status === 401 && !path.startsWith('/api/auth/')) onUnauthorized();
    const message = data?.detail ? `${data.error}: ${data.detail}` : data?.error ?? `Request failed (${res.status})`;
    throw new ApiError(res.status, message);
  }
  return data as T;
}

const qs = (params: Record<string, string | number | undefined | null>) => {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== '') p.set(k, String(v));
  const s = p.toString();
  return s ? `?${s}` : '';
};

export const api = {
  me: () => request<Me>('GET', '/api/auth/me'),
  login: (login: string, password: string) => request<Me>('POST', '/api/auth/login', { login, password }),
  signup: (p: { invite: string; login: string; name: string; password: string }) =>
    request<Me>('POST', '/api/auth/signup', p),
  logout: () => request<void>('POST', '/api/auth/logout'),
  phoneKey: () => request<{ phoneKey: string | null }>('GET', '/api/account/phone-key'),
  newPhoneKey: () => request<{ phoneKey: string }>('POST', '/api/account/phone-key'),
  changePassword: (current: string, next: string) => request<void>('POST', '/api/account/password', { current, next }),
  createInvite: () => request<{ code: string; expiresAt: string }>('POST', '/api/account/invites'),
  users: () => request<{ users: UserRow[] }>('GET', '/api/account/users'),
  aiStatus: () => request<AiStatus>('GET', '/api/account/ai'),
  saveAi: (apiKey: string, model: string) => request<AiStatus>('PUT', '/api/account/ai', { apiKey, model }),
  clearAi: () => request<AiStatus>('DELETE', '/api/account/ai'),
  health: () => request<{ ok: boolean; ai: boolean }>('GET', '/health'),
  categories: () => request<{ categories: string[]; creditCategories: string[] }>('GET', '/api/categories'),
  addCategory: (name: string, direction: Direction) =>
    request<{ categories: string[]; creditCategories: string[] }>('POST', '/api/categories', { name, direction }),
  banks: () => request<{ banks: string[] }>('GET', '/api/banks'),
  addBank: (name: string) => request<{ banks: string[] }>('POST', '/api/banks', { name }),
  summary: (from: string, to: string) => request<Summary>('GET', `/api/summary${qs({ from, to })}`),
  span: () => request<{ first: string | null; last: string | null }>('GET', '/api/transactions/span'),
  transactions: (p: {
    from?: string; to?: string; category?: string; q?: string; bank?: string; kind?: string; direction?: Direction;
    limit?: number; offset?: number;
  }) => request<Page>('GET', `/api/transactions${qs(p)}`),
  createTransaction: (fields: TransactionFields, allowDuplicates = false) =>
    request<IngestResponse>('POST', `/api/transactions${qs({ allowDuplicates: String(allowDuplicates) })}`, fields),
  updateTransaction: (id: string, patch: Partial<TransactionFields> & { category?: string | null }) =>
    request<Transaction>('PATCH', `/api/transactions/${encodeURIComponent(id)}`, patch),
  deleteTransaction: (id: string) => request<void>('DELETE', `/api/transactions/${encodeURIComponent(id)}`),
  review: () => request<{ items: ReviewItem[] }>('GET', '/api/review?limit=500'),
  resolveReview: (id: number, fields: TransactionFields, allowDuplicates = false) =>
    request<Transaction>('POST', `/api/review/${id}/resolve${qs({ allowDuplicates: String(allowDuplicates) })}`, fields),
  discardReview: (id: number) => request<void>('DELETE', `/api/review/${id}`),
  retryReview: () => request<{ saved: number; alreadySaved: number; remaining: number }>('POST', '/api/review/retry'),
  categorize: () => request<{ categorized: number; pending: number }>('POST', '/api/categorize'),
  runAnalysis: (to?: string, force = false) =>
    request<{ status: string; analysis?: Analysis; reason?: string }>(
      'POST',
      `/api/analysis/run${qs({ to, force: force ? 'true' : undefined })}`,
    ),
  analyses: () => request<{ items: Analysis[] }>('GET', '/api/analysis?limit=20'),
  splitPeople: () => request<{ people: { login: string; name: string }[] }>('GET', '/api/split/people'),
  splitLinked: () => request<{ transactionIds: string[] }>('GET', '/api/split/linked'),
  splitGroups: () => request<{ groups: SplitGroupSummary[] }>('GET', '/api/split/groups'),
  createSplitGroup: (name: string, members: string[]) =>
    request<{ id: number }>('POST', '/api/split/groups', { name, members }),
  splitGroup: (id: number) => request<SplitGroup>('GET', `/api/split/groups/${id}`),
  renameSplitGroup: (id: number, name: string) => request<SplitGroup>('PATCH', `/api/split/groups/${id}`, { name }),
  deleteSplitGroup: (id: number) => request<void>('DELETE', `/api/split/groups/${id}`),
  addSplitMember: (id: number, name: string, login?: string) =>
    request<SplitGroup>('POST', `/api/split/groups/${id}/members`, { name, login }),
  updateSplitMember: (id: number, memberId: number, patch: { name?: string; login?: string }) =>
    request<SplitGroup>('PATCH', `/api/split/groups/${id}/members/${memberId}`, patch),
  removeSplitMember: (id: number, memberId: number) =>
    request<SplitGroup>('DELETE', `/api/split/groups/${id}/members/${memberId}`),
  addSplitExpense: (id: number, e: NewSplitExpense) => request<SplitGroup>('POST', `/api/split/groups/${id}/expenses`, e),
  updateSplitExpense: (id: number, expenseId: number, e: NewSplitExpense) =>
    request<SplitGroup>('PUT', `/api/split/groups/${id}/expenses/${expenseId}`, e),
  /** Deletes your own expense; for anyone else's it asks them to approve. */
  deleteSplitExpense: (id: number, expenseId: number) =>
    request<SplitGroup>('DELETE', `/api/split/groups/${id}/expenses/${expenseId}`),
  decideSplitDelete: (id: number, expenseId: number, approve: boolean) =>
    request<SplitGroup>('POST', `/api/split/groups/${id}/expenses/${expenseId}/delete/${approve ? 'approve' : 'reject'}`),
  settleSplit: (id: number, s: { from: number; to: number; amount: string; date: string }) =>
    request<SplitGroup>('POST', `/api/split/groups/${id}/settlements`, s),
  decideSettlement: (id: number, settlementId: number, approve: boolean) =>
    request<SplitGroup>('POST', `/api/split/groups/${id}/settlements/${settlementId}/${approve ? 'approve' : 'reject'}`),
  deleteSettlement: (id: number, settlementId: number) =>
    request<SplitGroup>('DELETE', `/api/split/groups/${id}/settlements/${settlementId}`),
};

export type SplitGroupSummary = { id: number; name: string; ownerName: string; isOwner: boolean; members: number; myBalance: number };

export type SplitGroup = {
  id: number;
  name: string;
  ownerName: string;
  isOwner: boolean;
  myMemberId: number | null;
  members: { id: number; name: string; login: string | null; isMe: boolean }[];
  expenses: {
    id: number; description: string; amount: number; date: string; paidBy: number;
    shares: { memberId: number; amount: number }[]; mine: boolean; transactionId: string | null;
    addedBy: number | null; deleteRequest: { by: number | null; mine: boolean; canDecide: boolean } | null;
  }[];
  settlements: {
    id: number; from: number; to: number; amount: number; date: string;
    status: 'PENDING' | 'APPROVED' | 'REJECTED'; canDecide: boolean; mine: boolean;
  }[];
  balances: { memberId: number; net: number; paid: number; share: number }[];
  debts: { from: number; to: number; amount: number }[];
};

export type NewSplitExpense = {
  description: string; amount: string; date: string; paidBy: number; split: 'equal' | 'exact';
  members?: number[]; shares?: { memberId: number; amount: string }[]; transactionId?: string;
};
