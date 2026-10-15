package cn.anhuatv.live.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Models {
    private Models() {}

    public static final class Source {
        public final String id, name, url;
        public final boolean enabled;
        public final int priority;
        public Source(String id, String name, String url, boolean enabled, int priority) {
            this.id = id; this.name = name; this.url = url;
            this.enabled = enabled; this.priority = priority;
        }
        public Source enabled(boolean value) { return new Source(id, name, url, value, priority); }
    }

    public static final class Stream {
        public final String url, sourceId, sourceName, group, catchup, catchupSource;
        public final int priority;
        public final Map<String, String> headers;
        public Stream(String url, Source source, String group, Map<String, String> headers,
                      String catchup, String catchupSource) {
            this.url = url; this.sourceId = source.id; this.sourceName = source.name;
            this.priority = source.priority; this.group = group;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.catchup = catchup; this.catchupSource = catchupSource;
        }
        // Include request headers: the same address with different authorization can be distinct.
        public String key() {
            List<String> entries = new ArrayList<>();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                entries.add(e.getKey().toLowerCase(java.util.Locale.ROOT) + "=" + e.getValue());
            }
            Collections.sort(entries);
            return url + "|" + entries;
        }
    }

    public static final class Entry {
        public final String name, tvgId;
        public final Stream stream;
        public Entry(String name, String tvgId, Stream stream) {
            this.name = name; this.tvgId = tvgId; this.stream = stream;
        }
    }

    public static final class Channel {
        public final String id, name, group;
        public final List<Stream> streams;
        public Channel(String id, String name, String group, List<Stream> streams) {
            this.id = id; this.name = name; this.group = group;
            this.streams = Collections.unmodifiableList(new ArrayList<>(streams));
        }
    }
}
