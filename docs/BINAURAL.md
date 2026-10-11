# Waves: the binaural-beat generator

MuPlay's **Waves** tab generates binaural beats on the phone: a slightly different tone in each
ear, whose difference is the "beat". It plays under music or on its own, keeps running with the
screen off, and is stopped from its notification or from the tab.

This document records what the generator does, where its numbers came from, and how strong the
evidence behind them is. It is written for whoever changes a preset next, so they know which
numbers are conventions, which are design guesses, and which are the owner's own.

## Honest evidence, first

**Binaural beats may help relaxation; effects on sleep and focus are not proven.** That sentence is
shown in the app and in the store listing, and it is the strongest claim this project makes.

- Garcia-Argibay et al. 2019 (*Psychological Research*), a meta-analysis of 22 studies, found a
  medium pooled effect (g about 0.45) across memory, attention, anxiety and pain. The effect is
  pooled and heterogeneous and is not specific to any one band. Exposure before the task, and
  longer exposure, did better; masking noise was not needed.
  <https://pubmed.ncbi.nlm.nih.gov/30073406/>
- Ingendoh, Posny & Heine 2023 (*PLOS ONE*), a systematic review of 14 EEG studies: five supported
  entrainment, eight contradicted it, one was mixed. Entrainment of cortical rhythms is **not**
  established. <https://www.ncbi.nlm.nih.gov/pmc/articles/PMC10198548/>
- For sleep, a review of 34 auditory-stimulation studies found no strong evidence overall; pink
  noise looked best. That is why the sleep presets carry a quiet noise bed.
  <https://pmc.ncbi.nlm.nih.gov/articles/PMC9163611/>

The band associations below are conventions used across apps, not findings. No preset here is a
clinical protocol.

## Bands

The edges vary between sources; MuPlay labels a beat with these:

| Band  | Beat (Hz) | Conventional association |
|-------|-----------|--------------------------|
| Delta | below 4   | Deep, slow-wave sleep |
| Theta | 4 to 8    | Sleep onset, deep meditation, hypnosis |
| Alpha | 8 to 12   | Relaxed wakefulness, light meditation, creative flow |
| SMR   | 12 to 15  | Calm, relaxed attention |
| Beta  | 15 to 30  | Alertness, concentration |
| Gamma | 30 and up | "Peak focus" claims; very weak evidence, near the perceptual limit |

## How a tone is made

- **Channels.** `left = carrier + beat / 2`, `right = carrier - beat / 2` -- SBaGen's convention, with
  the higher tone on the left. A profile stores the carrier and the beat; the left and right
  frequencies are derived and shown live in the editor. The owner's own example,
  *Concentrate 13.46 Hz (left 158.44, right 144.98)*, is carrier 151.71 Hz and reproduces exactly.
- **Carrier.** Beats are heard only with carriers below about 1 kHz (Oster 1973, Licklider 1950).
  Presets sit between 120 and 260 Hz: low carriers for calm and sleep, higher ones for focus.
  The editor accepts 40 to 1000 Hz and warns outside 100 to 500 Hz.
- **Beat.** Perceived up to roughly 30-35 Hz; above that two tones are heard. The editor accepts
  0.1 to 40 Hz and warns above 30 Hz.
- **No clicks.** Each ear is one 64-bit phase accumulator; only its increment changes, and the
  frequency is re-evaluated every sample. Amplitude and noise level are smoothed (about 20 ms),
  fades are raised cosines, an edit while playing glides to the new frequency over half a second,
  and a stop is a fade. See `BinauralSynth`'s header.
- **Noise.** Optional white, pink (Paul Kellet's filter) or brown noise, each normalised to unit RMS
  so a level means the same loudness whichever colour is picked.
- **Level.** Quiet by design: tones at a few percent of full scale, a soft limiter above 0.9 so a
  noise bed cannot clip, and a separate volume slider on the Waves tab that is not part of any
  profile.
- **Output.** 48 kHz stereo float through its own `AudioTrack`, in a foreground service with its own
  notification. It takes **no audio focus**, so music keeps playing underneath, and it bypasses the
  music player's ReplayGain and gapless machinery entirely.

## The prepared profiles

All values are recommendations built from the conventions above; none are another vendor's numbers.

### Steady tones

| Profile     | Band  | Beat (Hz) | Carrier (Hz) | Left / right (Hz) | Noise |
|-------------|-------|-----------|--------------|-------------------|-------|
| Meditate    | Theta | 7.0       | 200          | 203.50 / 196.50   | light pink |
| Relax       | Alpha | 10.0      | 200          | 205.00 / 195.00   | light pink |
| Creative    | Alpha | 8.0       | 200          | 204.00 / 196.00   | light pink |
| Concentrate | SMR   | 13.46     | 151.71       | 158.44 / 144.98   | none -- the owner's own example |
| Focus       | Beta  | 18.0      | 250          | 259.00 / 241.00   | none |
| Hypnosis    | Theta | 6.0       | 150          | 153.00 / 147.00   | light pink |
| Sleep       | Delta | 3.5       | 150          | 151.75 / 148.25   | brown |
| Deep sleep  | Delta | 2.0       | 120          | 121.00 / 119.00   | brown |

Sleep is 3.5 Hz rather than the 4 Hz many sources quote, because 4 Hz is the theta edge and the
profile would otherwise be labelled "Theta" under a name that promises sleep.

### Programs (curves over time)

| Program           | Length | Shape |
|-------------------|--------|-------|
| Power nap         | 36 min | 8 → 6 Hz (5 min), 6 → 4 (5 min), 4 → 2.5 held low and drifting for 20 min, back up 2.5 → 8 (3 min), 8 → 14 (3 min). The owner's description: *starts with meditation, goes to sleep and deep sleep for 20 minutes (slow), then comes back up.* |
| Fall asleep       | 55 min | 10 → 7 → 4 → 2.5 Hz over 45 min, holds 2.5 Hz for 10 min while fading out |
| Deep sleep night  | 8 h    | 6 → 2 Hz over 20 min, 2 → 1.5 Hz through the night at a lower level, 20-minute fade to silence |
| Deep meditation   | 30 min | 10 → 7 Hz, 7 → 6 Hz held, back to 8 Hz |
| Focus session     | 50 min | 12 → 18 Hz in 5 min, 18 → 16 Hz held, down to 10 Hz. Start it before the task: pre-task exposure did better in Garcia-Argibay. |
| Wake up           | 15 min | 6 → 10 → 18 Hz |

Descents are gradual -- roughly 0.2 to 0.5 Hz a minute -- because the idea rests on the response
following the stimulus, which large jumps would defeat. That rate is a design choice, not a
published standard.

## Editing on the phone

Every profile opens in an editor from the pencil on its row:

- **Steps.** Each has a start and end beat, a start and end carrier, a length in minutes, and a
  curve: *Linear*, *Smooth* (eased at both ends) or *Exponential* (even in perceived pitch). Steps
  can be added and removed; the curve above them redraws as you type, with the band edges marked.
- **Sound.** Tone level, noise colour and level, fade in and out, and what happens at the end:
  stop, or keep holding the last tone.
- **Save, Try it, Duplicate.** *Try it* plays the draft without saving it. Saving a prepared profile
  keeps the change as an *edit* (an "Edited" badge appears) and **Reset** puts it back as shipped.
  *New profile* and *Duplicate* make profiles of your own, which can be deleted.
- Problems are listed under the steps. A beat outside 0.1-40 Hz, a carrier outside 40-1000 Hz or a
  fade over 30 minutes keeps Save off; a beat above 30 Hz or a carrier outside 100-500 Hz is only
  a warning.

Profiles, edits, the volume and the safety acknowledgement are stored on the phone only, in
`binaural-profiles.json` under the app's private files, written atomically. Nothing about Waves is
sent anywhere.

## Safety

Shown once, before the first play, and acknowledged:

- Headphones are needed -- a speaker mixes the two ears and the beat disappears. Mono-audio
  accessibility settings do the same.
- Do not use while driving or operating machinery.
- If you have epilepsy or a seizure disorder, a pacemaker or another implant, or a serious
  mental-health condition, ask a doctor first.
- Keep the volume low. This is not medical advice.

Sleep programs end with a slow fade to silence rather than playing on all night at full level.

## Sources

- SBaGen documentation and notes: <https://uazu.net/sbagen/sbagen.txt>, <https://uazu.net/sbagen/notes1.html>
- Gnaural help: <https://gnaural.sourceforge.net/help/>
- Garcia-Argibay et al. 2019: <https://pubmed.ncbi.nlm.nih.gov/30073406/>
- Ingendoh, Posny & Heine 2023: <https://www.ncbi.nlm.nih.gov/pmc/articles/PMC10198548/>
- Binaural beat perception limits (Frontiers in Human Neuroscience, 2017):
  <https://www.frontiersin.org/journals/human-neuroscience/articles/10.3389/fnhum.2017.00557/epub>
- Psychophysics of binaural beats (FernUni Schweiz): <https://fernuni.ch/psychologie/forschungsprojekt/psychophysics-of-binaural-beats>
- Auditory stimulation and sleep, a review: <https://pmc.ncbi.nlm.nih.gov/articles/PMC9163611/>
