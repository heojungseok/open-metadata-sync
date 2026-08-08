package com.heojungseok.openmetadatasync.batch.collect;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.heojungseok.openmetadatasync.batch.parameter.Tuning;
import com.heojungseok.openmetadatasync.crossref.CrossrefPage;

public final class CrossrefCollector {

	private static final int ATTEMPTS = 3;

	private final CrossrefClient client;
	private final Store store;
	private final Sleeper sleeper;
	private final Clock clock;

	public CrossrefCollector(CrossrefClient client, Store store, Sleeper sleeper, Clock clock) {
		this.client = Objects.requireNonNull(client);
		this.store = Objects.requireNonNull(store);
		this.sleeper = Objects.requireNonNull(sleeper);
		this.clock = Objects.requireNonNull(clock);
	}

	public Result collect(Request request) {
		long reportedTotalResults = 0;
		int pagesFetched = 0;
		StopReason stopReason = StopReason.SHORT_PAGE;

		for (Window window : request.windows()) {
			long sequenceBefore = store.sequenceBefore(request.executionId(), window.id());
			if (sequenceBefore >= request.maxItems()) {
				stopReason = StopReason.MAX_ITEMS;
				break;
			}

			String cursor = "*";
			long windowCount = 0;
			int noProgress = 0;
			boolean firstPage = true;

			while (true) {
				if (pagesFetched >= request.pageSafetyCap()) {
					throw safety("Crossref page safety cap reached", pagesFetched, reportedTotalResults);
				}

				Response response = fetch(window.pageUri(), cursor);
				CrossrefPage.Message message = requireMessage(response.page());
				pagesFetched++;
				if (firstPage) {
					reportedTotalResults += message.totalResults();
					firstPage = false;
				}

				List<CrossrefPage.Work> sourceItems = List.copyOf(message.items());
				boolean shortPage = sourceItems.size() < Tuning.CROSSREF_ROWS;
				long remaining = request.maxItems() - sequenceBefore - windowCount;
				int accepted = (int) Math.min(sourceItems.size(), remaining);
				List<CrossrefPage.Work> items = sourceItems.subList(0, accepted);

				if (!shortPage && Objects.equals(cursor, message.nextCursor())) {
					if (++noProgress >= request.consecutiveNoProgressLimit()) {
						throw safety("Crossref cursor made no progress", pagesFetched, reportedTotalResults);
					}
					continue;
				}
				noProgress = 0;

				store.persist(new PageWrite(
						request.executionId(), window.id(), cursor, message.nextCursor(),
						sequenceBefore + windowCount + 1, items, clock.instant()
				));
				windowCount += accepted;

				if (sequenceBefore + windowCount >= request.maxItems()) {
					store.completeWindow(window.id());
					stopReason = StopReason.MAX_ITEMS;
					Frozen frozen = store.freeze(request.executionId());
					return new Result(frozen.expectedCount(), frozen.stagingUpperBound(), reportedTotalResults,
							pagesFetched, stopReason);
				}
				if (shortPage) {
					store.completeWindow(window.id());
					break;
				}
				cursor = message.nextCursor();
			}
		}

		Frozen frozen = store.freeze(request.executionId());
		return new Result(frozen.expectedCount(), frozen.stagingUpperBound(), reportedTotalResults,
				pagesFetched, stopReason);
	}

	private Response fetch(URI pageUri, String cursor) {
		for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
			try {
				return client.fetch(pageUri, cursor, Tuning.CROSSREF_ROWS);
			} catch (CrossrefRequestException exception) {
				if (!exception.retryable() || attempt == ATTEMPTS) {
					throw exception;
				}
				Duration delay = exception.retryAfter() == null
						? Duration.ofSeconds(attempt)
						: exception.retryAfter();
				try {
					sleeper.sleep(delay);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new CrossrefRequestException("Crossref retry interrupted", false, null, interrupted);
				}
			}
		}
		throw new IllegalStateException("unreachable");
	}

	private static CrossrefPage.Message requireMessage(CrossrefPage page) {
		if (page == null || !"ok".equals(page.status()) || page.message() == null
				|| page.message().items() == null || page.message().totalResults() < 0) {
			throw new CrossrefRequestException("Invalid Crossref page", false, null);
		}
		return page.message();
	}

	private static CollectionSafetyException safety(String message, int pages, long totalResults) {
		return new CollectionSafetyException(message, pages, totalResults);
	}

	@FunctionalInterface
	public interface CrossrefClient {
		Response fetch(URI pageUri, String cursor, int rows);
	}

	public interface Store {
		long sequenceBefore(UUID executionId, UUID windowId);

		void persist(PageWrite page);

		void completeWindow(UUID windowId);

		Frozen freeze(UUID executionId);
	}

	@FunctionalInterface
	public interface Sleeper {
		void sleep(Duration duration) throws InterruptedException;
	}

	public record Request(
			UUID executionId,
			List<Window> windows,
			long maxItems,
			int pageSafetyCap,
			int consecutiveNoProgressLimit
	) {
		public Request {
			Objects.requireNonNull(executionId);
			windows = List.copyOf(windows);
			if (windows.isEmpty() || maxItems <= 0 || pageSafetyCap <= 0 || consecutiveNoProgressLimit <= 0) {
				throw new IllegalArgumentException("Invalid collection request");
			}
		}
	}

	public record Window(UUID id, URI pageUri) {
		public Window {
			Objects.requireNonNull(id);
			Objects.requireNonNull(pageUri);
		}
	}

	public record Response(CrossrefPage page) {
	}

	public record PageWrite(
			UUID executionId,
			UUID windowId,
			String cursor,
			String nextCursor,
			long startSequence,
			List<CrossrefPage.Work> items,
			Instant collectedAt
	) {
		public PageWrite {
			Objects.requireNonNull(executionId);
			Objects.requireNonNull(windowId);
			Objects.requireNonNull(cursor);
			items = List.copyOf(items);
			Objects.requireNonNull(collectedAt);
			if (startSequence <= 0) {
				throw new IllegalArgumentException("startSequence must be positive");
			}
		}
	}

	public record Frozen(long expectedCount, long stagingUpperBound) {
	}

	public record Result(
			long expectedCount,
			long stagingUpperBound,
			long reportedTotalResults,
			int pagesFetched,
			StopReason stopReason
	) {
	}

	public enum StopReason {
		SHORT_PAGE,
		MAX_ITEMS
	}

	public static class CrossrefRequestException extends RuntimeException {
		private final boolean retryable;
		private final Duration retryAfter;

		public CrossrefRequestException(String message, boolean retryable, Duration retryAfter) {
			this(message, retryable, retryAfter, null);
		}

		public CrossrefRequestException(String message, boolean retryable, Duration retryAfter, Throwable cause) {
			super(message, cause);
			this.retryable = retryable;
			this.retryAfter = retryAfter;
		}

		public boolean retryable() {
			return retryable;
		}

		public Duration retryAfter() {
			return retryAfter;
		}
	}

	public static final class CursorExpiredException extends CrossrefRequestException {
		public CursorExpiredException(String message) {
			super(message, false, null);
		}
	}

	public static final class CollectionSafetyException extends RuntimeException {
		private final int pagesFetched;
		private final long reportedTotalResults;

		private CollectionSafetyException(String message, int pagesFetched, long reportedTotalResults) {
			super(message);
			this.pagesFetched = pagesFetched;
			this.reportedTotalResults = reportedTotalResults;
		}

		public int pagesFetched() {
			return pagesFetched;
		}

		public long reportedTotalResults() {
			return reportedTotalResults;
		}
	}
}
