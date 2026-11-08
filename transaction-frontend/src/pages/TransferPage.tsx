import React, { useEffect, useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { Account, Transaction } from '../types';

export const TransferPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const [accounts, setAccounts] = useState<Account[]>([]);
  const [sourceAccountId, setSourceAccountId] = useState(searchParams.get('from') || '');
  const [destAccountId, setDestAccountId] = useState('');
  const [amount, setAmount] = useState('50.00');
  const [idempotencyKey, setIdempotencyKey] = useState<string>(crypto.randomUUID());

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<{
    tx: Transaction;
    status: number;
    isReplay: boolean;
  } | null>(null);

  useEffect(() => {
    const loadAccounts = async () => {
      try {
        const res = await api.get<Account[]>('/accounts');
        setAccounts(res.data);
        if (!sourceAccountId && res.data.length > 0) {
          setSourceAccountId(res.data[0].id);
        }
      } catch (err: any) {
        if (err.response?.status === 401) {
          navigate('/auth');
        }
      }
    };
    loadAccounts();
  }, [navigate]);

  const generateNewKey = () => {
    setIdempotencyKey(crypto.randomUUID());
    setResult(null);
  };

  const handleTransfer = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const response = await api.post<Transaction>(
        '/transactions',
        {
          fromAccountId: sourceAccountId,
          toAccountId: destAccountId,
          amount: parseFloat(amount),
        },
        {
          headers: {
            'Idempotency-Key': idempotencyKey,
          },
        }
      );

      const isReplay = response.status === 200;
      setResult({
        tx: response.data,
        status: response.status,
        isReplay,
      });
    } catch (err: any) {
      const status = err.response?.status;
      const data = err.response?.data;
      let msg = data?.message || data?.error || 'Transfer failed.';

      if (status === 409) {
        msg = `[HTTP 409 Conflict] Idempotency conflict! This key was already used with different transfer parameters.`;
      } else if (status === 403) {
        msg = `[HTTP 403 Forbidden] You do not own the source account.`;
      } else if (status === 400 && msg.includes('Insufficient')) {
        msg = `[HTTP 400 Bad Request] Insufficient funds in source account.`;
      }

      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="page-container">
      <div className="page-header">
        <div>
          <h1>Initiate Money Transfer</h1>
          <p className="page-subtitle">
            PostgreSQL row-level locking guarantees concurrent safety and zero balance corruption.
          </p>
        </div>
      </div>

      <div className="grid grid-cols-3">
        {/* Transfer Form (2 cols) */}
        <div className="col-span-2 card">
          <h3>Transfer Details</h3>
          <p className="card-desc">Transfers execute atomically within a single transactional boundary.</p>

          {error && <div className="alert alert-error mt-4">{error}</div>}

          {result && (
            <div className={`alert ${result.isReplay ? 'alert-warning' : 'alert-success'} mt-4`}>
              <div className="result-header">
                <span className="badge badge-lg">
                  {result.isReplay ? 'HTTP 200 OK — Idempotent Replay' : 'HTTP 201 CREATED — Transfer Executed'}
                </span>
                <span className="timestamp">{new Date(result.tx.createdAt).toLocaleTimeString()}</span>
              </div>
              <p className="mt-2">
                <strong>Transaction ID:</strong> <code>{result.tx.id}</code>
              </p>
              <p>
                <strong>Status:</strong> {result.tx.status} | <strong>Amount:</strong> ${result.tx.amount}
              </p>
              {result.isReplay && (
                <p className="replay-note">
                  ℹ️ This request was recognized as a retry of an existing transfer. No additional money was deducted.
                </p>
              )}
            </div>
          )}

          <form onSubmit={handleTransfer} className="mt-4">
            <div className="form-group">
              <label htmlFor="sourceAccount">Source Account (Debited)</label>
              <select
                id="sourceAccount"
                className="form-control"
                value={sourceAccountId}
                onChange={(e) => setSourceAccountId(e.target.value)}
                required
              >
                <option value="">Select an account...</option>
                {accounts.map((acc) => (
                  <option key={acc.id} value={acc.id}>
                    {acc.ownerName} (${Number(acc.balance).toFixed(2)}) — {acc.id.slice(0, 8)}...
                  </option>
                ))}
              </select>
            </div>

            <div className="form-group">
              <label htmlFor="destAccount">Destination Account UUID (Credited)</label>
              <div className="input-group">
                <input
                  id="destAccount"
                  type="text"
                  required
                  placeholder="Paste recipient Account UUID (e.g. b13d0a68-...)"
                  value={destAccountId}
                  onChange={(e) => setDestAccountId(e.target.value.trim())}
                  className="form-control"
                />
              </div>
              {accounts.length > 1 && (
                <div className="quick-select">
                  <span>Quick pick owned destination: </span>
                  {accounts
                    .filter((a) => a.id !== sourceAccountId)
                    .map((a) => (
                      <button
                        key={a.id}
                        type="button"
                        onClick={() => setDestAccountId(a.id)}
                        className="badge-button"
                      >
                        {a.ownerName}
                      </button>
                    ))}
                </div>
              )}
            </div>

            <div className="form-group">
              <label htmlFor="amount">Transfer Amount ($)</label>
              <input
                id="amount"
                type="number"
                step="0.01"
                min="0.01"
                required
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                className="form-control"
              />
            </div>

            <div className="form-group idempotency-group">
              <div className="idempotency-header">
                <label htmlFor="idempotencyKey">
                  Idempotency-Key Header <span className="text-muted">(Transaction Advisory Lock)</span>
                </label>
                <button type="button" onClick={generateNewKey} className="btn-link">
                  Generate Fresh Key
                </button>
              </div>
              <input
                id="idempotencyKey"
                type="text"
                required
                value={idempotencyKey}
                onChange={(e) => setIdempotencyKey(e.target.value)}
                className="form-control font-mono"
              />
              <span className="help-text">
                💡 Tip: Click "Transfer Funds" once. Then click it again <em>without</em> regenerating the key to witness HTTP 200 Idempotent Replay in action!
              </span>
            </div>

            <div className="form-actions">
              <button type="submit" disabled={loading} className="btn btn-primary btn-lg">
                {loading ? 'Executing Transfer...' : '🚀 Execute Transfer'}
              </button>
            </div>
          </form>
        </div>

        {/* Informational Sidebar */}
        <div className="card info-card">
          <h4>Engineering Guarantees</h4>
          <ul className="info-list">
            <li>
              <strong>Pessimistic Row Lock:</strong>
              <p>Acquires <code>SELECT ... FOR UPDATE</code> in deterministic UUID order to eliminate deadlocks.</p>
            </li>
            <li>
              <strong>Advisory Locks:</strong>
              <p><code>pg_advisory_xact_lock(hash)</code> locks the idempotency key for the transaction duration.</p>
            </li>
            <li>
              <strong>Audit Trail:</strong>
              <p>Transactions table records sender, receiver, amount, and timestamp atomically with balance updates.</p>
            </li>
          </ul>
        </div>
      </div>
    </div>
  );
};
