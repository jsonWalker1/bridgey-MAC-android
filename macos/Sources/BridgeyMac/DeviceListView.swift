import SwiftUI

/// MD-4: the connected devices, one row each; tapping a row selects that device (UI state only).
/// Knows nothing about features: it lists devices and changes `selection`.
struct DeviceListSection: View {
    let items: [DeviceListItem]
    @ObservedObject var selection: DeviceSelection

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Devices").font(.caption).foregroundStyle(.secondary)
            ForEach(items) { item in
                row(item)
            }
        }
    }

    private func row(_ item: DeviceListItem) -> some View {
        let selected = selection.selectedDeviceID == item.deviceID
        return Button {
            selection.selectedDeviceID = selected ? nil : item.deviceID
        } label: {
            HStack(spacing: 10) {
                Image(systemName: item.systemImage)
                    .font(.system(size: 15, weight: .medium))
                    .frame(width: 22)
                VStack(alignment: .leading, spacing: 1) {
                    Text(item.name).font(.callout).lineLimit(1)
                    Text([item.detail, item.isConnected ? "Connected" : "Offline"].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if selected { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(
            selected ? Color.accentColor.opacity(0.14) : Color.secondary.opacity(0.06),
            in: RoundedRectangle(cornerRadius: 9, style: .continuous)
        )
        .accessibilityLabel("\(item.name), \(item.detail)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
