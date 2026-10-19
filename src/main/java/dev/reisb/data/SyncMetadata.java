package dev.reisb.data;

public record SyncMetadata(int cacheVersion, int schemaVersion, String neuCommit, String neuDigest,
                            String githubEtag, String githubModified, String hypixelEtag, String hypixelModified,
                            String hypixelDigest, long hypixelLastUpdated, long lastChecked, long lastUpdated,
                            SnapshotDiff changes) {
    public static final int CACHE_VERSION = 1;
    public static final int SCHEMA_VERSION = 1;
    public static final SyncMetadata EMPTY = new SyncMetadata(CACHE_VERSION, SCHEMA_VERSION,
            "", "", "", "", "", "", "", 0, 0, 0, SnapshotDiff.EMPTY);

    public String revision() {
        return "NEU=" + (neuCommit.isEmpty() ? (neuDigest.isEmpty() ? "none" : "sha256:" + neuDigest) : neuCommit)
                + " | Hypixel=" + (hypixelDigest.isEmpty() ? "none" : hypixelLastUpdated + ":sha256:" + hypixelDigest);
    }

    public SyncMetadata withLastUpdated(long time) {
        return new SyncMetadata(cacheVersion, schemaVersion, neuCommit, neuDigest, githubEtag, githubModified,
                hypixelEtag, hypixelModified, hypixelDigest, hypixelLastUpdated, lastChecked, time, changes);
    }
}
