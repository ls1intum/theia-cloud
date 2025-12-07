# Session Handling

## Approaches

There are currently two approaches to session handling, represented by the `SessionHandler` interface.

### Lazy Session Handler (default)

Creates sessions on-demand, i.e. when a session is requested, the operator will create the necessary resources to run the session.

The session is created with all environment variables and options set.

### Eager Session Handler

Creates generic sessions ahead of time, according to the `AppDefinition` spec.

The session is created with all environment variables known ahead of time, like the data bridge port.

#### Data Bridge component

As the environment can't be personalized for prewarmed sessions, we need a way of injecting configuration at runtime.

This is handled by the data bridge component, a VSCode extension exposing HTTP endpoints for injection data.

#### Environment Configuration for prewarmed sessionsw

Prewarmed sessions need to be configured in advance to allow successful data injection later.

-   `SCORPIO_THEIA_ENV_STRATEGY`: Sets the strategy to use for environment configuration. Must be set to `data-bridge` for prewarmed sessions.
-   `DATA_BRIDGE_PORT` (optional): Overrides the default port the data bridge component should listen on.
