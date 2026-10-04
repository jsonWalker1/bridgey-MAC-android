import XCTest
@testable import BridgeyMac

/// MD-4 device list: presentation of the directory, selection as UI state, explicit targets.
final class DeviceListTests: XCTestCase {
    private let phone = "10000000-0000-4000-8000-00000000000a"
    private let mac = "20000000-0000-4000-8000-00000000000b"
    private let air = "30000000-0000-4000-8000-00000000000c"

    private func entry(_ id: String, _ name: String, _ platform: DevicePlatform, _ kind: DeviceKind,
                       connected: Bool = true, routed: Bool = false) -> DeviceDirectoryEntry {
        DeviceDirectoryEntry(deviceID: id, name: name, isTrusted: true, connection: connected ? .connected : .offline,
                             capabilities: connected ? ["ping": true, "find_device": true] : nil,
                             platform: platform, kind: kind, isRouted: routed)
    }

    private var three: [DeviceDirectoryEntry] {
        // As the directory returns them: sorted by name.
        [entry(air, "MacBook Air", .macos, .computer),
         entry(phone, "S23 Ultra", .android, .phone),
         entry(mac, "Tomášův MacBook", .macos, .computer)]
    }

    func testEmptyDirectory() {
        XCTAssertEqual(DeviceList.items([]), [])
        XCTAssertNil(DeviceList.reconcile(selected: phone, items: []))
        XCTAssertEqual(DeviceList.target(selected: nil, eligible: []), DeviceTarget.none)
    }

    func testOneDevice() {
        let items = DeviceList.items([entry(phone, "S23 Ultra", .android, .phone)])
        XCTAssertEqual(items.map(\.name), ["S23 Ultra"])
        XCTAssertEqual(items[0].detail, "Android · Phone")
        XCTAssertEqual(DeviceList.reconcile(selected: nil, items: items), phone, "a single device needs no choice")
    }

    func testTwoAndThreeDevicesAreAllVisible() {
        XCTAssertEqual(DeviceList.items(Array(three.prefix(2))).count, 2)
        let items = DeviceList.items(three)
        XCTAssertEqual(items.map(\.name), ["MacBook Air", "S23 Ultra", "Tomášův MacBook"])
        XCTAssertEqual(items.map(\.detail), ["macOS · Computer", "Android · Phone", "macOS · Computer"])
        XCTAssertNil(DeviceList.reconcile(selected: nil, items: items), "several devices: nothing is chosen for the user")
    }

    func testOrderingIsDeterministicConnectedFirst() {
        var entries = three
        entries[0] = entry(air, "MacBook Air", .macos, .computer, connected: false)
        let all = DeviceList.items(entries, connectedOnly: false)
        XCTAssertEqual(all.map(\.deviceID), [phone, mac, air])
        XCTAssertEqual(DeviceList.items(entries, connectedOnly: false), all, "same input, same order")
        XCTAssertEqual(DeviceList.items(entries).map(\.deviceID), [phone, mac], "the list shows connected devices")
    }

    func testSelectingAOrB() {
        let items = DeviceList.items(three)
        XCTAssertEqual(DeviceList.reconcile(selected: phone, items: items), phone)
        XCTAssertEqual(DeviceList.reconcile(selected: mac, items: items), mac)
    }

    func testSelectedDeviceDisappears() {
        let items = DeviceList.items(three.filter { $0.deviceID != phone })
        XCTAssertNil(DeviceList.reconcile(selected: phone, items: items), "two remain: the selection is cleared, not reinterpreted")
        let lastOne = DeviceList.items([entry(mac, "Tomášův MacBook", .macos, .computer)])
        XCTAssertEqual(DeviceList.reconcile(selected: phone, items: lastOne), mac, "one remains: it is the only possible target")
    }

    func testAnotherDeviceRemainsUsableWhenOneDisconnects() {
        let afterDisconnect = DeviceList.items(three.filter { $0.deviceID != air })
        XCTAssertEqual(DeviceList.reconcile(selected: mac, items: afterDisconnect), mac)
        XCTAssertEqual(DeviceList.target(selected: mac, eligible: afterDisconnect.map(\.deviceID)), .device(mac))
    }

    func testReconnectDoesNotDuplicateTheEntry() {
        let offline = three.map { $0.deviceID == phone ? entry(phone, "S23 Ultra", .android, .phone, connected: false) : $0 }
        XCTAssertEqual(DeviceList.items(offline).count, 2)
        let back = DeviceList.items(three)
        XCTAssertEqual(back.count, 3)
        XCTAssertEqual(back.filter { $0.deviceID == phone }.count, 1)
    }

    func testProfileUpdateRefreshesThePresentation() {
        let before = DeviceList.items([entry(phone, "Phone", .unknown, .unknown)])
        XCTAssertEqual(before[0].detail, "")
        XCTAssertEqual(before[0].systemImage, "display")
        let after = DeviceList.items([entry(phone, "S23 Ultra", .android, .phone)])
        XCTAssertEqual(after[0].name, "S23 Ultra")
        XCTAssertEqual(after[0].detail, "Android · Phone")
        XCTAssertEqual(after[0].systemImage, "smartphone")
    }

    func testPingAndFindTargetsStayExplicit() {
        let eligible = [phone, mac]
        XCTAssertEqual(DeviceList.target(selected: mac, eligible: eligible), .device(mac))
        XCTAssertEqual(DeviceList.target(selected: nil, eligible: eligible), .choose(eligible), "no selection: ask, never guess")
        XCTAssertEqual(DeviceList.target(selected: air, eligible: eligible), .choose(eligible),
                       "a selected device the feature does not apply to is not silently replaced")
        XCTAssertEqual(DeviceList.target(selected: nil, eligible: [phone]), .device(phone))
    }

    func testNoActiveSessionFallback() {
        // The routed device is not an input: a routed-but-unselected device is never chosen when
        // several devices are eligible.
        let entries = three.map { $0.deviceID == mac ? entry(mac, "Tomášův MacBook", .macos, .computer, routed: true) : $0 }
        let eligible = DeviceList.items(entries).map(\.deviceID)
        XCTAssertEqual(DeviceList.target(selected: nil, eligible: eligible), .choose(eligible))
        XCTAssertNil(DeviceList.reconcile(selected: nil, items: DeviceList.items(entries)))
    }

    func testSelectionObjectReconcilesOnlyThroughTheModel() {
        let selection = DeviceSelection()
        selection.selectedDeviceID = phone
        selection.reconcile(with: DeviceList.items(three))
        XCTAssertEqual(selection.selectedDeviceID, phone)
        selection.reconcile(with: DeviceList.items(three.filter { $0.deviceID != phone }))
        XCTAssertNil(selection.selectedDeviceID)
    }

    // MARK: - MD-4b selected-device context (peer-centric)

    func testSingleDeviceIsBothSelectedAndRoutedSoLegacyFeaturesApply() {
        let items = DeviceList.items([entry(phone, "S23 Ultra", .android, .phone, routed: true)])
        let selected = DeviceList.reconcile(selected: nil, items: items)
        let context = SelectedDeviceContext.make(items: items, selectedDeviceID: selected, routedDeviceID: phone)
        XCTAssertEqual(context.selected?.deviceID, phone)
        XCTAssertTrue(context.legacyFeaturesApply, "one peer keeps today's full card")
        XCTAssertNil(context.legacyFeaturesUseOtherPeer)
    }

    func testSelectedAndRoutedCanDifferAndLegacyStateIsNotShownUnderTheSelectedPeer() {
        let items = DeviceList.items(three)
        let context = SelectedDeviceContext.make(items: items, selectedDeviceID: phone, routedDeviceID: mac)
        XCTAssertEqual(context.selected?.name, "S23 Ultra")
        XCTAssertFalse(context.legacyFeaturesApply, "MacBook's legacy state must never appear under the S23 header")
        XCTAssertEqual(context.legacyFeaturesUseOtherPeer?.name, "Tomášův MacBook")
    }

    func testSelectingTheRoutedPeerShowsItsLegacyState() {
        let context = SelectedDeviceContext.make(items: DeviceList.items(three), selectedDeviceID: mac, routedDeviceID: mac)
        XCTAssertTrue(context.legacyFeaturesApply)
        XCTAssertNil(context.legacyFeaturesUseOtherPeer)
    }

    func testNoSelectionIsNotReplacedByTheRoutedPeer() {
        let context = SelectedDeviceContext.make(items: DeviceList.items(three), selectedDeviceID: nil, routedDeviceID: mac)
        XCTAssertNil(context.selected, "the routed peer is never shown as the selected one")
        XCTAssertFalse(context.legacyFeaturesApply)
        XCTAssertEqual(context.legacyFeaturesUseOtherPeer?.deviceID, mac, "but where legacy features go is explained")
    }

    func testASelectedPeerThatDisconnectedIsNotShown() {
        let items = DeviceList.items(three.filter { $0.deviceID != phone })
        let context = SelectedDeviceContext.make(items: items, selectedDeviceID: phone, routedDeviceID: mac)
        XCTAssertNil(context.selected)
    }

    func testSelectionNeverChangesRouting() {
        // The context only reads both ids; choosing a peer leaves the routed id as it was.
        let routed = mac
        for selected in [phone, air, mac] {
            let context = SelectedDeviceContext.make(items: DeviceList.items(three), selectedDeviceID: selected, routedDeviceID: routed)
            XCTAssertEqual(context.routed?.deviceID, routed)
            XCTAssertEqual(context.selected?.deviceID, selected)
        }
    }
}
