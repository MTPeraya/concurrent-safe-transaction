import React from 'react';
import {
  ArrowRight,
  Check,
  ChevronRight,
  History,
  LockKeyhole,
  ShieldCheck,
  Waypoints,
} from 'lucide-react';
import { Link } from 'react-router-dom';

const proofPoints = [
  {
    icon: Waypoints,
    title: 'Concurrency-safe',
    copy: 'Requests can arrive together without letting balances drift apart.',
  },
  {
    icon: LockKeyhole,
    title: 'Idempotent by design',
    copy: 'A retried transfer does not quietly become a second transfer.',
  },
  {
    icon: History,
    title: 'A clear history',
    copy: 'Every completed movement stays visible in the account ledger.',
  },
];

export const LandingPage: React.FC = () => {
  return (
    <main className="landing-page">
      <header className="landing-nav">
        <Link to="/" className="landing-brand" aria-label="ConcurrentSafe home">
          <span className="brand-mark" aria-hidden="true">
            <span />
            <span />
          </span>
          <span>ConcurrentSafe</span>
        </Link>

        <nav className="landing-nav-actions" aria-label="Public navigation">
          <a href="#how-it-works" className="landing-text-link">
            How it works
          </a>
          <Link to="/auth" className="landing-sign-in">
            Sign in
          </Link>
          <Link to="/auth" className="landing-nav-cta">
            Create account <ArrowRight size={16} aria-hidden="true" />
          </Link>
        </nav>
      </header>

      <section className="landing-hero">
        <div className="hero-copy">
          <div className="hero-rule" aria-hidden="true" />
          <h1>
            Move money.
            <br />
            Keep the record <em>true.</em>
          </h1>
          <p className="hero-lede">
            ConcurrentSafe keeps transfers correct when requests overlap, retries happen,
            and the ledger needs to tell the same story every time.
          </p>
          <div className="hero-actions">
            <Link to="/auth" className="landing-primary-cta">
              Start with an account <ArrowRight size={18} aria-hidden="true" />
            </Link>
            <a href="#how-it-works" className="landing-secondary-cta">
              See the safeguards <ChevronRight size={17} aria-hidden="true" />
            </a>
          </div>
          <p className="hero-note">
            <ShieldCheck size={15} aria-hidden="true" />
            Built for balances you can verify.
          </p>
        </div>

        <div className="ledger-stage" aria-label="Illustration of a settled transfer">
          <div className="ledger-sheet">
            <div className="ledger-topline">
              <span>SETTLEMENT SHEET / 001</span>
              <span className="ledger-live">
                <span className="live-dot" /> LIVE LEDGER
              </span>
            </div>

            <div className="ledger-title-row">
              <div>
                <span className="ledger-kicker">TRANSFER RECORD</span>
                <h2>Account movement</h2>
              </div>
              <span className="ledger-check" aria-label="Verified transfer">
                <Check size={17} strokeWidth={2.5} />
              </span>
            </div>

            <div className="ledger-transfer">
              <div className="ledger-account">
                <span className="account-index">01</span>
                <span className="account-name">Everyday account</span>
                <strong>$2,840.00</strong>
              </div>
              <div className="ledger-route" aria-hidden="true">
                <span className="route-line" />
                <span className="route-node route-node-start" />
                <span className="route-node route-node-end" />
                <span className="route-label">− $240.00</span>
              </div>
              <div className="ledger-account ledger-account-destination">
                <span className="account-index">02</span>
                <span className="account-name">Reserve account</span>
                <strong>$1,240.00</strong>
              </div>
            </div>

            <div className="ledger-divider" />
            <div className="ledger-meta">
              <span>
                <small>STATUS</small>
                <strong>COMPLETED</strong>
              </span>
              <span>
                <small>RECORD</small>
                <strong className="ledger-mono">TX-8F2A-19C</strong>
              </span>
              <span>
                <small>GUARD</small>
                <strong>ONCE ONLY</strong>
              </span>
            </div>
          </div>
          <div className="ledger-stamp">
            <ShieldCheck size={18} aria-hidden="true" />
            <span>VERIFIED<br />SETTLEMENT</span>
          </div>
        </div>
      </section>

      <section className="proof-rail" id="how-it-works">
        <div className="proof-intro">
          <span className="proof-index">THE RECORD</span>
          <p>Financial actions should leave evidence, not uncertainty.</p>
        </div>
        <div className="proof-list">
          {proofPoints.map(({ icon: Icon, title, copy }, index) => (
            <article className="proof-item" key={title}>
              <div className="proof-item-topline">
                <span className="proof-number">0{index + 1}</span>
                <Icon size={18} strokeWidth={1.7} aria-hidden="true" />
              </div>
              <h2>{title}</h2>
              <p>{copy}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="landing-bottom">
        <p>Open the ledger when you are ready to move.</p>
        <Link to="/auth" className="landing-bottom-link">
          Create your account <ArrowRight size={17} aria-hidden="true" />
        </Link>
      </section>

      <footer className="landing-footer">
        <span>ConcurrentSafe / transaction integrity, made visible.</span>
        <span>© 2026</span>
      </footer>
    </main>
  );
};
