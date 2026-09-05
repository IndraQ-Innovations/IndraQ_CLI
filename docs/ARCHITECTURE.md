# IndraQ Architecture

## Control plane

IndraQ CLI communicates with IndraQ Cloud API. PostgreSQL stores identities, roles, projects, shared environments, encrypted user provider credentials, infrastructure associations, managed AWS IAM credentials, and audit logs.

## Credential ownership

AWS, NPM, Jenkins, and GHCR credentials belong to an individual IndraQ user and are reusable across that user's projects.

They are not organization-wide global secrets and are not copied into each project folder.

## Shared environments

Admins can create any number of shared environments with arbitrary names. An environment is a reusable named target containing an IP and optional region. Health-check protocol, endpoint, port, path, interval, threshold, latency measurement, and SNI are Route53-resource settings selected when DNS is configured.

PRIMARY/SECONDARY is not an environment property. A FAILOVER DNS resource chooses which environment is PRIMARY and which is SECONDARY for that particular Route53 record.

## Project resources

Projects keep intent and external-resource associations, including GHCR image repositories, Jenkins jobs, Route53 routing/health checks, and NPM proxy hosts. Project membership controls who can discover/re-link a project. Resource grants record access to individual AWS, NPM, and Jenkins resources and are audited.

## Prebuild

Prebuild reconciles rather than blindly creates. It validates user provider configuration, checks existing project associations, asks for the Route53 routing policy first (SIMPLE, FAILOVER, WEIGHTED, or LATENCY), validates Jenkins host ports, configures/repairs Route53 according to that policy, verifies DNS before NPM, and creates/repairs NPM proxy hosts. SIMPLE records never receive a health check; FAILOVER uses PRIMARY/SECONDARY health checks; WEIGHTED/LATENCY may use health checks when requested.

## Local state

The project folder stores the Cloud project ID in `.indraq/project.json`. Reusable provider secrets are resolved from the authenticated user's Cloud account only when required.
