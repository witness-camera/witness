import WidgetKit
import SwiftUI

@main
struct WitnessRecordWidgetBundle: WidgetBundle {
    var body: some Widget {
        StartRecordingWidget()
    }
}

/// Lock screen widget that deep-links into the instant-start flow. It uses a
/// URL (not an in-widget intent button) because recording requires the app
/// to come to the foreground for camera access — the deep link launches the
/// app straight into black-screen recording.
struct StartRecordingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "StartRecordingWidget",
                            provider: StaticProvider()) { _ in
            StartRecordingWidgetView()
                .widgetURL(URL(string: "witnessrecord://start"))
        }
        .configurationDisplayName("Start Witness Recording")
        .description("One tap from the lock screen starts a black-screen recording.")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .systemSmall, .systemMedium])
    }
}

struct StartRecordingWidgetView: View {
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryRectangular:
            HStack {
                Image(systemName: "record.circle.fill")
                    .font(.title3)
                VStack(alignment: .leading) {
                    Text("Witness").font(.headline)
                    Text("Start recording").font(.caption2)
                }
            }
            .containerBackground(.clear, for: .widget)
        case .systemSmall:
            VStack(spacing: 6) {
                Image(systemName: "record.circle.fill")
                    .font(.system(size: 40))
                    .foregroundStyle(.red)
                Text("Start Recording")
                    .font(.caption.bold())
            }
            .containerBackground(.black, for: .widget)
        case .systemMedium:
            HStack(spacing: 14) {
                Image(systemName: "record.circle.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(.red)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Start Recording").font(.headline)
                    Text("Tap to begin a witness recording").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding()
            .containerBackground(.black, for: .widget)
        default:
            Image(systemName: "record.circle.fill")
                .font(.system(size: 28))
                .containerBackground(.clear, for: .widget)
        }
    }
}

// A static timeline — the widget never changes; it's just a launcher.
struct StaticProvider: TimelineProvider {
    struct Entry: TimelineEntry { let date: Date }

    func placeholder(in context: Context) -> Entry { Entry(date: .now) }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        completion(Entry(date: .now))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        completion(Timeline(entries: [Entry(date: .now)], policy: .never))
    }
}
