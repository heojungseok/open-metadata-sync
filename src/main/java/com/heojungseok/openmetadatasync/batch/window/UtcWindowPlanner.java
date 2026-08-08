package com.heojungseok.openmetadatasync.batch.window;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class UtcWindowPlanner {

	private UtcWindowPlanner() {
	}

	public static List<Window> plan(Instant fromInclusive, Instant untilExclusive) {
		if (!fromInclusive.isBefore(untilExclusive)) {
			throw new IllegalArgumentException("Window range must be increasing");
		}

		List<Window> windows = new ArrayList<>();
		Instant cursor = fromInclusive;
		while (cursor.isBefore(untilExclusive)) {
			Instant nextMidnight = cursor.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
					.atStartOfDay(ZoneOffset.UTC).toInstant();
			Instant end = nextMidnight.isBefore(untilExclusive) ? nextMidnight : untilExclusive;
			windows.add(new Window(windows.size(), cursor, end));
			cursor = end;
		}
		return List.copyOf(windows);
	}

	public static List<Window> pending(List<Window> windows, Set<Integer> completedSequences) {
		return windows.stream()
				.filter(window -> !completedSequences.contains(window.sequence()))
				.toList();
	}

	public record Window(int sequence, Instant fromInclusive, Instant untilExclusive) {

		public Window {
			if (sequence < 0 || !fromInclusive.isBefore(untilExclusive)) {
				throw new IllegalArgumentException("Invalid window");
			}
		}
	}
}
