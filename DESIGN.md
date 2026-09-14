# DESIGN — auto-band-selector

## Tokens
- Surface base: near-black #0B0B0D; card glass: white 6-12% on dark / black 4-8% on light.
- Text: primary white on dark / near-black on light; accent amber #FFD54F for status emphasis only.
- Typeface: Pretendard (bundled R.font.pretendard), letter-spacing -0.05em headline, 16sp section titles, 13sp body.
- Shape: rounded cards 28dp, pill buttons; hit targets >= 48dp.
- Material: Kyant0 Backdrop — real capture (`layerBackdrop`) + `drawBackdrop` with colorControls -> blur -> lens (API33+ lens, API31-32 blur only, API26-30 opaque readable fallback RoundedCornerShape).
- Backdrop content: subtle neutral diagonal gradient (black -> charcoal), no decorative imagery.

## Layout
- Compact width (<600dp): one scroll column — header, preflight card (accessibility + KT eSIM), run status/results card, control buttons (Start full-width, persistent Stop while active, Restore, logs).
- Expanded (>=600dp): two columns — controls left, live status/results right. No empty decorative panel.
- Compact footer: "sleepysoong 제작" preserved with cat identity.

## Behavior
- Start always runs a fresh B1/B3/B8 comparison; show the up-to-27 MB payload notice; never ask to confirm twice.
- Running state disables Start/Restore and exposes Stop in full screen and PiP RemoteAction.
- PiP shows current step/band/status only.
- Log sheet: copy, share (FileProvider), delete. Dark/light and font scale 1.5 supported; disabled effects never hide text.

## Accessibility
- TalkBack contentDescriptions on all controls; high foreground/background contrast; Korean copy unchanged in meaning.
