# Memora verification status

## Private remediation candidate — 2 November 2026

Target: **1.0.3 / code6**, package `com.personallibrary.app`, branch `codex/v1.0.1-final-qa`. Public availability remains V1.0.1; this candidate has no public-release or backend-deployment authorization.

- Local Android host gate: app328 passed with2 explicit benchmark opt-in skips; core66 passed; lint and Debug app/test assemblies passed.
- Server: typecheck/build and128 tests passed. Launcher assets3/3 and opt-in search/AI benchmarks passed.
- API37 Debug: all101 required unique executions have passing evidence across groups/reruns, not one clean sweep.
- API37 optimized: complete100/100 passed with zero failures/skips. Original AVD disks and lock preferences were preserved.
- Exact-source CI and permanent signing remain required before private APK delivery. The first candidate CI41497773423 failed because a query-contract test wrote a leftover diagnostic file under an absent ignored directory; its EXPLAIN/assertions passed locally. The diagnostic write is removed and the focused suite passed9/9 with zero failures/skips; exact-source CI remains required for the revised checkpoint.

Use the [root checkpoint](../CODEX_WORKLOG.md) for latest SHA/run identifiers, observed results and next action. [Candidate build procedure](QA_CANDIDATE_BUILD.md) defines the clean-source, five-job CI and permanent-key gates. Delivery receipts record exact APK hash, source and signing identity; a build or pending CI is not a delivered APK.

## Remaining acceptance

Physical in-place update/migration, realistic large-library responsiveness, fresh-device restore, actual speech recognition, OEM picker/viewer/reminder behavior, TalkBack and live/provider operation remain pending. Deferred server persistence findings F36/F37 remain open; F38 bounded cleanup is implemented. See the [active remediation plan](Memora_42_Finding_Remediation_Plan.md), [review rulings](CODEX_REMEDIATION_REVIEW_2026_10_27.md) and [owner handoff](HANDOFF.md). Automated host/emulator evidence does not close these items.

## Public release history

[V1.0.1/code3](https://github.com/RyanEid06/personal-library/releases/tag/v1.0.1) is the public release. Its permanent-signed APK source `5ac62b4ccbcdacdf6144827c8f09a3cfdeca5125` passed all five jobs in [CI39389151689](https://github.com/RyanEid06/personal-library/actions/runs/39389151689). Counts and limitations belong to that revision and are retained in the [historical V1.0.1 worklog](CODEX_V1_0_1_WORKLOG.md), [audit](CODEX_V1_0_1_AUDIT.md) and [physical checklist](CODEX_V1_0_1_PHYSICAL_QA.md).

[V1.0.0/code2](https://github.com/RyanEid06/personal-library/releases/tag/v1.0.0) was the first official release, built from `1c42f958f3e663d2e547a266670bae4fb54e5214` after [CI38959696966](https://github.com/RyanEid06/personal-library/actions/runs/38959696966). The permanent identity and historical APKs/receipts remain preserved. Completed planning drafts have been removed from the current tree and remain recoverable from Git history.
