import React, { useEffect, useState, useCallback } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { Account, PageResponse, Transaction } from '../types';

export const HistoryPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();

  const [accounts, setAccounts] = useState<Account[]>([]);
  const [selectedAccountId, setSelectedAccountId] = useState(searchParams.get('account') || '');
  const [historyPage, setHistoryPage] = useState<PageResponse<Transaction> | null>(null);
  const [currentPage, setCurrentPage] = useState(0);
  const [pageSize] = useState(10);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const loadAccounts = async () => {
      try {
        const res = await api.get<Account[]>('/accounts');
        setAccounts(res.data);
        if (!selectedAccountId && res.data.length > 0) {
          const initialId = res.data[0].id;
          setSelectedAccountId(initialId);
          setSearchParams({ account: initialId });
        }
      } catch (err: any) {
        if (err.response?.status === 401) {
          navigate('/auth');
        }
      }
    };
    loadAccounts();
  }, [navigate, searchParams, selectedAccountId, setSearchParams]);

  const fetchHistory = useCallback(async (accountId: string, page: number) => {
    if (!accountId) return;
    try {
      setLoading(true);
      setError(null);
      const res = await api.get<PageResponse<Transaction>>(
        `/accounts/${accountId}/transactions?page=${page}&size=${pageSize}`
      );
      setHistoryPage(res.data);
    } catch (err: any) {
      if (err.response?.status === 403) {
        setError('[HTTP 403 Forbidden] You do not have permission to view transaction history for this account.');
      } else {
        setError(err.response?.data?.message || 'Failed to load transaction history.');
      }
    } finally {
      setLoading(false);
    }
  }, [pageSize]);

  useEffect(() => {
    if (selectedAccountId) {
      fetchHistory(selectedAccountId, currentPage);
    }
  }, [selectedAccountId, currentPage, fetchHistory]);

  const handleAccountChange = (id: string) => {
    setSelectedAccountId(id);
    setCurrentPage(0);
    setSearchParams({ account: id });
  };

  const selectedAccount = accounts.find((a) => a.id === selectedAccountId);

  return (
    <div className="page-container">
      <div className="page-header">
        <div>
          <h1>Transaction Audit History</h1>
          <p className="page-subtitle">
            Cryptographically audited, paginated transaction history directly from the database.
          </p>
        </div>
      </div>

      <div className="card mb-6">
        <div className="filter-bar">
          <div className="form-group flex-1">
            <label htmlFor="selectAccount">Select Account to Audit</label>
            <select
              id="selectAccount"
              className="form-control"
              value={selectedAccountId}
              onChange={(e) => handleAccountChange(e.target.value)}
            >
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.ownerName} (${Number(a.balance).toFixed(2)}) — {a.id}
                </option>
              ))}
            </select>
          </div>

          {selectedAccount && (
            <div className="current-balance-badge">
              <span className="text-muted">Account Balance:</span>
              <span className="balance-val">${Number(selectedAccount.balance).toFixed(2)}</span>
            </div>
          )}
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="card">
        {loading ? (
          <div className="loading-card">Loading transactions...</div>
        ) : !historyPage || historyPage.content.length === 0 ? (
          <div className="empty-card">
            <div className="empty-icon">📜</div>
            <h4>No transactions recorded yet</h4>
            <p>Initiate a transfer to see audit records appear here.</p>
          </div>
        ) : (
          <>
            <div className="table-responsive">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Type</th>
                    <th>Transaction ID</th>
                    <th>Amount</th>
                    <th>Counterparty Account</th>
                    <th>Status</th>
                    <th>Timestamp</th>
                  </tr>
                </thead>
                <tbody>
                  {historyPage.content.map((tx) => {
                    const isDebit = tx.sourceAccountId === selectedAccountId;
                    return (
                      <tr key={tx.id}>
                        <td>
                          <span className={`direction-badge ${isDebit ? 'debit' : 'credit'}`}>
                            {isDebit ? 'OUTGOING' : 'INCOMING'}
                          </span>
                        </td>
                        <td>
                          <code className="font-mono text-sm">{tx.id.slice(0, 8)}...</code>
                        </td>
                        <td>
                          <span className={`amount-text ${isDebit ? 'text-danger' : 'text-success'}`}>
                            {isDebit ? '-' : '+'}${Number(tx.amount).toFixed(2)}
                          </span>
                        </td>
                        <td>
                          <code className="font-mono text-xs">
                            {isDebit ? tx.destinationAccountId : tx.sourceAccountId}
                          </code>
                        </td>
                        <td>
                          <span className="badge badge-success">{tx.status}</span>
                        </td>
                        <td className="text-muted text-sm">
                          {new Date(tx.createdAt).toLocaleString()}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            {/* Pagination Controls */}
            <div className="pagination-bar">
              <span className="page-info">
                Page {historyPage.number + 1} of {historyPage.totalPages} ({historyPage.totalElements} total entries)
              </span>

              <div className="pagination-buttons">
                <button
                  onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                  disabled={historyPage.first || loading}
                  className="btn btn-secondary btn-sm"
                >
                  ← Previous
                </button>
                <button
                  onClick={() => setCurrentPage((p) => p + 1)}
                  disabled={historyPage.last || loading}
                  className="btn btn-secondary btn-sm"
                >
                  Next →
                </button>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
};
