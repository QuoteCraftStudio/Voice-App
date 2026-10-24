# QuoteCraft Studio — Permanent Codex Instructions

These instructions apply to every QuoteCraft Studio programming task unless the owner explicitly changes a requirement. Follow them in this order. Project-specific instructions may add detail but must not silently weaken the recovery and safety rules.

## 0. NON-NEGOTIABLE FIRST PRIORITY: SAVE AND MAKE WORK RECOVERABLE

**Protect existing work before editing. Save continuously during execution, not merely at the end.** Recovery takes priority over adding features or polishing code.

1. At the start of an existing project, inspect the working directory, Git status (including untracked files), recent commits, current branch, and any recovery notes. Never assume the previous Codex session finished or committed its work.
2. Before any risky changes, create a separate, timestamped backup or recovery snapshot that includes unfinished code and important configuration, but excludes secrets, credentials, private keys, and unnecessary generated files. Verify the backup can be read.
3. Divide work into small, independently testable sections. After **each** section: save files, run relevant tests, record results, create a descriptive Git commit for verified work when available, and update the recovery record. Never accumulate many finished sections without saving.
4. Do **not** wait for a usage-limit warning. Codex may stop without notice. Make durable checkpoints proactively, especially **before** long tool calls, builds, refactors, or deployments. If time or usage appears limited, stop starting new work and prioritize recovery and verification.
5. Preserve incomplete but valuable changes as a clearly labeled work-in-progress snapshot or separate recovery branch/archive; never merge untested changes into a verified stable release. Record failing tests and unfinished steps.
6. When an authorized GitHub remote is configured, push suitable verified checkpoints to a **non-production recovery branch**, subject to the owner's existing authorization. Confirm the remote commit exists. Otherwise make a clearly identified downloadable or persistent backup. A temporary Codex workspace or conversation alone is not a sufficient backup.
7. Never claim that a file, test, backup, commit, push, signing operation, or deployment succeeded without verification. Report missing tools or inaccessible storage as limitations.

### Required recovery records for substantive or continuing work

Maintain `PROGRESS.md` and `CODEX_RECOVERY.md` in the project root. Create them if absent. Update after each verified section and before stopping, without erasing useful prior history.

`PROGRESS.md` must contain: current objective, completed sections, remaining tasks, current blockers, versions, and test results.

`CODEX_RECOVERY.md` must contain: project name and absolute project path; date and current version; Git remote, branch, and last verified commit; backup location and verification result; changed and untracked files that matter; implemented and incomplete features; test commands/results; important architecture decisions; known defects; exact commands to restore, test, and resume; and the **single next action**. Do not put secrets in this document.

A new session resuming a substantive project must read the applicable `AGENTS.md`, `PROGRESS.md`, `CODEX_RECOVERY.md`, and relevant Git history **before** making changes. If any are missing, inspect actual files and reconstruct the record without guessing.

## 1. Understand and Protect the Project

- Interpret the owner's everyday language; ask only when a missing decision genuinely blocks safe work.
- Inspect relevant existing code and behavior before changing it. Identify the root cause of bugs, not just symptoms.
- Protect the last known working release, existing user data, and encryption or signing identities.
- Make narrowly scoped, reversible changes. Avoid unrelated refactoring and unnecessary dependencies.
- Preserve functioning interfaces, APIs, databases, website behavior, build workflows, and deployment settings unless a change is specifically authorized.

## 2. Implement in Small Verified Sections

For each section: **inspect → plan briefly → back up if risky → edit → test → save → checkpoint → update recovery notes → continue**.

- Write clear, secure, maintainable code; use helpful comments and documentation without clutter.
- Refactor only when it safely improves correctness or maintainability.
- Consider performance, memory use, compatibility, error handling, and edge cases.
- Run relevant focused tests after each section and appropriate regression/build tests before release. State plainly when a test cannot be run.
- Fix observed failures before declaring the affected section complete. Never treat a mocked integration test as proof of a real production connection.

## 3. Security and Change Authorization

- Never expose passwords, tokens, API keys, private certificates, customer data, or other confidential material in source, logs, archives, chat, or GitHub.
- Review authentication, permissions, dependencies, and security-sensitive changes. Use HTTPS/TLS correctly for networked websites and services.
- Android release updates must preserve the existing application signing identity; use the appropriate signing certificate/key only through secure authorized procedures.
- Sign Windows executables or installers when a valid authorized code-signing certificate is available. Never invent certificates or claim unsigned builds are signed.
- Do not delete data, rewrite history, force-push, deploy to production, publish websites, replace signing keys, or change production settings without explicit authorization.
- Maintain separate approval for consequential publishing/deployment operations even when editing access has been granted.

## 4. Versioning, Releases, and Backups

- Maintain consistent semantic versions where appropriate, build dates, and a meaningful `CHANGELOG.md`.
- Keep version information consistent in application UI, package metadata, installers, and release archives; do not add redundant version numbers to every source file.
- Preserve known-good versions and verify release archives can be opened. Record checksums when producing distributable packages.
- Verify actual build and install outputs on applicable platforms when possible; distinguish local tests from real-device or live-service verification.

## 5. Communication and Completion

- Explain important changes and any needed manual steps in simple English.
- For each checkpoint, report what was changed, what was tested, where it was saved, and what is still unresolved.
- At the end, verify that requirements were implemented, regression checks were run, version/changelog updates are appropriate, and recovery files point to accessible checkpoints.
- If interrupted or blocked, prioritize a truthful, precise recovery handoff over an unverified claim of completion.

**Operating rule: PRESERVE → ONE SECTION → TEST → SAVE → VERIFY CHECKPOINT → DOCUMENT RECOVERY → NEXT SECTION. Never postpone recovery until a usage limit approaches.**