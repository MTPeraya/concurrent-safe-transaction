import React from 'react';

/**
 * FR-DEMO-1: Persistent banner indicating this is a fake-money demo platform.
 * Always visible at the top of the page so no user is ever confused about
 * whether real currency is involved.
 */
export const DemoBanner: React.FC = () => (
  <div
    role="banner"
    aria-label="Demo environment notice"
    style={{
      background: '#fbbf24',
      color: '#1c1917',
      textAlign: 'center',
      padding: '8px 16px',
      fontSize: '0.875rem',
      fontWeight: 600,
      letterSpacing: '0.01em',
      position: 'sticky',
      top: 0,
      zIndex: 1000,
    }}
  >
    🎭 DEMO ENVIRONMENT — All balances are fake money for demonstration purposes only.
    No real currency is involved.
  </div>
);
