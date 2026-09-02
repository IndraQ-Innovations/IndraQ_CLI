# IndraQ CLI

**One CLI for project setup, shared infrastructure, access control, Jenkins, AWS Route53, Nginx Proxy Manager, GHCR, Docker and deployments.**

> This guide matches **IndraQ CLI v1.9.1**.

**Jump to:** [Quick start](#quick-start) · [Recommended workflow](#recommended-workflow-in-chronological-order) · [Command reference](#command-reference) · [Common flows](#common-real-world-flows) · [Security](#security-model) · [Troubleshooting](#troubleshooting)

IndraQ CLI is built to remove the repetitive DevOps work developers normally have to remember or perform manually. Instead of opening Jenkins, AWS, Nginx Proxy Manager and multiple configuration files for every project, you use one consistent CLI from your terminal.

A developer can go from a project folder to a deployment with a workflow such as:

```text
login
  ↓
configure reusable credentials once
  ↓
init / register / link a project
  ↓
prebuild infrastructure
  ↓
deploy
```

The CLI keeps **user credentials** separate from **project infrastructure**, so the same AWS, Jenkins, NPM and GHCR credentials can be reused across every project the user is allowed to access.

---

## Why use IndraQ?

| Problem without IndraQ | What IndraQ does |
| --- | --- |
| Developers remember IPs, ports, Jenkins jobs and registry paths | Uses named environments and stored project associations |
| Provider credentials are copied between projects | Stores reusable credentials once in IndraQ Cloud |
| New projects need repetitive folder, Docker and environment setup | `indraq init` scaffolds the project safely |
| DNS, proxy and Jenkins setup drift apart | `indraq prebuild` reconciles them as one workflow |
| User access has to be changed in several systems | Unified user create/update/delete synchronizes providers |
| Jenkins jobs require opening the browser and remembering parameters | CLI can discover, prompt for and run any visible pipeline |
| AWS keys are difficult to recover or rotate safely | IndraQ can store managed IAM credentials encrypted and rotate them |
| Nobody knows who changed infrastructure | Manager/admin operations are audited in IndraQ Cloud |

The result is not just fewer commands. The important benefit is **repeatability**: two developers following the same IndraQ workflow should reach the same infrastructure state without having to know every provider-specific detail.

---

## The mental model

IndraQ is easier to understand if you remember three things.

### 1. Your reusable provider credentials

Each IndraQ user can store their own credentials for:

- AWS
- Nginx Proxy Manager (NPM)
- Jenkins Development
- Jenkins Production
- GitHub Container Registry (GHCR)

They are stored in IndraQ Cloud and reused across projects. They are not copied into every repository.

### 2. Shared environments

Admins can define friendly environment names that point to server targets, for example:

```text
production        → 10.0.0.10
production-backup → 10.0.0.11
staging           → 10.0.1.10
qa                → 10.0.2.20
```

Developers select `production` or `staging` instead of remembering the IP address.

### 3. Project infrastructure

A Cloud project can remember associations such as:

```text
Project
├── GHCR image
├── Jenkins deployment job
├── Route53 record
├── Route53 routing / health checks
├── NPM proxy host
├── environment
└── ports / deployment metadata
```

The local project normally needs only its Cloud project reference in:

```text
.indraq/project.json
```

---

## Roles and permissions

| Role | Typical responsibility |
| --- | --- |
| `user` | Work on permitted projects, configure personal providers, prebuild/deploy, use shared environments and manage their own supported credentials |
| `manager` | Everything a user can do, plus manage normal users, project membership/resource access, managed user AWS keys and audit logs |
| `admin` | Full organization control, including managers/admins, role changes, shared environments and admin-only Route53 health-check operations |

Important role rules:

- Managers can create and manage normal `user` accounts.
- Only admins can create managers/admins or change an existing IndraQ role.
- The last active admin cannot be deleted or demoted.
- An admin cannot use the role endpoint to demote their own active session.
- Role promotion/demotion is synchronized across linked managed providers before the Cloud role is committed.

Provider role mapping is intentional. Nginx Proxy Manager only provides `user` and `admin`, so IndraQ maps `manager` and `admin` to NPM `admin`. AWS and Jenkins use their IndraQ-managed role/policy mappings.

---

# Quick start

## 1. Install

Requirements:

- Node.js **22+**
- npm
- Git
- Docker when building/pushing Docker images
- network access to the IndraQ Cloud and providers you use

Install the published CLI:

```powershell
npm install -g indraq_cli
```

Check it:

```powershell
indraq --version
indraq doctor
indraq --help
```

When developing the CLI itself:

```powershell
npm install
npm run build
npm test
npm link
```

---

## 2. Login

```powershell
indraq login
```

Check the current identity:

```powershell
indraq whoami
```

The normal CLI uses the official IndraQ Cloud endpoint built into the package. Users do not need to configure an API URL manually.

If the account was created with a temporary password, the first login requires a new password before normal Cloud operations continue.

---

## 3. Configure your providers once

The easiest entry point is:

```powershell
indraq configure
```

It provides one menu for:

```text
Reusable provider credentials
Current project infrastructure / prebuild
Mobile settings
Provider status
```

You can also configure a provider directly:

```powershell
indraq provider configure aws
indraq provider configure npm
indraq provider configure jenkins-dev
indraq provider configure jenkins-prod
indraq provider configure ghcr
```

Review what is configured without exposing secrets:

```powershell
indraq provider list
```

---

## 4. Create or connect a project

For a brand-new application:

```powershell
indraq init
```

For an existing codebase that should become a new Cloud project without scaffolding it:

```powershell
indraq project create
```

For a folder that belongs to an existing Cloud project:

```powershell
indraq project link
```

Check the current association:

```powershell
indraq project status
```

---

## 5. Prepare infrastructure

```powershell
indraq prebuild
```

For web projects, prebuild is the main infrastructure reconciliation command. Depending on the project and selected options, it can prepare/reconcile:

```text
GHCR image association
Route53 DNS
Route53 routing / health checks
NPM reverse proxy / SSL
Jenkins deployment
project resource associations
```

Use it when creating a deployment for the first time **and** when infrastructure settings need to be reconciled later.

---

## 6. Deploy

Deploy with a named shared environment:

```powershell
indraq deploy build --env staging
```

Common shortcuts:

```powershell
indraq deploy:dev
indraq deploy:prod
```

The deployment flow builds the Docker image, pushes it to the configured registry and runs the configured Jenkins deployment.

---

# Recommended workflow in chronological order

| Step | Command | Why it matters |
| ---: | --- | --- |
| 1 | `indraq doctor` | Verify the machine and CLI command resolution before debugging anything else |
| 2 | `indraq login` | Authenticate to the organization control plane |
| 3 | `indraq configure` | Store reusable provider credentials once |
| 4 | `indraq environment list` | See which admin-managed server targets are available |
| 5 | `indraq init` / `project create` / `project link` | Establish the project correctly before infrastructure work |
| 6 | `indraq project status` | Confirm which Cloud project and resources the folder is using |
| 7 | `indraq prebuild` | Reconcile DNS, proxy, image and Jenkins infrastructure |
| 8 | `indraq deploy build --env <name>` | Build, push and deploy the application |
| 9 | `indraq logs` | Audit administrative/infrastructure activity when investigating changes |

For normal daily work after initial setup, developers usually spend most of their time around `project status`, `prebuild`, deployment commands and Jenkins pipeline commands.

---

# Command reference

`indraq --help` and `<command> --help` are the authoritative source for flags. The tables below explain **when to use each command and why it exists**.

Most list/select commands support some combination of:

```text
--search <text>   filter results
--limit <number>  control the number loaded
--all             load all results
```

## A. Help, diagnostics and authentication

| Command | Usage / importance |
| --- | --- |
| `indraq --help` | Show the complete live CLI command tree. Start here when you do not remember a command. |
| `indraq <command> --help` | Show flags and subcommands for one command. Best source for exact current syntax. |
| `indraq help [topic]` | Focused help for supported topics such as `configure`, `docker`, `mobile`, `aws` and `doctor`. |
| `indraq doctor` | Diagnose Node.js and command-resolution problems. Run this before deeper troubleshooting. |
| `indraq login` | Authenticate to IndraQ Cloud. Supports `--email` and `--password` for scripted use. |
| `indraq logout` | Remove the current Cloud session from the machine. |
| `indraq whoami` | Show the authenticated IndraQ identity. Useful before admin or production actions. |
| `indraq password reset` | Email-OTP password recovery for Cloud, NPM, Jenkins or all password-based accounts. AWS keys are intentionally separate. |
| `indraq cloud login` | Namespaced alias of `indraq login`. |
| `indraq cloud logout` | Namespaced alias of `indraq logout`. |
| `indraq cloud whoami` | Namespaced alias of `indraq whoami`. |

Examples:

```powershell
indraq help aws
indraq whoami
indraq password reset --email user@example.com --all
```

---

## B. Reusable providers and shared environments

Configure these before expecting project automation to work.

| Command | Usage / importance |
| --- | --- |
| `indraq configure` | Main configuration home. Recommended interactive entry point for providers, current-project prebuild and mobile settings. |
| `indraq provider configure [provider]` | Configure `aws`, `npm`, `jenkins-dev`, `jenkins-prod` or `ghcr` directly in Cloud. |
| `indraq provider list` | Show configured providers without exposing secrets. Useful before `prebuild`, user provisioning or Jenkins operations. |
| `indraq environment create` | **Admin:** create a reusable named server environment. Prevents users from memorizing IPs. |
| `indraq environment list` | List shared environments and targets. |
| `indraq environment update [name]` | **Admin:** update an environment target. Projects can keep using the friendly environment name. |
| `indraq environment delete [name]` | **Admin:** remove an environment. Use `--yes` only when intentionally bypassing confirmation. |
| `indraq credentials configure [provider]` | Compatibility namespace for provider configuration. Prefer `provider configure`. |
| `indraq credentials list` | Compatibility namespace for provider listing. Prefer `provider list`. |
| `indraq credentials sync` | Legacy credential synchronization command retained for compatibility. |
| `indraq credentials restore` | Legacy credential restore workflow retained for compatibility. |
| `indraq server create` | Deprecated alias that creates a shared environment. Prefer `environment create`. |
| `indraq server list` | Deprecated alias that lists shared environments. Prefer `environment list`. |

Example:

```powershell
indraq environment create
indraq environment list --all
indraq provider configure jenkins-prod
indraq provider list
```

---

## C. Organization users and roles

Unified user commands coordinate identities across IndraQ Cloud and selected configured providers.

| Command | Usage / importance |
| --- | --- |
| `indraq user list` | **Manager/admin:** list organization users. Use it before updates/deletion when you do not remember exact usernames. |
| `indraq user create [username]` | **Manager/admin:** create a Cloud user and selected AWS/NPM/Jenkins identities in one workflow. |
| `indraq user update [username]` | Update selected providers. **Admin role changes are synchronized across linked managed providers.** |
| `indraq user delete [username]` | Permanently delete the selected user's managed identities. External providers are handled before the Cloud row. |

Useful examples:

```powershell
indraq user create nitin --providers cloud,aws,npm,jenkins --role user
indraq user update nitin --role manager
indraq user update nitin --role user
indraq user delete nitin
```

### Role synchronization

When an admin changes a role, for example:

```powershell
indraq user update nitin --role manager
```

IndraQ reconciles linked providers in this direction:

```text
AWS role/policies
NPM role
Jenkins managed roles (DEV/PROD when linked)
        ↓
IndraQ Cloud role LAST
```

If an external role update fails, the Cloud role is not committed and already-changed external providers are rolled back on a best-effort basis.

### Permanent deletion

`indraq user delete` is a **real delete**, not a soft-disable command.

The unified flow deletes external identities first. Cloud deletion is intentionally last. If an external provider genuinely fails, the Cloud identity is retained so an administrator can repair the problem and retry.

When Cloud deletion runs, shared organization resources owned by the target user are preserved/reassigned rather than blindly destroyed, while user-scoped rows are removed according to their database relationships. The final Cloud user row is deleted.

A missing external account during deletion is considered an already-achieved final state where supported; deletion should be safe to retry.

---

## D. Project membership and resource access

Project membership and provider-native resource access are related but not identical.

| Command | Usage / importance |
| --- | --- |
| `indraq project add user` | **Manager/admin:** give a user access to a Cloud project. |
| `indraq project delete user` | **Manager/admin:** remove a user's Cloud project access. |
| `indraq access add user` | Grant a project user access to selected native AWS/NPM/Jenkins resources with audited synchronization. |
| `indraq access delete user` | Revoke selected native resource access without deleting the organization user. |

Use user deletion only when the person should be removed. Use access commands when the person remains in the organization but should gain/lose a project or resource.

---

## E. Project lifecycle

| Command | Usage / importance |
| --- | --- |
| `indraq init [directory]` | Safely create a new application and IndraQ project setup. Use `--skip-install` when you want files generated without dependency installation. |
| `indraq project create [name]` | Register an **existing current folder** as a new Cloud project without scaffolding or infrastructure changes. |
| `indraq project list` | List organization projects and your VIEW/WRITE access. |
| `indraq project link` | Link/re-link the current folder to an existing accessible Cloud project. |
| `indraq project sync` | Migrate older local IndraQ metadata to the Cloud model and keep the local project reference minimal. |
| `indraq project status` | Show the current Cloud project and infrastructure associations, optionally for an environment. |
| `indraq dockerfile create` | Generate Docker support for an existing project without running the full `init` workflow. |

### `indraq init`

Use this for a **new project**, not to overwrite a populated application accidentally.

The wizard can create supported frontend, backend, full-stack and mobile structures, environment files, ignore files and Docker support. Framework/version choices are collected interactively. Dependency installation can be skipped:

```powershell
indraq init my-service --skip-install
```

### `indraq project create`

Use this when code already exists and only Cloud registration is needed:

```powershell
indraq project create order-service --kind backend --framework express --port 5000 --health /health
```

It does not scaffold the application and does not automatically create DNS/proxy/Jenkins infrastructure.

### `indraq dockerfile create`

Common flags include:

```text
--framework <framework>
--node-version <version>
--output <directory>
--port <port>
--health <endpoint>
--compose
--force
```

---

## F. Infrastructure reconciliation and deployment

| Command | Usage / importance |
| --- | --- |
| `indraq prebuild` | Main web-infrastructure reconciliation command for GHCR, Route53, NPM and Jenkins. Run before first deployment and after infrastructure changes. |
| `indraq deployment create` | Deployment-oriented entry point to the prebuild/reconciliation flow. |
| `indraq deployment run --env <name>` | Run the configured deployment for a shared environment. |
| `indraq deploy build --env <name>` | Build Docker image, push it and run the Jenkins deployment. Primary explicit deploy command. |
| `indraq deploy configure` | Open the main configuration home from the deploy namespace. |
| `indraq deploy:configure` | Legacy/top-level alias for configuration. |
| `indraq deploy:dev` | Shortcut for deployment using the `dev`/development shared environment. |
| `indraq deploy:prod` | Shortcut for deployment using the `prod`/production shared environment. |

Useful prebuild flags:

```text
--env <environment>
--secondary-env <environment>
--routing SIMPLE|FAILOVER|WEIGHTED|LATENCY
--host-port <port>
--networks <list>
--volumes <list>
--flags <flags>
--yes
```

Example failover preparation:

```powershell
indraq prebuild --env production --secondary-env production-backup --routing FAILOVER
```

The purpose of prebuild is **reconciliation**, not blind creation. It should discover what already exists and create/update what the project requires.

---

## G. Jenkins: pipelines, deployments and users

Jenkins commands use the Jenkins credentials stored in the authenticated user's IndraQ Cloud profile.

| Command | Usage / importance |
| --- | --- |
| `indraq jenkins pipelines` | List all buildable jobs/pipelines visible to the Jenkins account, including jobs in folders. No default 10-job cap when `--limit` is omitted. |
| `indraq jenkins run [job]` | Select/run any visible pipeline. IndraQ discovers its Jenkins parameters and prompts for values. |
| `indraq jenkins pipeline:run [job]` | Long-form alias of `jenkins run`. |
| `indraq jenkins create-deployment` | Trigger only the Jenkins `CREATE-DEPLOYMENT` workflow for a component; it does not create Route53/NPM resources. |
| `indraq jenkins deployments` | List Jenkins deployment jobs. |
| `indraq jenkins deployment:update [job]` | Reconcile a deployment through `CREATE-DEPLOYMENT`. |
| `indraq jenkins deployment:delete [job]` | Delete a Jenkins deployment job after confirmation. |
| `indraq jenkins users` | List Jenkins users visible to the configured admin-capable identity. |
| `indraq jenkins user:update [username]` | Update a Jenkins user's password/roles directly. Unified organization role changes should normally use `indraq user update`. |
| `indraq jenkins user:delete [username]` | Delete a Jenkins user directly. Unified organization deletion should normally use `indraq user delete`. |

Choose Jenkins environment explicitly when useful:

```powershell
indraq jenkins pipelines --stage dev
indraq jenkins pipelines --stage prod
```

### Run any Jenkins pipeline

Interactive selection:

```powershell
indraq jenkins run
```

Direct job name:

```powershell
indraq jenkins run api-admin-service --stage dev
```

Folder jobs are supported using their full job path when visible.

IndraQ inspects the job's parameter definitions. Parameters with a usable Jenkins default are shown as **optional**; parameters without one are treated as **required**. Boolean, choice, password, text and string parameters get appropriate prompts.

By default the CLI waits for completion. To queue and return immediately:

```powershell
indraq jenkins run api-admin-service --stage dev --no-wait
```

### Create only a Jenkins deployment

Use this when DNS and proxy already exist or you intentionally want Jenkins only:

```powershell
indraq jenkins create-deployment --stage dev --component backend --image-name api-service --host-port 5010
```

Common optional deployment fields include networks, volumes and Docker flags.

---

## H. AWS IAM and managed access keys

IndraQ IAM users are designed for CLI/API access. Console login is intentionally not part of the IAM-user flow.

| Command | Usage / importance |
| --- | --- |
| `indraq iam users` | List AWS IAM users. |
| `indraq iam user:create [username]` | Create an IAM user and normally an access key; optional managed policy ARNs can be attached. |
| `indraq iam user:update [username]` | Update attached managed policies for an IAM user. |
| `indraq iam user:delete [username]` | Delete an IAM user and removable dependencies after confirmation. |
| `indraq iam credentials [username]` | Reveal the authenticated user's own IndraQ-managed AWS access key/secret stored encrypted in Cloud. |
| `indraq iam access-key rotate [username]` | Rotate an IndraQ-managed IAM access key safely; manager/admin can select managed organization users when authorized. |

Examples:

```powershell
indraq iam users --all
indraq iam user:create nitin
indraq iam credentials nitin
indraq iam access-key rotate nitin --reason "Quarterly credential rotation"
```

AWS does not reveal an old secret access key again after creation. For AWS identities created through the managed IndraQ workflow, the generated secret can be stored encrypted in IndraQ Cloud so the owner can retrieve it later through the authorized CLI command.

During rotation, IndraQ creates/stores the replacement managed key before removing the previous managed key so the recovery record does not point to a key that was never successfully created.

---

## I. Route53 DNS and health checks

| Command | Usage / importance |
| --- | --- |
| `indraq dns zones` | List Route53 hosted zones available to the configured AWS identity. |
| `indraq dns records` | List records in a hosted zone. Use `--zone` when scripting. |
| `indraq dns create [record]` | Create a Route53 record interactively or with flags. |
| `indraq dns update [record]` | UPSERT/reconcile a Route53 record. |
| `indraq dns delete [record]` | Select/delete Route53 record set(s). |
| `indraq healthcheck list` | List/select Route53 health checks. |
| `indraq healthcheck create` | **Admin:** create a Route53 health check. |
| `indraq healthcheck delete [id]` | **Admin:** delete a Route53 health check. |

Supported record types exposed by the command include:

```text
A
CNAME
TXT
MX
```

Supported routing choices include:

```text
SIMPLE
FAILOVER
WEIGHTED
LATENCY
```

Example:

```powershell
indraq dns create api.example.com --type A --environment production --routing SIMPLE
```

Failover example:

```powershell
indraq dns create api.example.com `
  --type A `
  --environment production `
  --secondary-environment production-backup `
  --routing FAILOVER
```

Health checks are especially important for failover designs because Route53 needs a reliable signal to decide whether a primary target is healthy.

---

## J. Nginx Proxy Manager: proxy hosts and users

### Proxy hosts

| Command | Usage / importance |
| --- | --- |
| `indraq proxy list` | List NPM proxy hosts. |
| `indraq proxy create [domain]` | Create a reverse proxy, with optional certificate/SSL/WebSocket settings. |
| `indraq proxy update [domain]` | Update an existing proxy host. |
| `indraq proxy delete [domain]` | Delete a proxy host after confirmation. |

Common proxy options include:

```text
--forward-host <host>
--environment <name>
--forward-port <port>
--scheme http|https
--certificate-id <id>
--request-ssl
--email <email>
--force-ssl
--http2
--websocket / --no-websocket
```

Example:

```powershell
indraq proxy create api.example.com --environment production --forward-port 5000 --request-ssl --force-ssl --http2
```

### NPM users

| Command | Usage / importance |
| --- | --- |
| `indraq npm-user list` | List Nginx Proxy Manager users. |
| `indraq npm-user create` | Create an NPM user directly. |
| `indraq npm-user update [identity]` | Update name, nickname, email, password, role or disabled state. |
| `indraq npm-user delete [identity]` | Delete an NPM user directly. |

For organization-wide lifecycle changes, prefer `indraq user create/update/delete` so Cloud, AWS, NPM and Jenkins stay synchronized.

---

## K. Mobile builds

Mobile build shortcuts are grouped under `indraq build`.

| Command | Usage / importance |
| --- | --- |
| `indraq build mobile:dev` | Build the mobile development environment. |
| `indraq build mobile:development` | Alias of `mobile:dev`. |
| `indraq build mobile:staging` | Build the mobile staging environment. |
| `indraq build mobile:prod` | Build the mobile production environment. |
| `indraq build mobile:production` | Alias of `mobile:prod`. |

Useful per-build overrides:

```text
--output dev-client|debug-apk|apk|aab
--profile fast|clean|full-reset
--verbose
--dry-run
--yes
```

Before a real upload/build, validate the plan when useful:

```powershell
indraq build mobile:staging --dry-run
```

Use `indraq configure` → **Mobile App settings** for the persistent mobile configuration.

---

## L. Audit logs

| Command | Usage / importance |
| --- | --- |
| `indraq logs` | **Manager/admin:** view IndraQ audit activity. Supports search/limit/all filtering. |

Example:

```powershell
indraq logs --search user.role --all
```

Audit logs are important when diagnosing who changed user roles, project access or infrastructure and why.

---

# Common real-world flows

| Goal | Recommended commands |
| --- | --- |
| Add a developer | `user create` → `project add user` → `access add user` |
| Promote/demote a person | `user update <name> --role manager|user` |
| Remove a person permanently | `user delete <name>` |
| Register an existing app | `project create` → `configure` → `prebuild` |
| Connect a checked-out repo | `project link` → `project status` |
| First web deployment | `prebuild --env <name>` → `deploy build --env <name>` |
| Later web deployment | `deploy build --env <name>` |
| Run an arbitrary Jenkins job | `jenkins pipelines` → `jenkins run` |
| Remove only project/resource access | `project delete user` / `access delete user` |

Use unified user commands for organization lifecycle changes. Direct AWS/NPM/Jenkins user commands are useful for provider-specific administration, but changing only one provider can intentionally create a state that differs from the IndraQ organization identity.

---

# Security model

IndraQ automates privileged systems, so convenience must not come at the cost of uncontrolled credentials.

| Area | Design |
| --- | --- |
| Cloud login | Authenticated session stored for the CLI rather than re-entering passwords for every command |
| Provider credentials | Stored per IndraQ user in Cloud rather than in each project repository |
| Encryption | Sensitive reusable provider secrets use the Cloud `MASTER_KEY` for encrypted-at-rest storage |
| AWS managed keys | Secret access keys created through the managed workflow can be retained encrypted for authorized recovery/rotation |
| Project access | VIEW/WRITE and resource grants control who can change project infrastructure |
| Role updates | External linked providers are reconciled before the Cloud role is finalized |
| User deletion | External cleanup happens before permanent Cloud identity deletion |
| Auditing | Sensitive manager/admin actions require/record reasons where the API enforces them |

Never commit `.env` files containing real secrets, AWS credentials, Jenkins tokens, NPM passwords or GHCR tokens to Git.

Keep the Cloud `MASTER_KEY` stable. Changing or losing it after encrypted credentials are stored makes those existing encrypted values unreadable.

---

# Troubleshooting

| Symptom | First checks |
| --- | --- |
| `indraq` is not found | Run `npm run build`, `npm link` (development), open a new terminal, then `indraq doctor` |
| Provider credentials are missing | `indraq provider list`, then `indraq provider configure <provider>` |
| Jenkins crumb/authorization error | Confirm the correct `--stage` and that the stored Jenkins account has enough permission for that operation |
| Jenkins host port is busy | Choose another validated/suggested free host port before deployment creation |
| `prebuild` cannot reconcile resources | Check `whoami` → `project status` → `provider list` → `environment list`, then rerun |
| Unified deletion stops on a provider | Repair the genuine provider failure and retry; Cloud deletion is retained until external cleanup can complete |

For command syntax, use `indraq <command> --help` rather than guessing flags.

---

# Platform operator: IndraQ Cloud API

Most CLI users can skip this section. The team operating `api.indraq.com` should use `cloud-api/README.md` as the detailed server guide.

The Cloud API requires Node.js 22+, PostgreSQL, a strong `JWT_SECRET`, a persistent 64-hex-character `MASTER_KEY`, bootstrap-admin credentials for first startup, and SMTP when email password reset is enabled.

```powershell
cd cloud-api
npm install
Copy-Item .env.example .env
npm run build
npm start
```

Numbered SQL migrations in `cloud-api/sql/` run automatically at API startup. Do not casually replace `MASTER_KEY`; existing encrypted provider secrets depend on it.

More implementation detail:

```text
cloud-api/README.md
docs/ARCHITECTURE.md
```

---

# Before publishing a CLI release

Run the complete verification sequence:

```powershell
npm install
npm run build
npm test
npm pack --dry-run
```

Inspect `npm pack --dry-run` before publishing. The package should contain the files required at runtime and must not contain private `.env` files, tokens, credentials or unrelated development artifacts.

Test the actual tarball when making a significant release:

```powershell
npm pack
npm install -g .\indraq_cli-<version>.tgz
indraq --version
indraq doctor
indraq --help
```

Then publish only after the packaged build behaves correctly:

```powershell
npm publish --dry-run
npm publish
```

---

# Where to go next

For day-to-day usage, these four commands cover most questions:

```powershell
indraq --help
indraq configure
indraq project status
indraq prebuild
```

For deployments:

```powershell
indraq deploy build --env <environment>
```

For Jenkins automation:

```powershell
indraq jenkins pipelines
indraq jenkins run
```

For exact flags on anything:

```powershell
indraq <command> --help
```

---

# License

MIT © IndraQ Innovations
