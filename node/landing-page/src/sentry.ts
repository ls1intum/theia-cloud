import type { TheiaCloudConfig } from '@eclipse-theiacloud/common';
import * as Sentry from '@sentry/react';

const defaultDsn = 'https://a3851baf2ece0c6bec13e1c99fd3111f@sentry.aet.cit.tum.de/13';
let sentryInitialized = false;

function buildTraceTargets(serviceUrl?: string): Array<string | RegExp> {
  const targets: Array<string | RegExp> = [];

  if (serviceUrl) {
    try {
      targets.push(new URL(serviceUrl).origin);
    } catch {
      targets.push(serviceUrl);
    }
  }

  targets.push(window.location.origin);

  return targets;
}

export function initSentry(config?: TheiaCloudConfig): void {
  if (sentryInitialized) {
    return;
  }

  const sentryDsn = import.meta.env.VITE_SENTRY_DSN ?? defaultDsn;
  const sentryEnvironment = config?.sentryEnvironment ?? import.meta.env.MODE;

  if (!sentryDsn) {
    return;
  }

  Sentry.init({
    dsn: sentryDsn,
    environment: sentryEnvironment,
    tracesSampleRate: 1.0,
    tracePropagationTargets: buildTraceTargets(config?.serviceUrl),
    integrations: [Sentry.browserTracingIntegration()]
  });
  Sentry.setTag('component', 'landing-page');
  sentryInitialized = true;
}
