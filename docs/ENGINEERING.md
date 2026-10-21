# How VibeGuide works

## From a destination to a physical cue

1. I enter a destination in the Compose screen. Android `Geocoder` resolves it to coordinates, and Google Directions supplies route steps.
2. The app displays the route and uses each step endpoint as a waypoint.
3. Phone location updates feed a straight-line distance calculation to the current waypoint.
4. The app looks for `left` or `right` in the current instruction and chooses the active side.
5. Distance selects low or high intensity; warning and waypoint events can override that selection.
6. Separate Bluetooth Classic SPP connections send ASCII commands to the left and right Arduino units.

## Intensity and event commands

During normal approach, the active side receives `1` in (50, 100] m, `2` in (10, 50] m, and `0` above 100 m. The other side receives `0`. A step without a recognized left/right instruction leaves both normal approach commands at `0`.

The first update within 50 m starts a 1.5-second warning cue (`3`) on both sides. A timer restores the normal intensity when the warning ends, including when the GPS position has not changed. The warning triggers once per waypoint. The receiver's `3` sequence is two brief pulses followed by off.

Within 10 m of an intermediate waypoint, both sides receive a `3` request; the app waits for the pulse before advancing the step. At the final waypoint, both sides receive a `2` request held for one second, followed by `0` as navigation stops. Each device completes a `3` / `0` connection check before the app marks it connected.

## Receiver firmware

The sketch uses `Serial.begin(9600)` and PWM pin 10. Commands `0`, `1`, and `2` hold the motor at off, PWM 120, or PWM 255 until another command arrives. Command `3` runs a blocking pulse sequence and returns to off. The app remembers the last command selected for each side to suppress consecutive duplicates.

## App state and demo controls

Each side has its own connection state, socket, output stream, write lock, and last command. Writes for that side are serialized, and write failures reset its connection indicator. Location updates are requested every 5 seconds, with a fastest interval of 2 seconds. Backgrounding the app disconnects the Bluetooth devices.

The UI includes a command display and a wall-test mode. Wall-test mode fixes a target 110 m north of the starting position. A button advances the simulated position by up to 5 m toward that fixed point. It follows the same warning and arrival logic as a final waypoint.

## Functional test

I was responsible for the final simple functional test with the connected prototype. We confirmed that the basic navigation-to-vibration workflow worked.

## What I'd improve next

- Add an explicit reconnect workflow and receiver acknowledgements.
- Make the distance thresholds more tolerant of GPS noise and use structured maneuver data for turn direction.
- Separate direct Directions web-service access from the map SDK's credential setup for wider deployment.
