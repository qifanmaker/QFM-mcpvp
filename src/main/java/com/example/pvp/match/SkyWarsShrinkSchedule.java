package com.example.pvp.match;

/** 按明确的终局时间收束地图，避免大图每次只缩四格直到超时仍无法交战。 */
public record SkyWarsShrinkSchedule(int initialRadius, int finalRadius, int startSeconds,
                                   int intervalSeconds, int finalSeconds) {
    public SkyWarsShrinkSchedule {
        initialRadius = Math.max(1, initialRadius);
        finalRadius = Math.max(1, Math.min(initialRadius, finalRadius));
        startSeconds = Math.max(15, startSeconds);
        intervalSeconds = Math.max(1, intervalSeconds);
        finalSeconds = Math.max(startSeconds, finalSeconds);
    }

    public int stages() {
        return (finalSeconds - startSeconds + intervalSeconds - 1) / intervalSeconds + 1;
    }

    public int stageAt(int seconds) {
        if (seconds < startSeconds) return 0;
        if (seconds >= finalSeconds) return stages();
        return Math.min(stages(), (seconds - startSeconds) / intervalSeconds + 1);
    }

    public int secondsForStage(int stage) {
        return Math.min(finalSeconds, startSeconds + (Math.max(1, stage) - 1) * intervalSeconds);
    }

    public int radiusForStage(int stage) {
        int bounded = Math.max(0, Math.min(stages(), stage));
        return initialRadius - (int) Math.ceil((initialRadius - finalRadius) * (double) bounded / stages());
    }

    public int warningSeconds() {
        return Math.min(15, Math.max(1, intervalSeconds - 1));
    }
}
