# UI (shell)

**Answers:** how is state presented and how does the user act on it?

Owns the application shell: Android dashboard, macOS menu bar panel and settings window,
localisation. Feature UI (cards, windows) lives with its feature when that keeps ownership clear.
UI consumes feature APIs and read-only device state; it never talks to sessions, transport or
messaging.

## Code today
| Platform | Where |
|---|---|
| Android | `MainActivity.kt` (dashboard incl. feature cards), `res/values*/strings.xml` |
| macOS | panel and settings in `BridgeyApp.swift`, `MenuBarPanelSurface.swift`, `Localization.swift`, `Resources/*.lproj` |

## Devices and selection (MD-4)
`DeviceList` (`DeviceList.swift` / `.kt`) is a read-only presentation of the Core's device
directory: every connected device (connected first, then the directory's name order) with its
display name and platform/kind ("Android · Phone"). The panel (`DeviceListSection`) and the
Android dashboard show the list when more than one device is connected; one device keeps the
simple card.

`selectedDeviceId` is **UI state only** (`DeviceSelection` on macOS, `rememberSaveable` on
Android). It is never a session and never routing: the Core stays addressed by `deviceId`.
`DeviceList.reconcile` keeps a selection while that device stays connected; when it goes away
the only remaining connected device is selected, otherwise nothing. Feature actions resolve their
target with `DeviceList.target(selected, eligible)`: the selection if the feature applies to it,
else the only eligible device, else the user chooses — never the routed device. The device list
itself is not filtered by features. Today Ping and Find use it.

## Device-centric shell (MD-4b)
Bridgey is **peer-centric**: every connected device is an independent peer, none is "main". The
panel and the dashboard show *the peers*, then *the selected peer's card*:
- identity from the directory (name, platform · kind, connected), Ping and Find of that peer,
  and **Disconnect that peer** (`disconnect(deviceId)`, never the routed session);
- **that peer's own telemetry** (battery, storage, memory, CPU, temperature and the details view),
  whichever peer it is (MD-4c, see [telemetry](../features/telemetry/README.md));
- **Clipboard to that peer** (MD-5);
- **Send Files… to that peer** (MD-6); transfer rows and notifications name the peer
  (`photo.jpg → Mac`, `document.pdf ← S23`), Android's Share dialog asks for the recipient (the
  selected peer preselected when it is eligible), and on macOS Finder's Services → "Send to
  Bridgey…" sends a selection, see [files](../features/files/README.md);
- legacy sections (calls, media, links, screen share, quick actions) only when
  the selected peer **is** the routed peer (`SelectedDeviceContext`
  `legacyFeaturesApply`), so one peer's state is never shown under another peer's name.

Three concepts stay separate: connected peers (the directory), `selectedDeviceId` (UI context)
and `preferredDeviceID` (where legacy single-peer features are routed). When they differ, a row
says which features currently use <peer> and offers "Use <selected> for these features", which only changes
`preferredDeviceID` (file transfers in progress keep their peer, MD-6). With no selection and
several peers nothing is shown as selected; with one peer it is selected automatically.

Known limitation: legacy sections still exist only for the routed peer until each feature is
migrated (MD-5, MD-6, …).

Related: [app](../app/README.md) · [features](../features/README.md)
