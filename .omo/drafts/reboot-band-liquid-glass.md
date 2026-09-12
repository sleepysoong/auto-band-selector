---
slug: reboot-band-liquid-glass
status: complete
intent: clear
review_required: true
plan_path: .omo/plans/reboot-band-liquid-glass.md
plan_sha256: 0fdd2d62bd57315e0916096d9e23d9d1c36fbf47b2d428d2844edcd30c3e8d11
review_round_id: a52577aa-b328-4772-9fd6-d808ef463896
review_round_limit: 5
pending-action: deliver approved plan; await separate execution instruction
review:
  plan_reviewer:
    status: approved
    workspace_root: /root/auto-band-selector
    runtime_home: null
    target: .omo/plans/reboot-band-liquid-glass.md
    round_id: a52577aa-b328-4772-9fd6-d808ef463896
    plan_sha256: 0fdd2d62bd57315e0916096d9e23d9d1c36fbf47b2d428d2844edcd30c3e8d11
    launch_id: 44f93d49-0bc8-4943-9eb0-61b7e4b33d99
    session: st_01a094e7
    result: "[OKAY] Local and external references verified; tasks have concrete entry points, dependencies, commands and expected results. Execution requires authorized Fold6."
approach: On KT Fold6 with KT eSIM, every foreground Start performs fresh candidate-band speed comparison and applies the fastest verified band; Compose Backdrop UI, no boot auto-launch.
---

# Draft: reboot-band-liquid-glass

## Components (topology ledger)
<!-- Lock the SHAPE before depth. One row per top-level component that can succeed or fail independently. -->
<!-- id | outcome (one line) | status: active|deferred | evidence path -->

## Open assumptions (announced defaults)
<!-- Record any default you adopt instead of asking, so the user can veto it at the gate. -->
<!-- assumption | adopted default | rationale | reversible? -->

## Findings (cited - path:lines)
- Baseline HEAD bd9f2e2; clean worktree before planning.
- Existing Kotlin/XML app: app/build.gradle.kts:1-40, minSdk 26, target/compileSdk 34, no Compose.
- app/src/main/AndroidManifest.xml:1-52: AccessibilityService exists; boot receiver absent.
- BandSelectorService.kt:112-219: SIM2 hardcoded; band labels matched by substring; success persisted without UI readback.
- MainActivity.kt preserves carrier selection, scan, Automatic restoration, PiP and log sharing.
- Original article direct fetch failed with HTTP 403. Research pending; article contents not yet verified.
- AndroidLiquidGlass GitHub README currently identifies library as Compose Multiplatform Backdrop.
- Notepad: /tmp/ulw-20260912-063858.XO759v.md.

## Decisions (with rationale)
- intent: clear; review_required: true (ulw-plan default).
- Session tier LIGHT (plan-only artifacts); downstream implementation HEAVY (permissions, persistent state and concurrency).
- Cold-start question stance: one-by-one, switchable by user.
- Architect model unavailable; lead owns architecture. Source librarian and ultrabrain contracts lanes are independent read-only tasks.

## Scope IN
- Existing app automation reliability, reboot workflow, AndroidLiquidGlass design, existing-function regression and real Samsung QA plan.

## Scope OUT (Must NOT have)
- Product implementation in this session.
- Claims of device support or hidden-menu success without real-device evidence.
- New remote server or paid service absent an explicit need.

## Open questions
- Target Samsung model, Android/One UI, firmware carrier and SIM configuration: consult available memory first.
- Reboot trigger RESOLVED: user opens the app and presses Start; neither offered automatic-unlock nor notification option was selected.

## Approval gate
status: approved
<!-- When exploration is exhausted and unknowns are answered, set status: awaiting-approval. -->
<!-- That durable record is the loop guard: on a later turn read it and resume at the gate instead of re-running exploration. -->

## Grounding update — 2026-09-12
- Verified original article through exact command `curl --silent --show-error --location --max-time 30 https://r.jina.ai/http://arca.live/b/genshin/98684695`. Tool webfetch of both Jina variants returned a challenge page, but actual curl returned full Markdown. Title: "펌) 군대에서 나만 데이터 느릴때 해결법"; publication 2024-02-10 04:27:08.
- Article procedure: stock Samsung Phone; `319712358`; firmware-carrier password; Network Settings -> Network mode -> upper-left menu -> Band Selection; select bands then enable SELECTION. Inspect `*123456#` for actual connected band. Restore by disabling SELECTION, returning to Network Mode and verifying Automatic. Do NOT treat SELECTION as an unconditional submit button.
- Article final example allows a combination (B1+B7, excluding B5), not merely the single fastest band. Saved reboot profile must represent a set of bands rather than silently reducing the original request to one band. Existing per-band scan remains separate.
- Radio frequency/RSRP explanations in the article are anecdotal and are not implementation contracts or thresholds.
- Library source verified at `65ab177e90e5c1d8c62e70cf7755841982da65f6`: `backdrop/build.gradle.kts` declares Android minSdk 21, compileSdk 37, artifact version 2.0.1; catalog declares Kotlin 2.4.10 / Compose 1.12.0 / AGP 9.3.2. Upstream build configuration does not by itself establish the consuming app's required toolchain.
- Publication independently verified: https://repo1.maven.org/maven2/io/github/kyant0/backdrop/maven-metadata.xml reports release 2.0.1, lastUpdated 20260826040722.
- No assumption that full shader effects work on every supported Android version; precise effect fallback/toolchain contract remains to be established before plan handoff.
- Existing identity transcript file returned empty; other identity memory access denied and not retried. Durable current user requirements recorded via memory tool in notes/auto-band-selector.md.
- Owner question issued: first-unlock automatic application (recommended) versus notification-triggered application. Automatic option explicitly discloses visible phone/hidden-menu screen transitions.
- Next owner context: actual Samsung model / Android / One UI / firmware carrier / target SIM; no connected adb executable discovered on PATH.
- Source research child completed and its article/library core evidence independently checked. Macro-contracts child remains pending.

## Owner decision — Manual start after reboot
- User reply: "앱 실행하고 시작버튼".
- Classification: RESOLVED; this is a third outcome, not approval of the recommended automatic-unlock option.
- Execution entry point: foreground app Start button. Persist selected settings across reboot, but never persist an active run for automatic continuation.
- Exclude BOOT_COMPLETED receiver, unlock listener and reboot notification workflow. Preserve existing manual scan/restore behavior and distinguish saved-profile application from rescanning.
- Device model, Android/One UI and target SIM remain open; approach approval not yet requested.

## Macro-contract advisory integrated
- Child st_01a09457 completed; no open advisory children remain.
- Independently read source supports these defects: persisted macro_mode/target authorize service activity before a fresh Start (BandSelectorService.kt:19-25); MainActivity.onResume consumes persisted progress (MainActivity.kt:359-460); unconditional SELECTION click plus immediate success can invert selection on repeated events (BandSelectorService.kt:138-202); scalar substring matching confuses B1/B18 (BandSelectorService.kt:168-180); SIM2 is hardcoded (BandSelectorService.kt:108-115).
- Design contract: keep saved configuration, but require fresh in-memory user run authorization after reboot/process death. Clear or ignore legacy runnable preferences before service actions; opening the app alone remains idle.
- Serialize screen actions and correlate observations to current run/attempt; stale callbacks after Stop cannot restart work. Verify checkbox set and SELECTION state before acknowledging configured restriction. Distinguish configured restriction from actual registered radio band.
- Restoration is an explicit ordered workflow: SELECTION off, Network Mode Automatic, observable confirmation. Repeated events must not toggle it on again.
- Exact planned regression fixtures: legacy flags before Start; already-correct selection with duplicate events; B1/B10/B18/B19 controls; missing/offscreen controls; failed click/no state change; double resume plus Stop; requested SIM1 with SIM2 available; Wi-Fi/default-network measurement mislabeled as target SIM.
- Agent host probes and article interpretation are not Samsung-device proof. Final plan must require actual target-device screen evidence and deterministic event-driven tests without sleeps.
- Current status: waiting for user's model / Android / One UI / firmware-carrier / SIM reply. Do not re-explore settled trigger or request approval until device context is resolved.

## Device reply — Galaxy Z Fold6 KT
- User: "갤럭시 폴드6 KT".
- Confirmed target hardware family: Samsung Galaxy Z Fold6. KT is stated but firmware carrier versus active SIM carrier is not distinguished.
- Do not infer SIM2 from repository, or installed Android/One UI from device launch specifications.
- Next question narrows SIM/firmware configuration; use KT presets only after distinction is resolved.

## UI compatibility evidence — Owner answer pending
- Official https://kyant.gitbook.io/backdrop/api/backdrop-effects.md says RenderEffect-based effects require Android 12+; RuntimeShader effects including lens require Android 13+. Library minSdk 21 does not imply full effects on Android 8.
- Documented effect order: color filter -> blur -> lens. Lens requires CornerBasedShape and bounded refraction geometry.
- Official FAQ https://kyant.gitbook.io/backdrop/faq.md identifies missing `Modifier.layerBackdrop(backdrop)` as a cause of absent effects. UI QA must show sampled background changing under glass, not merely a translucent fill.
- Fold6 visual QA must include cover display, unfolded inner display and folding during an active run; preserve run identity and visible Stop action across configuration changes. These are native Android GUI scenarios, not browser mocks.
- Existing async SIM configuration question remains pending. No repeated question or additional device assumptions made.

## Approval brief — Confirmed target and proposed behavior
- Confirmed user reply: "kt 기기 kt esim". Target is KT-issued Galaxy Z Fold6 using KT eSIM. Firmware preset KT; SIM carrier KT; eSIM is not synonymous with hidden-menu SIM2.
- Reboot behavior is resolved: user launches app and presses Start. No boot/unlock receiver or reboot notification.
- Proposed Start behavior for approval: apply the saved user-selected band allowlist. Separate existing speed-comparison action; do not re-download speed tests on every Start. First-use setup requires selecting a nonempty band set, rather than assuming a fastest band.
- KT LTE presets B1/B3/B8 are candidate choices from existing code/article, not guaranteed working bands; actual menu availability controls supported choices. B5-exclusion example from U+ must not become a KT default.
- Keep Automatic restore, Stop, scan results and local log viewing/sharing. Restore and verification are explicit state-machine steps, not a blind SELECTION click.
- Use Kyant0 Backdrop for real sampled-background glass in Compose; Korean labels and existing visual identity retained, Fold6 cover/inner displays supported.
- Distribution default: existing personal sideload APK workflow; no store publication, remote server or paid service. No public device compatibility promise beyond tested firmware.
- Installed OS/build is a runtime preflight fact, not guessed from model. Executor records `adb shell getprop ro.build.version.sdk`, `adb shell getprop ro.build.version.release`, `adb shell getprop ro.build.version.oneui` and build fingerprint before device QA. If no device is accessible, report real-device QA as unverified; never replace it with emulator-only success.
- Test strategy: characterization for existing scan/restore entry contracts, deterministic failing-first state-machine regression tests, native app/UI automation on target Samsung and Fold6 render evidence. No product tests run during prose planning.
- Next action after approval: create .omo/plans/reboot-band-liquid-glass.md, plan-consultant gap analysis, structural checks and plan-reviewer review (default on, at most five rounds). Approval is for plan creation only, not implementation.
- Approval request will include review opt-out. No plan file or review child started before approval.

## Accepted final choice — Every Start scans
- After the approach brief and explicit two-way Start question, user selected "매번 속도 비교 후 적용". This resolves the offered fork and authorizes plan creation under the approval gate; not implementation.
- Supersedes saved-allowlist Start and separate-only speed comparison proposed above. Every Start freshly measures KT candidates B1/B3/B8, excludes unsupported/failed candidates and applies the highest valid measured result.
- Persist carrier/eSIM settings and historical results only. Do not reuse past measurements to choose this run's winner or resume old automation after reboot.
- Multi-band allowlist was source context, not an accepted feature: user now explicitly wants per-run speed comparison and fastest-band application. Do not add a manual multi-profile editor or combined-band optimizer.
- Required reviewer remains default on; no opt-out received.

## Plan completion and gap integration
- Plan written: 11 implementation rows + 4 final verifier rows, 3 waves, six explicit success criteria. All required section headers present/in order; no scaffold placeholders; 46,155 bytes read in full.
- Plan-consultant st_01a09462 completed. Integrated eSIM ambiguity, cellular network isolation, registered LTE readback, exact checkbox/toggle verification, medians/tie ordering, lifecycle run identity, PiP Stop, real-device-first gate, Backdrop spike and deterministic tests.
- Consultant recommendation "Stop automatically restores" declined as non-blocking: no user demand to restore on Stop; immediate cessation prevents additional unexpected UI actions. Explicit Restore starts a new authorized run; plan discloses distinction. Automatic restoration remains mandatory for failed/no-winner/final-apply-error paths where known UI is controllable.
- Discarded warm-up download not adopted: not a user requirement; three equal complete requests including connect time with fixed policy already define comparable metric. Limit remains 27 MB payload plus overhead; no extra unannounced traffic.
- Latest upstream build and publication inspected: Gradle 9.7.1, AGP 9.3.2, Kotlin2.4.10, Compose1.12.0. Artifact resolution/build is a downstream gate, not falsely reported as run in this planning session.
- No additional user interview recommended by consultant; installed firmware/eSIM-label feasibility remains task-1 evidence gate.
- Self-review: LIGHT held because only plan/draft edited; no product commands executed. Full plan QA-by-read and grammar check PASS; physical support explicitly unverified.
- Review round 1 initialized with descriptor-relative O_NOFOLLOW directory traversal and same-descriptor SHA256. All descriptors closed. Round active; blocker ledger initially empty.
- Review receipt: plan-reviewer st_01a0946a / plan-review-1 in_flight, bound to round 3be660fc-204a-420e-8c04-3f5ff0d8607d and SHA256 above. Do not edit the plan until verdict; parent will re-hash on receipt.

## Review round 1 terminal and round 2 recovery
- Round 1 receipt st_01a0946a terminated with `Codex error: The usage limit has been reached`. No verdict returned; disposition INCONCLUSIVE, not approval and no accepted blocker ledger.
- Parent revalidated descriptor-bound live hash equals 0fdd2d62bd57315e0916096d9e23d9d1c36fbf47b2d428d2844edcd30c3e8d11.
- Fresh round 2: 7d6e01b7-a5c9-42a3-a78f-83b50a91b3ab, launch 8f9398b7-6edc-45f9-9726-9f4ad540607f. Request same plan-reviewer through explicit alternate provider path; no plan changes.
- Round 2 receipt st_01a094e6 terminated before tool use: `No API key found for openai.` INCONCLUSIVE; no review verdict. No credentials changed.
- Model registry inspected with credential fields omitted: only openai-codex provider entries; gpt-5.6-sol is registered and previously ran the consultant. Round 3 a52577aa-b328-4772-9fd6-d808ef463896 tries that model with same plan-reviewer role. Live plan hash unchanged.
- Round 3 receipt st_01a094e7 in flight, plan-review-3. Await terminal verdict; no plan mutation.

## Final review receipt and completion audit
- Round 3 / st_01a094e7 returned `[OKAY]`; terminal completed. Reviewer verified cited local/external references and executable starting points, dependencies, commands and expected results. No blockers or notes requiring changes.
- Approved SHA256 revalidated through descriptor-relative no-follow traversal: `0fdd2d62bd57315e0916096d9e23d9d1c36fbf47b2d428d2844edcd30c3e8d11`; matches reviewed plan. No plan edits after review launch.
- C1 PASS: current code, article and published library evidence recorded; KT Fold6/eSIM and every-Start scan user decisions reflected. Hardware support explicitly depends on future task-1 real-device feasibility.
- C2 PASS: 11 implementation tasks, F1-F4, eight required sections, three dependency waves, all task reference/acceptance/happy/failure/category/commit fields present. Category mix: 10 deep, one visual-engineering. Six downstream criteria cover user behavior, failure, regression and native glass rendering.
- Planning-only verification: QA-by-read, structural validation and independent approved review. Product build, tests and physical device QA were not run or claimed in this session.
- Cleanup receipt: all children terminal; hash descriptors closed; no QA server/browser/device session active. Draft, plan and /tmp/ulw-20260912-063858.XO759v.md are intentionally retained planning/evidence artifacts.
- No planning-style episode recorded: replies resolved individual questions but did not establish a qualified recurring or declared question-rendering preference.
