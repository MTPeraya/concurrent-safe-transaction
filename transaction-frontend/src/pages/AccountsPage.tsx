import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { Account } from '../types';

export const AccountsPage: React.FC = () => {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Form states for creating new account
  const [ownerName, setOwnerName] = useState('');
  const [initialBalance, setInitialBalance] = useState('1000.00');
  const [creating, setCreating] = useState(false);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const navigate = useNavigate();

  const fetchAccounts = async () => {
    try {
      setLoading(true);
      setError(null);
      const res = await api.get<Account[]>('/accounts');
      setAccounts(res.data);
    } catch (err: any) {
      if (err.response?.status === 401) {
        navigate('/auth');
      } else {
        setError(err.response?.data?.message || 'Failed to load accounts.');
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAccounts();
  }, []);

  const handleCreateAccount = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!ownerName.trim()) return;

    try {
      setCreating(true);
      setError(null);
      await api.post('/accounts', {
        ownerName: ownerName.trim(),
        initialBalance: parseFloat(initialBalance) || 0,
      });
      setOwnerName('');
      setInitialBalance('1000.00');
      await fetchAccounts();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to create account.');
    } finally {
      setCreating(false);
    }
  };

  const copyToClipboard = (id: string) => {
    navigator.clipboard.writeText(id);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  return (
    <div className="page-container">
      <div className="page-header">
        <div>
          <h1>Accounts Dashboard</h1>
          <p className="page-subtitle">Manage your bank accounts and monitor real-time ledger balances.</p>
        </div>
        <button onClick={fetchAccounts} className="btn btn-secondary">
          🔄 Refresh Balances
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="grid grid-cols-3">
        {/* Account Creation Card */}
        <div className="card create-account-card">
          <h3>Create New Account</h3>
          <p className="card-desc">Open an account tied to your authenticated identity.</p>

          <form onSubmit={handleCreateAccount} className="mt-4">
            <div className="form-group">
              <label htmlFor="ownerName">Account Name / Label</label>
              <input
                id="ownerName"
                type="text"
                required
                placeholder="e.g. Alice Checking"
                value={ownerName}
                onChange={(e) => setOwnerName(e.target.value)}
                className="form-control"
              />
            </div>

            <div className="form-group">
              <label htmlFor="initialBalance">Initial Balance ($)</label>
              <input
                id="initialBalance"
                type="number"
                step="0.01"
                min="0.00"
                required
                value={initialBalance}
                onChange={(e) => setInitialBalance(e.target.value)}
                className="form-control"
              />
            </div>

            <button type="submit" disabled={creating} className="btn btn-primary btn-block">
              {creating ? 'Creating...' : '+ Open Account'}
            </button>
          </form>
        </div>

        {/* Accounts List (2 cols) */}
        <div className="col-span-2">
          {loading ? (
            <div className="card loading-card">Loading accounts...</div>
          ) : accounts.length === 0 ? (
            <div className="card empty-card">
              <div className="empty-icon">🏦</div>
              <h4>No accounts found</h4>
              <p>Create your first account using the form on the left to start transferring funds.</p>
            </div>
          ) : (
            <div className="account-grid">
              {accounts.map((acc) => (
                <div key={acc.id} className="card account-card">
                  <div className="account-card-header">
                    <div>
                      <h4 className="account-title">{acc.ownerName}</h4>
                      <div className="account-id-row">
                        <span className="account-id">{acc.id}</span>
                        <button
                          type="button"
                          onClick={() => copyToClipboard(acc.id)}
                          className="btn-copy"
                          title="Copy Account ID"
                        >
                          {copiedId === acc.id ? '✓ Copied' : '📋 Copy'}
                        </button>
                      </div>
                    </div>
                  </div>

                  <div className="account-balance-section">
                    <span className="balance-label">Current Balance</span>
                    <span className="balance-amount">
                      ${Number(acc.balance).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                    </span>
                  </div>

                  <div className="account-actions">
                    <button
                      onClick={() => navigate(`/transfer?from=${acc.id}`)}
                      className="btn btn-primary btn-sm"
                    >
                      Transfer Out
                    </button>
                    <button
                      onClick={() => navigate(`/history?account=${acc.id}`)}
                      className="btn btn-secondary btn-sm"
                    >
                      Audit Ledger
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
