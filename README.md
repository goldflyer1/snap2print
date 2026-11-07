# Snap2Print

Point your Android phone at an object, say how big you want it, and get a print-ready STL and 3MF. Then print it with the Bambu Handy app on the same phone, or open it in any other printer app.

```
 1. Take 1–4 photos
 2. Say "8 cm tall"            ┌─ Claude (default): rebuilds the object from simple solid shapes
 3. Create STL  ───────────────┤
                               └─ Meshy (optional): AI "scan look-alike", for organic shapes
    App scales it to exactly 80 mm, stands it on the bed, saves .stl + .3mf to Downloads/Snap2Print
 4. Print with Bambu Handy  /  Open in another printer app  /  (optional) PC bridge
```

## Install on your phone (no Android Studio needed)

Every push to `main` builds the app on GitHub automatically.

1. On your phone, open **github.com/goldflyer1/snap2print → Releases** (you need to be logged in, because the repo is private).
2. Tap the newest `Snap2Print-0.2.x.apk`.
3. When Android asks, allow your browser to **install unknown apps**, then tap Install.

Each new build installs over the previous one. Your settings and API key are kept.

## First-time setup in the app

Tap **Settings**:

| Setting | What to enter |
|---|---|
| 3D engine | **Claude** (default) or Meshy |
| Claude API key | Create one at **console.anthropic.com → API keys** (starts with `sk-ant-`). It is stored only on your phone and is never in the code or on GitHub. |
| Claude model | `claude-sonnet-5-5` (default: good and affordable). Use `claude-opus-5-5` for the most careful results. |
| Meshy API key | Only if you choose the Meshy engine |
| PC bridge | Optional. Leave empty unless you use the PC helper (see below) |

**Cost with Claude:** one model is a single API request with your photos. It typically costs a few cents with Sonnet, and somewhat more with Opus or 4 photos. Set a monthly spending limit in the Anthropic Console if you like.

## Using it

1. **Take photo.** One works. 2–4 photos from different sides (front, side, top) give better proportions. Use a plain background, good light, and get the whole object in view.
2. **Speak** (or type): "8 centimeters tall", "80 mm wide", "3 inches", "7,5 cm hoog", "10 centimètres de haut".
   - tall/high/hoog/haut → height · wide/breed/large → width · deep/diep → depth
   - no direction word → longest side · no unit → cm if below 30, otherwise mm
3. **Create STL.** With Claude this takes roughly 20–90 seconds. The app shows the final size in mm and Claude's notes on what it simplified.
4. **Print**:
   - **Print with Bambu Handy.** Bambu Handy can't open STL files itself; it prints models from your MakerWorld account, which Bambu's cloud slices for your printer. So the app:
     1. has already saved the STL in **Downloads/Snap2Print**
     2. opens **makerworld.com**, where you log in with your Bambu account, tap Upload, pick the STL and set it to **Private**
     3. opens **Bambu Handy**, where you go to your uploads and tap Print
   - **Files:** every model is saved as both **.stl** and **.3mf**. The 3MF is a standard model file in millimetres that opens in Bambu Studio, OrcaSlicer, PrusaSlicer, Cura and MakerWorld. It holds the model only: a printer still needs it sliced (a `.gcode.3mf`), and slicing can't run on the phone.
   - **Open in another printer app…** asks STL or 3MF, then sends the file to any installed app that accepts it: phone slicer apps, other brands' printer apps, Drive, mail, WhatsApp.
   - **Send to PC bridge** (only shown if set up) uses the optional PC helper in `bridge/`, which opens the STL in Bambu Studio or prints fully automatically over your LAN. See `bridge/snap2print_bridge.py`.

## What Claude can and can't model

Claude looks at the photo and rebuilds the object from **boxes, cylinders, cones, tubes (round holes), spheres, turned profiles** (like a lathe: knobs, caps, bottles, pulleys, cups) and **extruded outlines** (brackets, plates, clips). The phone turns those shapes into a closed, printable mesh. Overlapping shapes are merged by the slicer.

- **Good for:** knobs, caps, spacers, bushings, brackets, adapters, simple replacement parts, containers, nameplates.
- **Not good for:** faces, animals, figurines, very organic or detailed shapes. Switch the engine to **Meshy** for those.
- **Holes:** round holes come out well (tube, revolve). Square or irregular holes through a part are approximated.
- **Accuracy:** proportions come from a photo, so they're estimated. Your spoken size is applied exactly to the dimension you named. For parts that must fit precisely, check the result, or measure the part and tell the app the critical size.

## For developers

```
app/src/main/java/com/snap2print/app/
  MainActivity.kt        screen, camera, voice, print buttons
  ClaudeShapeClient.kt   photo → shape list via the Claude Messages API (vision + forced tool call)
  MeshyClient.kt         optional photo → mesh via Meshy
  ImageUtil.kt           rotate/shrink photos before upload
  BridgeClient.kt        optional PC bridge
  core/                  plain Java, unit-tested:
    Shapes.java          shape → watertight triangle mesh (box, cylinder, cone, tube, sphere, revolve, extrude)
    SizeParser.java      "8 cm tall" → 80 mm, height
    MeshIO.java          STL/OBJ read, binary STL write
    MeshFit.java         Y-up→Z-up, exact scaling, place on bed
app/src/test/            unit tests (watertightness, volumes, parsing)
.github/workflows/       builds, tests and publishes the APK on every push to main
bridge/                  optional PC helper for Bambu Studio / LAN printing
```

Build locally: open the folder in Android Studio and press Run, or run `./gradlew assembleRelease`.

**Signing:** builds use `app/snap2print-dev.jks` (password `snap2print`) so updates install over each other. Before selling on Google Play, create your own private key and pass it via the environment variables `SNAP2PRINT_KEYSTORE`, `SNAP2PRINT_KEYSTORE_PASSWORD`, `SNAP2PRINT_KEY_ALIAS` and `SNAP2PRINT_KEY_PASSWORD` (GitHub repository secrets). Never ship the dev key publicly.

**Selling it:** don't ship your own Claude key inside the app. Either let users enter their own key (as now), or put a small server of your own between the app and Claude that holds the key and charges per model.
