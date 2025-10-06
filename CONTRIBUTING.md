# Contributing Guide

Thanks for your interest in contributing! This document outlines the preferred workflow, coding style, and quality expectations.

## Table of Contents
- [Code of Conduct](#code-of-conduct)
- [Branches](#branches)
- [Issues & Discussion](#issues--discussion)
- [Commits](#commits)
- [Pull Requests](#pull-requests)
- [Code Style](#code-style)
- [Kotlin / Android](#kotlin--android)
- [AR & Native Changes](#ar--native-changes)
- [Security / Secrets](#security--secrets)
- [Checklists](#checklists)

## Code of Conduct
By participating you agree to abide by the [Code of Conduct](CODE_OF_CONDUCT.md).

## Branches
| Branch | Purpose |
|--------|---------|
| `main` | Stable, releasable state. Protected. |
| `feature/*` | New isolated feature work. |
| `bugfix/*` | Targeted bug fixes. |
| `hotfix/*` | Critical fix on `main` (PR & fast review). |

## Issues & Discussion
1. Search existing issues first.
2. Use templates (Bug / Feature). Provide logs, device info, reproduction steps.
3. For architectural proposals, open a "Proposal" issue.

## Commits
Use **Conventional Commits**:
```
feat: add QR pose smoothing
fix: null pointer in chat collapse
chore: bump AGP version
refactor: extract Supabase fetch logic
perf: reduce NV21 rotation allocations
docs: clarify model scale instructions
```
Include scope when useful: `feat(ar): ...`

## Pull Requests
- Rebase on latest `main` before opening.
- Keep PRs focused (avoid unrelated formatting).
- Include a short description + before/after (screenshots or logs) for UI/AR changes.
- Ensure CI passes (lint, assemble, tests when added).

## Code Style
- Kotlin official style (`kotlin.code.style=official`).
- Avoid over-optimization prematurely.
- Prefer explicit over implicit when clarity helps (e.g., suspend return types, interface boundaries).

## Kotlin / Android
- Keep Activities lean; extract helpers for complex logic (pose estimation, chat backends).
- Avoid blocking main thread (use `Dispatchers.IO` / `Default`).
- Guard AR frame operations to maintain FPS (throttle heavy work).

## AR & Native Changes
- Document magic numbers (e.g., stabilization thresholds) with inline comments.
- Provide test data or a short reasoning note for new math/pose formulas.

## Security / Secrets
- Never commit API keys.
- Use user-level `~/.gradle/gradle.properties` or CI secret store.
- Redact sensitive logs in issues and PRs.

## Checklists
### New Feature
- [ ] Added/updated docs (README or inline KDoc).
- [ ] No leaked secrets.
- [ ] Tested on at least one physical ARCore-capable device (if AR related).
- [ ] Logs contain no sensitive data.

### Bug Fix
- [ ] Reproduced original bug.
- [ ] Added guard / explanation.
- [ ] Verified fix & no regressions.

### Refactor
- [ ] Behavior unchanged.
- [ ] Simplifies code or reduces duplication.

### Docs Only
- [ ] Builds successfully.
- [ ] No broken links.

Happy building! 🎉

