# veracode-pipeline (Jenkins Shared Library)

Tenant-wide Veracode pipeline as a Jenkins Shared Library. One implementation, consumed by every repo, versioned by Git tag.

## Layout

```text
veracode-pipeline/        <- this Git repo
  vars/
    veracodePipeline.groovy
```

The `vars/` directory name is required by Jenkins. Register this repo under Manage Jenkins > System > Global Pipeline Libraries as `veracode-pipeline`, default version `v1`, "Allow default version to be overridden" on, "Load implicitly" off.

## Usage

In a consumer repo's `Jenkinsfile`:

```groovy
@Library('veracode-pipeline@v1') _
veracodePipeline()
```

### Optional overrides

```groovy
veracodePipeline(
    appName:          'acme-corp/payments-api',  // default: org/repo from JOB_NAME
    sourceDir:        'app',                      // default: repo root
    // topLevelBranches: 'main'                   // fallback only; normally unset
    // buildSteps:    { sh 'mvn -pl api -am package' }  // optional; see "Complex builds"
)
```

`VERACODE_APP_NAME` and `VERACODE_SOURCE_DIR` can also be set as folder/job env vars. `TOP_LEVEL_BRANCHES` / `topLevelBranches` is only an optional fallback used if the branch source does not populate `BRANCH_IS_PRIMARY`; normally you do not set it. Config map wins over env, env over defaults.

### Complex builds (`buildSteps`)

The `Package Artifacts` stage runs the Veracode CLI autopackager by default. The autopackager only handles projects it can detect and build (a supported language with a supported build file). Some repos cannot be packaged that way: multi-module Maven/Gradle, monorepos that emit several artifacts, compiled stacks that need a real build with specific flags first (for example .NET needing PDBs, C/C++ needing a generate-then-make step), code that needs generation before compile, or repos that already produce their own deployable artifact.

For those, pass a `buildSteps` closure. When present, it runs **instead of** the autopackager, and the rest of the pipeline (SCA, IaC/secrets, the Policy/SAST upload) is unchanged. This keeps one shared library: the build divergence lives in the consumer's `Jenkinsfile`, not in a forked copy of the template.

```groovy
@Library('veracode-pipeline@v2') _

veracodePipeline(
    appName:   'acme-corp/api-service',
    sourceDir: 'src',                 // SCA + IaC/secrets only; keep on real source, NOT verascan
    buildSteps: {
        sh '''
            set -e
            rm -rf verascan && mkdir -p verascan
            mvn -B -pl api,worker -am clean package -DskipTests
            cp api/target/*.jar worker/target/*.jar verascan/
        '''
    }
)
```

Contract and behavior:

- The closure **must** leave scannable artifacts in `verascan/`. The Policy/SAST stage unstashes and uploads that directory. The stage fails the build if `verascan/` is empty after `buildSteps` runs.
- `sourceDir` is independent of `buildSteps`. It still feeds SCA and IaC/secrets, so point it at real source, never at `verascan`.
- `buildSteps` runs only where packaging runs: the repo's default branch, post-merge. It never runs on PRs.
- The closure executes on the same agent as the job, so that agent needs the build toolchain it invokes (Maven/JDK, msbuild, and so on). Use agent labels accordingly.
- Absent `buildSteps`, the autopackager runs exactly as before. v2 is a backward-compatible superset of v1, so simple repos need no change.

Rule of thumb: if the difference is a value, pass an override and keep one version. If the difference changes what the Package or SAST stage executes, use `buildSteps` (or, for a different stage graph, a separate version), not a per-repo fork of the whole library.

## What it runs

| Scan | When | Input | Gating |
|------|------|-------|--------|
| Agent-Based SCA | every build | checked-out source | non-gating (skips if `srcclr-api-token` absent) |
| Container/IaC/Secrets | every build | checked-out source (`sourceDir`) | non-gating by default |
| Package + Policy (SAST) | repo default branch only (post-merge, not PRs) | autopackaged build output, or `buildSteps` output in `verascan/` | per your Veracode policy |

SCA and IaC/secrets are source-level and need no build. SAST autopackages a Veracode-appropriate (debug) build by default, or runs a repo-supplied `buildSteps` closure (see "Complex builds"); either way its agent needs the language toolchain. SAST runs only on the repo's default branch (detected via `BRANCH_IS_PRIMARY`), which means after a merge, never on PRs.

The library auto-detects the agent OS with `isUnix()` and runs the bash or PowerShell path accordingly, so one library serves Linux and Windows agents.

## Requirements

- Credentials resolvable by ID through the folder hierarchy: `veracode-api-id`, `veracode-api-key` (required), `srcclr-api-token` (optional).
- SCM checkout uses the credential configured on the org folder's branch source (the shared `scm-readonly` scan account in this deployment); the library does not handle SCM auth itself.
- Agent tooling: `curl`, `unzip`, `java` (JRE 8+); `bash` on Linux or `powershell` on Windows.
- Plugins: Pipeline, Declarative, Credentials Binding, Workspace Cleanup, Timestamper, **docker-workflow** (required for containerized SAST builds).

### Agent requirements for SAST packaging

The SAST stage compiles the repo's source code to produce a scannable artifact, exactly like the Veracode GitHub Actions workflow running on `ubuntu-latest`. There are two ways to provide the build toolchain:

**Option A -- Docker on the agent (recommended)**

Install Docker on the Jenkins agent and install the `docker-workflow` plugin on the controller. The pipeline auto-detects the repo language from build files (`pom.xml`, `*.csproj`, `package.json`, etc.) and pulls the matching container image to compile inside. No toolchain installation on the agent itself is needed.

Required setup:
1. Install Docker on the agent host.
2. Add the jenkins user to the docker group: `usermod -aG docker jenkins`
3. If Jenkins runs inside Docker, mount the host socket: `-v /var/run/docker.sock:/var/run/docker.sock`
4. Install the `docker-workflow` plugin on the Jenkins controller.

Language-to-image map (auto-detected, or override with `sastImage`):

| Language | Detected by | Docker image |
|---|---|---|
| Java/Maven | `pom.xml` | `maven:3.9-eclipse-temurin-21` |
| Java/Gradle | `build.gradle` | `gradle:8-eclipse-temurin-21` |
| .NET/C# | `*.csproj` / `*.sln` | `mcr.microsoft.com/dotnet/sdk:8.0` |
| Node.js | `package.json` | `node:20` |
| Python | `requirements.txt` / `pyproject.toml` | `python:3.12` |
| Go | `go.mod` | `golang:1.22` |
| Ruby | `Gemfile` | `ruby:3.3` |
| PHP | `composer.json` | `php:8.3-cli` |
| Rust | `Cargo.toml` | `rust:1.77` |

Override the image per-repo in the Jenkinsfile:
```groovy
veracodePipeline(sastImage: 'maven:3.9-eclipse-temurin-21')
```
Or set `VERACODE_SAST_IMAGE` as an environment variable on the org folder. Set to `none` to disable containerized builds and always use the bare agent.

**Option B -- Pre-installed toolchain on the agent**

Install the required language toolchains directly on the Jenkins agent (JDK + Maven/Gradle, .NET SDK, Node, etc.). Use `VERACODE_SAST_AGENT_LABEL` to route SAST jobs to agents that have the right toolchain. Set `sastImage: 'none'` to skip Docker detection.

**Option C -- Custom build steps**

For repos with complex builds, pass a `buildSteps` closure. See "Complex builds" above.

## Versioning

Versions are Git tags on this one repo. `v1`, `v2`, and so on are tags, not separate files and not separate repos: the same `vars/veracodePipeline.groovy` (and any other files in the library) at different commits. A consumer selects a version with the ref after `@`:

```groovy
@Library('veracode-pipeline@v1') _        // tag    - releases, immutable, reproducible
@Library('veracode-pipeline@develop') _   // branch - testing only, moves as the branch moves
@Library('veracode-pipeline@<sha>') _     // commit - exact freeze
```

Use tags for anything that runs in production. Branches and SHAs are only for trying a change before you tag it.

### Create the repo (first release)

1. Create the repo and add `vars/veracodePipeline.groovy`.
2. Commit, then tag and push the first release:

   ```bash
   git add . && git commit -m "veracode-pipeline v1"
   git tag v1
   git push origin main --tags
   ```

3. Register it in Jenkins (see top of this file): default version `v1`, override allowed, load implicitly off.

### Cut a new version (v2)

1. Branch from `main` and edit `vars/veracodePipeline.groovy`.
2. Canary it: point one test repo (or one branch) at the branch ref, `@Library('veracode-pipeline@<branch>')`, and run real builds. No tag yet.
3. When it is good, merge to `main`, then tag:

   ```bash
   git tag v2
   git push origin main --tags
   ```

   `v2` now exists alongside `v1`, and nothing about `v1` changes.
4. Promote consumers from `v1` to `v2` in waves. Roll back by re-pinning `v1`, which is still intact.

### Where a version is selected (most specific wins)

- Per repo (and per branch): the `@<ref>` in that repo's `Jenkinsfile`. This is how one repo runs a different version or tests a branch.
- Per org: a folder-scoped library override on the org folder, to move a whole org at once. The consumer must use the bare `@Library('veracode-pipeline')` (no `@ref`) or the repo pin will shadow the override.
- Fleet default: the global library default version, used only when the consumer specifies no `@ref`.

With override allowed, a `Jenkinsfile` `@ref` beats a folder override, which beats the global default.

See `SOLUTION.md` for the full multi-org rollout.
