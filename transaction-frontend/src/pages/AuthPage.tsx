import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { AuthResponse } from '../types';

export const AuthPage: React.FC = () => {
  const [isRegister, setIsRegister] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSuccessMsg(null);
    setLoading(true);

    try {
      if (isRegister) {
        await api.post('/auth/register', { email, password });
        setSuccessMsg('Account created successfully! Please sign in.');
        setIsRegister(false);
      } else {
        const response = await api.post<AuthResponse>('/auth/login', { email, password });
        localStorage.setItem('token', response.data.accessToken);
        localStorage.setItem('userEmail', email);
        navigate('/accounts');
      }
    } catch (err: any) {
      const msg = err.response?.data?.message || err.response?.data?.error || 'Authentication failed. Please check your credentials.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-container">
      <div className="auth-card">
        <div className="auth-header">
          <div className="auth-icon">🛡️</div>
          <h2>{isRegister ? 'Create an Account' : 'Welcome Back'}</h2>
          <p className="auth-subtitle">
            {isRegister
              ? 'Register to experience concurrent-safe financial transactions'
              : 'Sign in to access your accounts and initiate safe transfers'}
          </p>
        </div>

        {error && <div className="alert alert-error">{error}</div>}
        {successMsg && <div className="alert alert-success">{successMsg}</div>}

        <form onSubmit={handleSubmit} className="auth-form">
          <div className="form-group">
            <label htmlFor="email">Email Address</label>
            <input
              id="email"
              type="email"
              required
              placeholder="e.g. alice@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="form-control"
            />
          </div>

          <div className="form-group">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              required
              placeholder="••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="form-control"
            />
          </div>

          <button type="submit" disabled={loading} className="btn btn-primary btn-block">
            {loading ? 'Processing...' : isRegister ? 'Register' : 'Sign In'}
          </button>
        </form>

        <div className="auth-switch">
          {isRegister ? (
            <p>
              Already have an account?{' '}
              <button
                type="button"
                onClick={() => {
                  setIsRegister(false);
                  setError(null);
                }}
                className="btn-link"
              >
                Sign In
              </button>
            </p>
          ) : (
            <p>
              Need an account?{' '}
              <button
                type="button"
                onClick={() => {
                  setIsRegister(true);
                  setError(null);
                }}
                className="btn-link"
              >
                Register now
              </button>
            </p>
          )}
        </div>
      </div>
    </div>
  );
};
