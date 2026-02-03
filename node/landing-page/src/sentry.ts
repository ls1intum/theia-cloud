import type { TheiaCloudConfig } from '@eclipse-theiacloud/common';
import * as Sentry from '@sentry/react';

const defaultDsn = 'https://a3851baf2ece0c6bec13e1c99fd3111f@sentry.aet.cit.tum.de/13';
let sentryInitialized = false;

export function initSentry(config?: TheiaCloudConfig): void {
  if (sentryInitialized) {
    return;
  }

  const sentryDsn = import.meta.env.VITE_SENTRY_DSN ?? defaultDsn;
  const sentryEnvironment = config?.sentryEnvironment;

  if (!sentryDsn) {
    return;
  }

  Sentry.init({
    dsn: sentryDsn,
    environment: sentryEnvironment
  });
  Sentry.setTag('component', 'landing-page');
  sentryInitialized = true;
  console.log('Sentry initialized in environment:', sentryEnvironment);
}
