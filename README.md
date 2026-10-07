# aap-cmajor: use Cmajor patches as AAP

![screenshot](https://github.com/user-attachments/assets/77559f63-46b5-4b2c-bc28-ea4f1f836bb2)

It is an experimental Cmajor integration to AAP ecosystem.

It bundles Cmajor's example patches (`examples/patches`, except for FaustFM
which needs the Faust transpiler) and runs them with Cmajor's JIT engine.
There are two plugins, which can both play any of the patches:

- "AAP Cmajor Instrument" has stereo audio outputs and starts with Pro54.
- "AAP Cmajor Effect" also has stereo audio inputs, and starts with Tremolo.

Both have MIDI 2.0 in/out. A patch's first audio input gets the stereo input
(a mono input gets left and right mixed), and its first audio output goes to
the stereo output (a mono output goes to both channels). Any other audio
inputs get silence, and the instrument's patches only ever get silence.

Each plugin instance plays one patch at a time. The plugin UI has two tabs:
"Patch" shows the patch's own UI (or generic parameter controls for patches
without one), and "Patches" switches to another patch. The selected patch and
its state are saved in the plugin state.

Loading patches from local storage is not supported yet.

The code is mostly written by AI coding agents (Claude/Codex).

It comes with our own choc WebView on Android.
And there are (of course) changes to cmajor sources too.
There are patches for them respectively.

## Licenses

aap-cmajor is released under the GPLv3 license.

Cmajor is released under the GPLv3 license (or commercial).

