import React from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';

export const Navbar: React.FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const email = localStorage.getItem('userEmail') || 'User';

  const handleLogout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('userEmail');
    navigate('/auth');
  };

  const navLinks = [
    { to: '/accounts', label: 'Accounts' },
    { to: '/transfer', label: 'Transfer Money' },
    { to: '/history', label: 'Transaction History' },
    { to: '/stress-test', label: '⚡ Concurrency Stress Test' },
  ];

  return (
    <header className="navbar">
      <div className="navbar-brand">
        <Link to="/accounts" className="logo">
          <span className="logo-icon">🛡️</span>
          <span className="logo-text">ConcurrentSafe Bank</span>
        </Link>
      </div>

      <nav className="nav-menu">
        {navLinks.map((link) => (
          <Link
            key={link.to}
            to={link.to}
            className={`nav-item ${location.pathname === link.to ? 'active' : ''}`}
          >
            {link.label}
          </Link>
        ))}
      </nav>

      <div className="navbar-user">
        <span className="user-email">{email}</span>
        <button onClick={handleLogout} className="btn btn-secondary btn-sm">
          Sign Out
        </button>
      </div>
    </header>
  );
};

