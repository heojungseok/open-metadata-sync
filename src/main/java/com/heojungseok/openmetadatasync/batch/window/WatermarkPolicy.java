package com.heojungseok.openmetadatasync.batch.window;

import java.time.Instant;

public final class WatermarkPolicy {

	private WatermarkPolicy() {
	}

	public static Instant advance(
			Instant current,
			Instant requestedUntil,
			boolean fullRangeReconciled,
			boolean hasConflicts
	) {
		if (!fullRangeReconciled || hasConflicts) {
			throw new IllegalStateException("Watermark requires conflict-free full-range reconciliation");
		}
		if (current != null && requestedUntil.isBefore(current)) {
			throw new IllegalArgumentException("Watermark cannot move backwards");
		}
		return requestedUntil;
	}
}
