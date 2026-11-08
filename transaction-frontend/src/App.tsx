import React from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { Navbar } from './components/Navbar';
import { AuthPage } from './pages/AuthPage';
import { AccountsPage } from './pages/AccountsPage';
import { TransferPage } from './pages/TransferPage';
import { HistoryPage } from './pages/HistoryPage';
import { StressTestPage } from './pages/StressTestPage';
import { LandingPage } from './pages/LandingPage';
import './App.css';

const ProtectedLayout: React.FC = () => {
  const token = localStorage.getItem('token');
  const location = useLocation();

  if (!token) {
    return <Navigate to="/auth" state={{ from: location }} replace />;
  }

  return (
    <div className="app-shell">
      <Navbar />
      <main className="main-content">
        <Routes>
          <Route path="/accounts" element={<AccountsPage />} />
          <Route path="/transfer" element={<TransferPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/stress-test" element={<StressTestPage />} />
          <Route path="*" element={<Navigate to="/accounts" replace />} />
        </Routes>
      </main>
    </div>
  );
};

export const App: React.FC = () => {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<LandingPage />} />
        <Route path="/auth" element={<AuthPage />} />
        <Route path="/*" element={<ProtectedLayout />} />
      </Routes>
    </BrowserRouter>
  );
};

export default App;
