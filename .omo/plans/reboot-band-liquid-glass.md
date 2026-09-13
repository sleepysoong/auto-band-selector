# reboot-band-liquid-glass - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**What you'll get:** KT 폴드6에서 시작을 누를 때마다 KT eSIM의 LTE 밴드 속도를 새로 비교하고, 가장 빠른 밴드를 확인해 적용하는 앱입니다. 실제 리퀴드글래스 화면과 중지·자동 모드 복구·결과·로그를 제공합니다.

**Why this approach:** 기존 삼성 히든메뉴 화면 조작을 개선하므로 별도 서버나 루팅 없이 같은 수동 절차를 자동화합니다. 클릭했다는 사실이 아니라 화면 상태와 실제 연결을 확인해 잘못된 성공 표시를 막습니다.

**What it will NOT do:** 재부팅만으로 실행되거나 지난 측정 결과를 그대로 적용하지 않습니다. 직접 앱을 열고 시작해야 하며, 5G·여러 밴드 조합 최적화는 추가하지 않습니다.

**Effort:** Large
**Risk:** High - 삼성 펌웨어별 히든메뉴 및 eSIM 연결 검증이 실제 기기에 달려 있습니다.
**Decisions to sanity-check:** KT LTE B1/B3/B8을 순서대로 측정하며 세 번의 3MB 다운로드 중앙값을 비교합니다. 전체 실행은 최대 27MB 본문 데이터와 통신 오버헤드를 사용합니다. 중지는 즉시 조작을 멈추며, 자동 모드 복구는 별도 버튼입니다.

Your next move: 정밀 계획 검토가 승인된 뒤 별도 실행을 시작하세요. 아래 내용은 구현·검증 지침이며 이 계획 세션에서는 제품 코드를 변경하지 않습니다.

---

> TL;DR (machine): Large downstream Android change, high device-compatibility risk; foreground per-run KT band scan, verified best-band apply/restore, Compose Backdrop UI.

## Scope
### Execution amendment — user no-device instruction (authoritative)
User explicitly said: "연결은 못하니 고려 ㄴㄴ 걍 나즁에 내가 직접 앱 깔아서 해보지 뭐". This supersedes EVERY physical-device prerequisite, physical QA completion gate and blocked-on-device clause below, including task1 and F3. Do not ask again for a connected device or stop implementation for one. Deliver an installable APK for the user to test later.

- Product behavior remains unchanged: every Start fresh KT B1/B3/B8 scan, KT eSIM routing, verified state transitions, explicit Stop/Restore, Backdrop UI and root APK.
- Task1 completes with real local JVM characterization, compiled Android instrumentation and host-runner negative tests; no connected device required. Task2 depends on independently verified Android baseline/build portion of task1; host runner may finish concurrently because it does not alter product/build inputs.
- Replace physical tasks2-10 scenario gates with their existing deterministic JVM/Robolectric/instrumentation-build assertions, plus Android emulator native UI validation if the environment can provide an emulator. Unit or emulator evidence is labeled exactly, never "Fold6 verified".
- No device-captured tree exists: synthetic accessibility fixtures may test logic and must be labeled synthetic. On-user-device runtime screen recognition must validate package/window/control state and fail with useful logs on unsupported firmware, rather than rely on fabricated preverified profiles.
- KT eSIM slot is resolved from runtime subscription data and visible SIM dialog; if mapping remains ambiguous, offer explicit in-app slot choice without silently assuming SIM2. Registered-band and SELECTION state must still be observed before reporting applied; if readback is unavailable, stop with diagnostic status, never fabricate speed success.
- Task11/F3 deliver APK hash/signature/package validation, automated results, available emulator UI evidence and a concise user install/test checklist covering fresh scan, eSIM, Stop/Restore and both Fold6 displays. Actual radio/network/folding results are explicitly NOT TESTED here and are assigned to the user's later trial.
- Keep RED/GREEN, no skipped/weakened tests, independent verification, cleanup, source/APK equality and local direct delivery. No physical radio/posture proof is needed to complete this run under the user's amendment.

### Must have
- Confirmed target: KT-issued Samsung Galaxy Z Fold6, KT eSIM. User explicitly selected: open app after reboot, press Start, compare speeds EVERY time, then apply fastest band. Never replace this with saved-band fast apply.
- Preserve application ID `com.sleepysoong.autobandselector`, Korean UI, existing cat icon/Pretendard identity, carrier settings, speed results, Stop, Automatic restore, PiP progress and local log view/copy/share/delete.
- Every run starts fresh with KT LTE candidates B1, B3, B8 in that order. Menu-absent candidates are unsupported, not zero-Mbps samples. No copied U+ B5-exclusion preset. Compare single LTE candidates; apply a single winning candidate.
- Operate Samsung Phone and its hidden-menu UI through AccessibilityService, using recognized text/resource IDs and freshly observed state, not root commands or a telephony band-setting API.
- Separate firmware carrier KT (password `774632`) from subscription carrier KT. Resolve the active embedded KT subscription; never assume eSIM means SIM2. If none/multiple or mapping unclear, do not start radio changes.
- KT eSIM must be the default mobile-data subscription before Start. Show the condition and a system-settings action if not; never switch user's default SIM automatically. Runtime subscription identity is resolved again for every run.
- Show current step, per-band valid/unsupported/failed status, median Mbps, final result, Restore and Stop. Distinguish "band restriction verified" from "currently registered band observed"; a click or speed result alone proves neither.
- Use actual `io.github.kyant0:backdrop:2.0.1` sampling/refraction, not only opacity gradients. Support Fold6 outer/inner display and fold/unfold continuity.
- Ship updated root `auto-band-selector.apk` as existing personal sideload artifact, with matching source build and SHA-256 evidence; no public release upload in this plan.
### Must NOT have (guardrails, anti-slop, scope boundaries)
- No boot receiver, unlock-triggered run, reboot notification, root/Shizuku dependency, remote-control server, account, telemetry, payment, location automation or Play Store submission.
- No stored run authorization/progress resumed after reboot or process death; persisted history is read-only history, never the next run's winner.
- No manual multi-profile editor, multi-band combination optimizer, 5G scanning expansion or promise that selecting a band increases speed everywhere.
- No unrestricted text matching/actions in arbitrary apps, blind coordinate macros, unconditional SELECTION toggling, `Thread.sleep`, stale AccessibilityNodeInfo retention or UI mutation after Stop.
- Do not suppress Gradle/lint errors, disable lint as the upstream demo does, relax tests, remove unrelated files, overwrite user's changes or introduce generic plugin/driver frameworks.
- No physical-device claim from article, JVM tests, emulator or static screenshot alone.

### Source map and chosen contracts
Paths below are relative to repository root. Line references are baseline HEAD `bd9f2e2`; resolve symbols after edits.

| Key | Evidence | Contract / limitation |
| --- | --- | --- |
| S | `app/src/main/java/com/sleepysoong/autobandselector/BandSelectorService.kt:19-25,108-115,138-202,255-279` | Persisted run flags, SIM2 hardcode, substring matches and immediate success need replacement. |
| A | `app/src/main/java/com/sleepysoong/autobandselector/MainActivity.kt:139-141,313-478,511-575` | Start currently scans, onResume starts duplicate jobs, speed HTTP uses default network; retain behavior, repair contracts. |
| C | `app/build.gradle.kts:1-40`, `build.gradle.kts:1-4`, `gradle/wrapper/gradle-wrapper.properties:1-7` | Current AGP 8.2.2/Kotlin 1.9.22/Gradle 8.5, SDK 26/34/34, no Compose/tests. |
| M | `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/accessibility_service_config.xml` | Existing accessibility/PiP/FileProvider configuration; no boot behavior needed. |
| V | `app/src/main/res/layout/activity_main.xml`, `res/drawable/ic_cat_launcher.xml`, `res/font/pretendard_font.xml` | Existing visual identity and controls; replace main layout with Compose, preserve assets. |
| P | `PROJECT.md`, `.omo/drafts/reboot-band-liquid-glass.md` | Baseline functional inventory and final user decisions. Later decisions override earlier proposed saved-band workflow. |
| Article | https://arca.live/b/genshin/98684695 (2024-02-10) | Stock Phone `319712358` -> password -> Network Settings -> Network mode -> menu -> Band Selection; SELECTION on enables restriction. Restore: SELECTION off, Network Mode Automatic. User-reported, firmware-dependent. |
| Backdrop | https://github.com/Kyant0/AndroidLiquidGlass/tree/65ab177e90e5c1d8c62e70cf7755841982da65f6 | `backdrop/build.gradle.kts`, `gradle/libs.versions.toml`, `androidApp/build.gradle.kts`, wrapper inspected; do not copy demo suppression flags. |
| Publication | https://repo1.maven.org/maven2/io/github/kyant0/backdrop-android/2.0.1/backdrop-android-2.0.1.module | Kotlin 2.4.10, Compose 1.12.0, shapes 1.2.1; artifact SHA-256 `009744ad39a09c886bb40261c2c3dbbb0094ec0ef680800af7d5600d37db7597`. |
| Effects | https://kyant.gitbook.io/backdrop/api/backdrop-effects.md and https://kyant.gitbook.io/backdrop/faq.md | Android 12+ RenderEffect, Android 13+ lens; color filter -> blur -> lens, CornerBasedShape and layerBackdrop capture required. |
| Android | https://developer.android.com/reference/android/accessibilityservice/AccessibilityService ; https://developer.android.com/reference/android/net/TelephonyNetworkSpecifier ; https://developer.android.com/reference/android/net/Network#openConnection(java.net.URL) | User-enabled service, subscription-scoped network request and per-Network HTTP; verify current API permissions during implementation. |

Exact internal structure (all under existing `app`, no new Gradle module): `automation/RunCoordinator.kt` owns one process-local run; `automation/MacroState.kt` owns typed states/events/results; `automation/SamsungScreenParser.kt` converts node trees to immutable observations; `automation/SamsungMacroDriver.kt` executes one expected-screen action; `network/KtSubscriptionResolver.kt` resolves current subscription; `network/CellularSpeedProbe.kt` measures on a validated cellular Network; `data/SettingsRepository.kt` persists configuration/history only; `ui/BandSelectorScreen.kt` and `ui/GlassTheme.kt` render StateFlow state. Existing MainActivity and BandSelectorService become Android adapters. Use an application-owned coordinator with injected collaborators, not a DI framework.

Run states: Idle -> Preflight -> EnterMenu -> ChooseSimIfShown -> ReadBandPage -> ConfigureCandidate -> VerifyCandidate -> AwaitCellular -> VerifyRegisteredBand -> MeasureCandidate -> VerifyRegisteredBand -> next candidate -> ChooseWinner -> ApplyWinner -> VerifyWinner -> VerifyRegisteredBand -> Completed. Restore uses DisableSelection -> NetworkModeAutomatic -> VerifyAutomatic. Terminal states also include Cancelled and Failed(stage, reason, recoveryStatus). All events carry runId and attemptId; terminal/cancelled identities reject further actions.

Registered-band verification uses the task-1-observed Samsung service-mode screen opened with `*123456#`, including the same eSIM context. Parse the exact LTE band and RAT before/after a candidate's three samples and after final application. If the screen is unavailable, ambiguous, on another SIM, NR rather than LTE, or mismatched, do not label the candidate measured/verified: recover or abort as appropriate. Configured checkbox readback alone cannot close measurement correctness. No new location permission or speculative RSRP threshold is required.

For every checkbox and SELECTION, compare observed checked state to desired state before a single action, then re-read. Exact normalized band IDs accept `LTE B1` independently of B10/B18/B19. Traverse/scroll all band rows with a finite page limit (20 unique page signatures); no missing target or unseen selected exclusion is treated as success. Package/window allowlist comes from task 1's verified Samsung components. Password entry is restricted to recognized hidden-menu screen; never log the password or full node text.

Timing policy: state deadlines 15 s, cellular reacquisition 20 s, HTTP connect/read 5 s, whole request 10 s. These are bounded production deadlines, not fixed test sleeps. Subscribe to accessibility/network callbacks before actions. One attempt per candidate; no unbounded retry. Candidate radio/network failure triggers verified Automatic restoration before trying the next; failed restoration aborts the run. A driver/screen/SIM mismatch aborts immediately without further speculative clicks.

Measurement policy: for each configured candidate, collect three complete 3,000,000-byte downloads from existing `https://speed.cloudflare.com/__down?bytes=3000000`, sequentially, with connection reuse/caching disabled and monotonic elapsed time measured from request start through final byte. HTTP non-200, short body, unexpected length, timeout or network loss invalidates that sample and candidate; no retry. Use median of three finite positive Mbps values. At most 27,000,000 payload bytes for B1/B3/B8 plus protocol overhead per full Start; show this estimate before Start. Use the Network returned by a cellular request for the chosen subscription; never global process binding or default URL.openConnection. Require cellular transport and chosen subscription match in observable capabilities; mismatches fail, never silently test Wi-Fi. Equal medians break toward earlier candidate order B1,B3,B8. No valid candidate -> restore Automatic and report no winner. Final winner application has its own verification, and failure restores Automatic rather than claiming completion.

Stop immediately invalidates run/attempt identity, cancels outstanding HTTP/callbacks and prevents every further automatic screen action. Keep current radio state labeled unknown/not restored unless previously verified; provide explicit Restore, which starts a NEW user-authorized restore run. Do not hide rollback actions under Stop. Accessibility loss, locking or process death similarly revoke authorization; on next launch show interrupted status and Restore without auto-resuming.

## Verification strategy
> All verification actions are agent-executed once an authorized target device is available. Device connection/unlock authorization is an external prerequisite, not a substitute for QA.
- Test decision: TDD for behavior (JUnit4 + Robolectric for Android adapters, kotlinx-coroutines-test for virtual scheduling, AndroidX Test/UiAutomator and Compose UI tests for native surface). Characterize existing carrier lists, Start-scan wiring and log sharing before refactoring; do not pin buggy SELECTION success as desired behavior. Pure UI styling uses render review, not prose assertions.
- Evidence root `E=.omo/evidence/reboot-band-liquid-glass`; every scenario has `red.txt`, `green.txt`, screen PNG/XML, event/result JSON and `cleanup.txt` in its named directory. This variable denotes an exact repository-relative directory, not an unfilled input.
- Use `./gradlew :app:testDebugUnitTest --tests 'com.sleepysoong.autobandselector.<TestClass>'` for each named JVM seam, exact method suffix where supplied. Tests must fail for the behavior before production change, not merely absent class/import. When introducing a seam, first characterize the old adapter, extract without behavior change, then capture failing regression before fixing it.
- Native runner contract introduced in task 1: `python3 script/qa/band_qa.py <scenario> --evidence .omo/evidence/reboot-band-liquid-glass/<scenario>`. It resolves exactly one authorized `adb devices` target, rejects emulator/non-Fold6/wrong-carrier for radio scenarios, drives native UiAutomator instrumentation with bounded exact-state waits, exports actual screen captures/results and exits nonzero for failed/skipped/unverified scenarios. No success from log substring alone. It never enables accessibility through secure-settings shell writes or bypasses a lock credential.
- Device manifest evidence: model, SDK, Android release, One UI property (raw value if present), build fingerprint, packages/activity versions, anonymized subscription type/slot, supported UI nodes. Capture with `adb shell getprop ro.product.model`, `adb shell getprop ro.build.version.sdk`, `adb shell getprop ro.build.version.release`, `adb shell getprop ro.build.version.oneui`, `adb shell getprop ro.build.fingerprint`. Actual installed firmware has not been inspected in this planning session.
- Baseline, failed-first and final radio QA must use the real Samsung dialer/hidden menu, not an emulator fixture. A debug-only probe harness may inject deterministic HTTP streams/clock at the speed-probe boundary to test failures, but `scan-live` must use the real endpoint and no substitution.
- Fold states cannot be simulated by `wm size` as proof of physical fold continuity. Use a physically controllable Fold6 posture or record that hardware scenario pending. Emulators can additionally check responsive layout; they cannot close physical radio/posture criteria.
- Resource cleanup is paired: unregister network/accessibility listeners, disconnect streams, end instrumentation and close any device-view window; restore Automatic after radio QA with UI verification, retaining only explicit evidence. `cleanup.txt` records restored mode or failure. A failed restoration is FAIL, not a clean pass.
- Final suite once after all code changes: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`; then named physical scenarios. SDK/dependency downloads and long Gradle/device runs use monitor subscriptions, not foreground polling.

## Execution strategy
### Parallel execution waves
- Wave A, ground/build/core (1-5): task 1 preflight first. Task 2 toolchain follows baseline; tasks 3-5 follow task 2 and use disjoint files, with 5 consuming 4's interface. This is a dependency wave, not permission to run blocked tasks early.
- Wave B, behavior/UI/integration (6-10): 6 network measurement can run alongside 7 macro driver after interfaces freeze. 8 integrates them; 9 UI proceeds against frozen state contract without editing coordinator/driver. 10 validates integrated app.
- Wave C, artifact (11): docs/APK provenance only after physical/automated checks.
- Delegation topology: independent background `deep` lanes for network vs macro once shared contracts are frozen; `visual-engineering` UI lane owns only `ui/**`, MainActivity and UI resources. If that category is unavailable, use `deep` for native Android GUI work. Lead owns shared Gradle/manifest/Application files, coordinator integration and real device QA; no two agents drive the same phone or write same paths concurrently. No team needed: interdependent units remain sequential. Do not dispatch architect: provider unavailable in current session.

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 | none | 2-11 | none |
| 2 | 1 | 3-11 | none |
| 3 | 2 | 6,8 | 4,5 |
| 4 | 2 | 5,7,8,9 | 3 |
| 5 | 4 | 7 | 3 |
| 6 | 3,4 | 8 | 7,9 |
| 7 | 4,5 | 8 | 6,9 |
| 8 | 3,4,6,7 | 10 | 9 (frozen interface) |
| 9 | 2,4 | 10 | 6,7,8 (disjoint ownership) |
| 10 | 8,9 | 11 | none (single device) |
| 11 | 10 | F1-F4 | none |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->
- [x] 1. Capture app baseline and establish native QA harness
  - Recommended task executor category: deep — native device automation and compatibility evidence.
  - What to do: Read current instructions/worktree; preserve unrelated changes. Add `script/qa/band_qa.py` and `app/src/androidTest/java/com/sleepysoong/autobandselector/qa/NativeBandQa.kt`, plus minimum test-runner dependencies needed to capture baseline. Record current APK/source hashes separately; do not assume root APK equals source. Use actual Phone UI to capture menu tree, SELECTION semantics, firmware password route, KT eSIM-to-visible-SIM mapping, `*123456#` registered LTE band readback and reversible Automatic restoration. Add JVM characterization tests `LegacyEntryContractTest` for existing KT candidate list, Start initiating scan, log FileProvider authority. Avoid private telephony APIs. Preflight read-only device facts first; radio transition QA only after existing user grant and a known working Restore route. Use LSP references before moving behavior if Kotlin LSP is available; otherwise record unavailable and inspect the two source adapters directly.
  - Test pins shared with task 2: JUnit 4.13.2, Robolectric 4.14.1 (explicit SDK34 tests), kotlinx-coroutines-test 1.7.3 aligned to existing runtime, AndroidX runner 1.6.2/rules 1.6.1/ext-junit 1.2.1, UiAutomator 2.3.0. Compose native tests use the UI test artifact matching Compose 1.12.0; dependency graph gate must catch conflicts rather than overriding metadata.
  - Parallelization: Wave A; blocked by none; blocks all other tasks.
  - References: S, A, C, M, P, Article; `MainActivity.startScanCountdown`, `shareLogFile`, `BandSelectorService.onAccessibilityEvent`.
  - Acceptance: `./gradlew :app:testDebugUnitTest --tests 'com.sleepysoong.autobandselector.LegacyEntryContractTest'` passes unchanged behavioral source; harness exits nonzero for no device or unsupported menu and captures baseline scan/restore limitations without claiming pass.
  - QA happy: `python3 script/qa/band_qa.py baseline --evidence .omo/evidence/reboot-band-liquid-glass/baseline`; PASS iff actual target identity, exact screen tree and verified Automatic restoration are captured. QA failure: `python3 script/qa/band_qa.py preflight-negative --evidence .omo/evidence/reboot-band-liquid-glass/preflight-negative`; inject empty adb enumeration into harness self-test (not production app), PASS iff exit nonzero and zero phone commands executed. RED existing defects saved here and in their later targeted tests.
  - Commit: Y | `test: capture Samsung band macro baseline and device QA harness`.

- [ ] 2. Migrate to a pinned Backdrop-compatible Android build
  - Recommended task executor category: deep — Kotlin/AGP compatibility affects every entry point.
  - What to do: Update root/app Gradle and wrapper to upstream verified Gradle 9.7.1 / AGP 9.3.2 / Kotlin and Compose compiler plugin 2.4.10; use AGP built-in Kotlin (remove `org.jetbrains.kotlin.android` from app), explicit Kotlin Gradle version alignment following AGP supported configuration, JVM 17 and compileSdk/build tools 37. Keep targetSdk 34 and minSdk 26 unless resolved dependency metadata raises the Android minimum; a required minimum increase is a plan deviation to report, not suppress. Add Compose Multiplatform Android artifacts 1.12.0 matching published Backdrop graph, activity-compose 1.13.0 and `io.github.kyant0:backdrop:2.0.1`; no dynamic versions/BOM guessing. Use upstream Android-only app plugin pattern, not a KMP module conversion. Use task 1's explicit test pins. Preserve existing dependency versions unless resolution requires an explicit upgrade; record resolved graph. Do not copy upstream lint-off flags. First render a minimal sampled-background Backdrop card in a debug-only test screen and verify effects before migrating the whole UI.
  - Parallelization: Wave A; depends 1; blocks 3-11.
  - References: C, Backdrop, Publication; upstream `androidApp/build.gradle.kts`, catalog and wrapper.
  - Acceptance: `./gradlew :app:dependencies --configuration debugRuntimeClasspath` captures resolved Backdrop Android variant; `./gradlew :app:checkDebugAarMetadata :app:assembleDebug :app:testDebugUnitTest` all exit 0. If pinned source graph cannot resolve, stop compatibility gate and report concrete artifact/error; do not silently substitute a different glass library.
  - QA happy: `python3 script/qa/band_qa.py launch --evidence .omo/evidence/reboot-band-liquid-glass/build-launch` installs built debug APK and shows unchanged controls. QA failure: `./gradlew :app:lintDebug` plus harness `launch-negative` detects Activity crash/non-launch and fails, not a silent screenshot-only pass. Dependency-only change has no artificial RED requirement; preserve characterization GREEN.
  - Commit: Y | `build: align Android toolchain for Backdrop Compose UI`.

- [ ] 3. Resolve KT eSIM and persist configuration without runnable state
  - Recommended task executor category: deep — Android subscription permissions and identity boundaries.
  - What to do: Add `network/KtSubscriptionResolver.kt`, `data/SettingsRepository.kt` and tests. Enumerate active subscriptions after READ_PHONE_STATE grant; identify KT embedded subscription by platform carrier metadata and embedded status, not display-name substring alone. If ambiguous, require explicit in-app subscription selection from actual entries; persist a logical selection without ICCID/IMSI/phone number, re-resolve each run. Require selected subId equals defaultDataSubscriptionId; otherwise blocked preflight plus system SIM settings link. Hidden-menu mapping uses observed logical slot from task 1; refuse unverified mapping. Keep existing device/SIM carrier settings, history and log sharing. Legacy scan flags are ignored/cleared before any service action; history cannot authorize work. Add READ_PHONE_STATE, ACCESS_NETWORK_STATE and CHANGE_NETWORK_STATE only where required for the cellular request; no broad new privilege.
  - Parallelization: Wave A; depends 2; blocks 6,8; lead owns manifest changes.
  - References: S:19-25,108-115; A:94-112,340-345; M; Android TelephonyNetworkSpecifier.
  - Acceptance: `./gradlew :app:testDebugUnitTest --tests 'com.sleepysoong.autobandselector.KtSubscriptionResolverTest' --tests 'com.sleepysoong.autobandselector.SettingsRepositoryTest'` RED then GREEN for SIM2 assumption, stale flags, wrong default subscription and denied permission.
  - QA happy: `python3 script/qa/band_qa.py esim-preflight --evidence .omo/evidence/reboot-band-liquid-glass/esim-preflight`; correct active KT eSIM/slot shown, no dialer until Start. QA failure: `python3 script/qa/band_qa.py permission-denied --evidence .omo/evidence/reboot-band-liquid-glass/permission-denied`; deny runtime permission via native permission UI, Start blocked and zero macro actions. Restore previous grant state in cleanup.
  - Commit: Y | `fix: resolve active KT eSIM and discard stale macro authorization`.

- [ ] 4. Establish a single cancellable process-local run state machine
  - Recommended task executor category: deep — event ordering and cancellation are core logic.
  - What to do: Add `automation/MacroState.kt`, `RunCoordinator.kt`, application ownership and StateFlow output. Define typed observations/actions/results, runId/attemptId and exact states above. Start atomically creates one fresh run; second Start rejected while active. MainActivity recreation only observes, never starts tasks from onResume. Service acts only with current process-local authorization. Stop/interrupt revoke first, then cancel effects. Reboot/process restart Idle even with legacy flags. Preserve history separately; an interrupted result may be displayed without executing it. Implement deadlines with coroutine scheduler and monotonic clock, no blocking main thread.
  - Parallelization: Wave A; depends 2; blocks 5,6,7,8,9.
  - References: A:359-478; S:19-25,189-202; M; final user decisions in P.
  - Acceptance: `./gradlew :app:testDebugUnitTest --tests 'com.sleepysoong.autobandselector.RunCoordinatorTest'` RED/GREEN includes duplicate events/resume, two Starts, Stop followed by late response, process reconstruction with old prefs and no action on app open. Await explicit signals with virtual time.
  - QA happy: `python3 script/qa/band_qa.py start-once --evidence .omo/evidence/reboot-band-liquid-glass/start-once`; double-tap creates one runId. QA failure: `python3 script/qa/band_qa.py stop-late-callback --evidence .omo/evidence/reboot-band-liquid-glass/stop-late-callback`; held debug probe completion released after Stop cannot advance or apply winner; no production test bypass.
  - Commit: Y | `fix: serialize macro runs and cancel stale callbacks`.

- [ ] 5. Parse Samsung screens and match band controls exactly
  - Recommended task executor category: deep — accessibility tree interpretation and bounded navigation.
  - What to do: Add immutable screen parser using task 1 captured sanitized trees. Recognize stock dialer, password, warning, SIM dialog, network menu, overflow, band rows and SELECTION toggle by package/activity/window plus exact normalized labels/IDs. Include KT candidate B1/B3/B8 and explicit non-target rows B10/B18/B19. Represent unknown/missing/unreadable control state explicitly. Checkable semantics may live on parent/sibling; map label to owning control and verify state there. Scroll by verified container, stop at repeated signature or 20 pages. Coordinates allowed only for an identified visible node's bounds where ACTION_CLICK is unsupported and bounds remain in same verified window; never fixed recording coordinates.
  - Parallelization: Wave A; depends 4; blocks 7.
  - References: S:31-105,118-192,207-279; Article; task 1 screen evidence.
  - Acceptance: `./gradlew :app:testDebugUnitTest --tests 'com.sleepysoong.autobandselector.SamsungScreenParserTest'` RED/GREEN for B1/B18 collision, unknown window containing Password, unseen selected band, offscreen targets and no checkable SELECTION state. Test fixtures reflect real captured UI shapes, not invented controls.
  - QA happy: `python3 script/qa/band_qa.py inspect-band-page --evidence .omo/evidence/reboot-band-liquid-glass/inspect-band-page`; parsed controls match actual UI/XML including full scroll coverage, no radio mutation. QA failure: `python3 script/qa/band_qa.py unrelated-screen --evidence .omo/evidence/reboot-band-liquid-glass/unrelated-screen`; a non-allowlisted native test activity with matching labels causes zero clicks/password input.
  - Commit: Y | `fix: parse Samsung band controls without prefix collisions`.

- [ ] 6. Measure speed only over the selected KT cellular network
  - Recommended task executor category: deep — external HTTP and subscription-bound networking.
  - What to do: Add `network/CellularSpeedProbe.kt` and tests; request TRANSPORT_CELLULAR with TelephonyNetworkSpecifier for selected subId on supported APIs, verify returned capabilities and default-data identity, bind each HTTP connection using Network.openConnection, no global binding. Since supported target Fold6 is modern, older API lacking verified subscription matching is unsupported for scan rather than silently falling back. Implement exact sample/median/error/deadline policy above. Acquire callbacks before transitions and unregister in finally; cancellation disconnects active HTTP even if blocked IO. Validated connectivity is a prerequisite, not a speed score. Never read ICCID/IMSI or log sensitive subscription identifiers. Keep endpoint query contract unchanged. An active default-network VPN is a blocked preflight condition: explain that a direct cellular comparison is unavailable while it is active; do not disable or bypass it automatically.
  - Parallelization: Wave B; depends 3,4; parallel with 7,9; blocks 8.
  - References: A:511-538; Android sources; measurement policy above.
  - Acceptance: `./gradlew :app:testDebugUnitTest --tests 'com.sleepysoong.autobandselector.CellularSpeedProbeTest'` RED/GREEN for default Wi-Fi leak, wrong subscription, incomplete body, HTTP error, connection close on cancellation and median/tie handling using deterministic byte stream and clock. Expected medians use fixed independent fixtures, not output-derived expectations.
  - QA happy: `python3 script/qa/band_qa.py cellular-with-wifi --evidence .omo/evidence/reboot-band-liquid-glass/cellular-with-wifi`; real endpoint while Wi-Fi available, requested/returned cellular subscription verified with full byte count and measured duration. QA failure: `python3 script/qa/band_qa.py network-loss --evidence .omo/evidence/reboot-band-liquid-glass/network-loss`; native UI disables mobile data during a controlled pending sample; no valid score and HTTP/callbacks released; restore initial data/Wi-Fi settings.
  - Commit: Y | `fix: measure band speeds on the selected cellular subscription`.

- [ ] 7. Drive verified band selection and ordered Automatic restoration
  - Recommended task executor category: deep — OEM UI automation requires exact postconditions.
  - What to do: Add `automation/SamsungMacroDriver.kt`, connect service adapter to coordinator. Explicitly resolve verified Samsung Phone component then ACTION_DIAL `tel:319712358`; preserve final-8 click workaround via event-driven fresh node lookup. Navigate password 774632 and known warning only in verified hidden-menu window; choose task 3 resolved SIM only when dialog appears. For each LTE candidate, establish known SELECTION-off state as needed, clear all non-target selectable bands, select exact target, ensure SELECTION on, re-open/read complete state before success. No blind "applied=true". If node state cannot express truth, use task 1 verified alternative screen readback or report unsupported; never infer from click acceptance. Restoration: SELECTION off -> Network Mode Automatic -> re-read verified mode. Package change/lock/service loss revoke run.
  - Parallelization: Wave B; depends 4,5; parallel with 6,9; blocks 8.
  - References: S entire; Article; task 1 verified mapping; state and timing contracts above.
  - Acceptance: `./gradlew :app:testDebugUnitTest --tests 'com.sleepysoong.autobandselector.SamsungMacroDriverTest'` RED/GREEN for already-on duplicate events, failed click, absent target, missing SIM mapping, Automatic restore and password-like unrelated screen.
  - QA happy: `python3 script/qa/band_qa.py apply-restore --evidence .omo/evidence/reboot-band-liquid-glass/apply-restore`; actual KT candidate apply, repeated event without inversion, final Automatic verify. QA failure: `python3 script/qa/band_qa.py missing-target --evidence .omo/evidence/reboot-band-liquid-glass/missing-target`; debug observation boundary removes target on real screen, driver reports unsupported without clicking another band; no injected path counts as device-compatibility proof. End with verified Automatic.
  - Commit: Y | `fix: verify Samsung selection state and Automatic restoration`.

- [ ] 8. Orchestrate fresh per-run KT comparisons and verified winner application
  - Recommended task executor category: deep — integrates radio transitions, samples and recovery.
  - What to do: Implement coordinator candidate loop B1,B3,B8, three samples each, median ranking and fresh results. One network acquisition belongs to current candidate/attempt only; revoke previous callbacks on each transition. Candidate absent -> unsupported skip. Candidate no cellular/invalid sample -> restore Automatic, then next candidate if restoration verified. Driver structural/SIM mismatch -> fail stop; no valid winner -> restore Automatic. Highest median candidate must be applied and verified again before Completed. Do not use old scores or success based on network callback alone. Stop semantics remain immediate; explicit Restore separate. Keep logs bounded (1 MiB rotation, last 20 run summaries), redact passwords and subscriber identifiers, preserve copy/share/delete FileProvider behavior.
  - Parallelization: Wave B; depends 3,4,6,7; parallel only with disjoint UI task 9; blocks 10.
  - References: A:313-478,511-553; S:189-202; P final user choice.
  - Acceptance: `./gradlew :app:testDebugUnitTest --tests 'com.sleepysoong.autobandselector.BandScanIntegrationTest'` RED/GREEN: fixed medians B1=8/B3=20/B8=11 chooses B3; next run B1=15/B3=5/B8=9 chooses B1 despite history; exact tie chooses B1; all failures restore and no winner; stale response cannot change score/winner; final apply failure cannot Complete.
  - QA happy: `python3 script/qa/band_qa.py scan-live --evidence .omo/evidence/reboot-band-liquid-glass/scan-live`; real Samsung transitions and actual HTTP, samples/ranking plus final selected-band state captured. QA failure: `python3 script/qa/band_qa.py all-candidates-fail --evidence .omo/evidence/reboot-band-liquid-glass/all-candidates-fail`; debug probe produces explicit network failures, real driver restores Automatic and no success/winner displayed. Additional `scan-twice` verifies each Start creates fresh sample IDs/traffic instead of applying prior winner.
  - Commit: Y | `feat: compare KT bands on every start and apply the verified winner`.

- [ ] 9. Build the Fold6 Liquid Glass interface with live run controls
  - Recommended task executor category: visual-engineering — Compose visual design and adaptive interface; use deep if router unavailable.
  - What to do: Read frontend and visual-qa skills at execution. Replace main XML rendering with Compose in MainActivity; add `ui/BandSelectorScreen.kt`, `ui/GlassTheme.kt`. Preserve cat/Pretendard, Korean copy, black/white base with subtle neutral backdrop. Compact width uses one scroll column: header, accessibility/eSIM preflight card, candidate/speed card, full-width Start, persistent Stop while active, Restore and logs. Expanded width >=600dp uses controls left/results right; no giant decorative empty panel. Capture background with layerBackdrop; draw cards and primary control with ordered color/blur/lens and rounded shape, restrained glass highlights. API31-32 blur-only, API26-30 readable opaque fallback; API33+ lens. Do not let disabled effects hide content.
  - What to do (behavior): Start always fresh scan; show up-to-27MB payload notice, do not require repeated confirmation. Disable conflicting controls while running. Make Stop reachable in full screen and PiP via RemoteAction; PiP shows only current step/band/status, not dense log text. Preserve run on folding/configuration; privacy-safe log sheet retains copy/share/delete. 48dp hit targets, TalkBack labels, font scale 1.5 and dark/light contrast. No screenshots of Compose previews count as device rendering.
  - Parallelization: Wave B; depends 2,4; uses frozen state interfaces; owns only UI files/MainActivity/UI resources, lead resolves manifest/Gradle needs.
  - References: V, A:28-139,167-308,556-609; Backdrop, Effects.
  - Acceptance: `./gradlew :app:testDebugUnitTest --tests 'com.sleepysoong.autobandselector.UiStateMappingTest'`; native Compose assertions via `python3 script/qa/band_qa.py ui-controls --evidence .omo/evidence/reboot-band-liquid-glass/ui-controls`. Pure visual changes require screenshots after each change, no prose-pinning tests.
  - QA happy: `python3 script/qa/band_qa.py fold-layout --evidence .omo/evidence/reboot-band-liquid-glass/fold-layout`; capture real cover/inner, light/dark and font1.5 states, no clipped Start/Stop/results, actual backdrop content visibly sampled. QA failure: `python3 script/qa/band_qa.py ui-permission-error --evidence .omo/evidence/reboot-band-liquid-glass/ui-permission-error`; disabled accessibility visibly blocks Start and opens correct settings instead of pretending execution. Restore font/theme/posture settings in cleanup. API26/31 emulator render checks additional only.
  - Commit: Y | `design: apply Backdrop glass UI for Fold6 scan controls`.

- [ ] 10. Verify reboot, lifecycle, live scanning and restoration end to end
  - Recommended task executor category: deep — real native GUI and integrated regression verification.
  - What to do: Complete all `band_qa.py` scenario implementations with actual UiAutomator actions and binary assertions. Runner provides no product-only shortcut to bypass Start, permissions, service or real dialer in `scan-live`/`reboot-idle`. Run current diagnostics before final Gradle suite; fix in-scope failures at original seam with RED/GREEN. Use dedicated test modes only for deterministic failure induction and label artifacts accordingly. Confirm mapped selected eSIM remains same across candidate transitions.
  - Parallelization: Wave B; depends 8,9; exclusive ownership of physical device; blocks 11.
  - References: all implementation tasks, M, S/A regression seams.
  - Acceptance: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` exit 0, no skips/only/xfail; every S1-S6 criterion below has native evidence and cleanup. No device => report exact missing prerequisite and do not mark completed.
  - QA happy: `python3 script/qa/band_qa.py reboot-idle --evidence .omo/evidence/reboot-band-liquid-glass/reboot-idle`; reboot authorized target, observe no macro before app open/Start, retain carrier config, press Start and capture fresh full scan/winner. Requires agent-controllable unlocked test device; never bypass real credential. Then `python3 script/qa/band_qa.py scan-live --evidence .omo/evidence/reboot-band-liquid-glass/final-scan-live`.
  - QA failure: `python3 script/qa/band_qa.py lifecycle-stop --evidence .omo/evidence/reboot-band-liquid-glass/lifecycle-stop`; rotate/fold and duplicate resume during suspended sample, Stop through PiP then deliver late completion, zero subsequent actions. Also `process-death` force-stops app during test, relaunch remains idle and exposes Restore; `restore-live` verifies Automatic after interrupted state.
  - Commit: Y | `test: cover Fold6 reboot scan cancellation and recovery flows`.

- [ ] 11. Publish a source-matched local APK and accurate usage guide
  - Recommended task executor category: deep — packaged APK provenance and install/launch proof.
  - What to do: Update PROJECT.md to describe KT Fold6/eSIM, Start always scans, measured data estimate, initial accessibility/runtime grants, best-band limits, Stop versus Restore, supported tested firmware and device QA evidence. Do not claim "permanent" band setting. Produce debug APK from final source matching existing sideload convention, increment versionCode/versionName from actual current values, copy build to root `auto-band-selector.apk`; do not overwrite signing keys or uninstall an incompatible installed app automatically. Record signing certificate and SHA-256; no network release upload.
  - Parallelization: Wave C; depends 10; blocks F1-F4.
  - References: PROJECT.md; existing root APK; C, A log sharing and P decisions.
  - Acceptance: `sha256sum app/build/outputs/apk/debug/app-debug.apk auto-band-selector.apk` hashes equal; `apksigner verify --print-certs auto-band-selector.apk` succeeds; source version/package match APK. If signing mismatch blocks update, report instead of uninstall/data loss.
  - QA happy: `python3 script/qa/band_qa.py packaged-smoke --evidence .omo/evidence/reboot-band-liquid-glass/packaged-smoke` installs exact root APK, opens real screen, Start executes scan and Restore verifies Automatic. QA failure: harness `package-mismatch` self-test points at mismatching copy and fails before install; no false provenance pass. Prose reviewed by read; no prose tests.
  - Commit: Y | `release: ship verified Fold6 band scanner APK and usage guide`.

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit
  - Recommended task executor category: deep
  - Verify all task/criterion evidence including real source-matched APK and user decisions. Read final git diff and run `python3 script/qa/band_qa.py evidence-audit --evidence .omo/evidence/reboot-band-liquid-glass`; fail on missing result/cleanup or skipped physical scenario. No product edits. Record `final/F1.md`.
- [ ] F2. Code quality review
  - Recommended task executor category: deep
  - Review changed code against StateFlow identity, exact screen matching, eSIM mapping, cellular Network selection, cancellation/resource cleanup and no unsupported success. Check last suite/LSP results from current source hash. Run targeted reproduction if suspected; record `final/F2.md`. Fail on concrete contract violation, not style preference.
- [ ] F3. Local QA and manual-install handoff
  - Recommended task executor category: deep
  - Executor personally drives native `scan-live`, `restore-live`, `reboot-idle`, `fold-layout`, `lifecycle-stop` using the exact commands above, one phone owner. F1/F2/F4 may inspect while this runs, never drive phone concurrently. Capture actual screenshots and XML/result evidence, verify Automatic and restore test settings; record `final/F3.md`. Missing device/posture automation is not PASS.
- [ ] F4. Scope fidelity
  - Recommended task executor category: deep
  - Compare final source, manifest and APK with accepted user requirements. Confirm every Start measures, no boot automation/saved-fast-apply, KT eSIM not hardcoded SIM2, genuine Backdrop. Confirm no user changes overwritten or unsolicited remote publication. Record `final/F4.md`; fail on explicit-scope mismatch.

## Commit strategy
- One verified increment per task; inspect `git log --oneline -20` and `git log -5 -- <actual touched paths>` before each message. Existing convention is lowercase English `fix:`, `feat:`, `design:`, `test:`, `release:` without scope. Use task suggestions if history remains consistent.
- Stage task-owned files only; no automated `git add .`, no reset/rewrite, no commit of private device dumps/identifiers. Keep sanitized evidence references, actual artifacts remain local. No WIP commits.
- Final implementation commit footer: `Plan: .omo/plans/reboot-band-liquid-glass.md`. Project planning commit likewise records the plan after approved review; approval does not run implementation.

## Success criteria
| ID | Binary observable and exact scenario | Failing-first proof | Required GREEN/surface evidence |
| --- | --- | --- | --- |
| S1 | Every Start freshly scans KT B1/B3/B8 candidates; `scan-twice` sees distinct run/sample identities and winner follows current medians, never history | BandScanIntegrationTest freshResultsChooseWinner fails on stale history; baseline limitations captured before fix | JVM GREEN + `scan-live`, `scan-twice` samples/result/screen and cleanup |
| S2 | Correct KT eSIM mapped to menu and cellular HTTP even with Wi-Fi available; `esim-preflight`, `cellular-with-wifi` | KtSubscriptionResolverTest SIM2 regression and CellularSpeedProbeTest defaultNetworkLeak | GREEN plus real subscription/transport evidence, completed-byte counts and no sensitive identifiers |
| S3 | Selection idempotent, exact band IDs; Automatic verified by `apply-restore`, `restore-live` | SamsungScreenParserTest B1NotB18 and SamsungMacroDriverTest duplicateSelectionDoesNotInvert / orderedRestore | GREEN and real before/after toggle/mode trees; no click-only completion |
| S4 | Reboot/process death idle until Start; Stop blocks late actions; `reboot-idle`, `process-death`, `lifecycle-stop` | RunCoordinatorTest stalePrefsCannotAuthorize / cancelledAttemptCannotRearm | GREEN, native screenshots + action trace, no later UI effects and cleanup |
| S5 | No valid sample/wrong SIM/unrecognized UI yields no winner; restore or explicit recovery failure, not false success; `all-candidates-fail`, `permission-denied`, `network-loss` | fixed failing samples, wrong sub and failed restore tests RED before implementation | GREEN and native failure UI/recovery evidence with every spawned callback/connection released |
| S6 | Actual Backdrop glass readable on Fold6 outer/inner; controls/logs/PiP remain functional, source equals installed root APK; `fold-layout`, `ui-controls`, `packaged-smoke` | existing controls characterized before UI replacement; visual-only styling exempt from RED; behavior regressions RED in UI tests | screenshots reviewed at both postures/font/theme, functional native assertions, APK hashes/signature + install/scan proof |

Completion means every row PASS with current-code evidence, all cleanup complete and final verification approved. A plan may be complete before device execution; downstream implementation may not claim success without the physical proof. Keep unknown installed firmware/device access explicit until execution preflight supplies it.
