package com.heojungseok.openmetadatasync.batch.collect;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.heojungseok.openmetadatasync.batch.parameter.Tuning;
import com.heojungseok.openmetadatasync.crossref.CrossrefPage;

public final class CrossrefCollector {

	private static final int ATTEMPTS = 3;
	private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);

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
		store.validate(
				request.executionId(), request.maxItems(), request.windows().stream().map(Window::id).toList()
		);
		long reportedTotalResults = 0;
		int pagesFetched = 0;
		StopReason stopReason = StopReason.SHORT_PAGE;
		List<WindowEvidence> windowEvidence = new ArrayList<>();

		for (int windowIndex = 0; windowIndex < request.windows().size(); windowIndex++) {
			Window window = request.windows().get(windowIndex);
			WindowProgress progress = store.progress(request.executionId(), window.id());
			long sequenceBefore = progress.sequenceBefore();
			long replayFrontier = progress.committedWindowCount();
			if (sequenceBefore >= request.maxItems()) {
				stopReason = StopReason.MAX_ITEMS;
				Frozen frozen = store.complete(request.executionId());
				return new Result(frozen.expectedCount(), frozen.stagingUpperBound(), reportedTotalResults,
						pagesFetched, stopReason, windowEvidence, request.pageSafetyCap());
			}

			String cursor = "*";
			long windowCount = 0;
			List<CrossrefPage.Work> previousFullPage = List.of();
			int repeatedFullPages = 0;
			int zeroNewPages = 0;
			int windowPagesFetched = 0;
			int windowPagesAdvanced = 0;
			long effectivePageUpperBound = 0;
			boolean firstPage = true;

			while (true) {
				if (pagesFetched >= request.pageSafetyCap()
						|| (!firstPage && windowPagesAdvanced >= effectivePageUpperBound)) {
					throw safety("Crossref page safety cap reached", request, pagesFetched,
							reportedTotalResults, windowEvidence);
				}

				CrossrefPage.Message message;
				try {
					message = requireMessage(fetch(window.pageUri(), cursor));
				} catch (CrossrefRequestException exception) {
					throw exception.withEvidence(
							pagesFetched, reportedTotalResults, request.pageSafetyCap(), windowEvidence
					);
				}
				pagesFetched++;
				windowPagesFetched++;
				if (firstPage) {
					reportedTotalResults += message.totalResults();
					long derivedPageUpperBound = derivePageUpperBound(
							message.totalResults(), request.maxItems() - sequenceBefore
					);
					effectivePageUpperBound = Math.min(
							derivedPageUpperBound, request.pageSafetyCap() - pagesFetched + 1L
					);
					windowEvidence.add(new WindowEvidence(
							window.id(), message.totalResults(), derivedPageUpperBound,
							effectivePageUpperBound, 0, 0
					));
					firstPage = false;
				}
				WindowEvidence frozenEvidence = windowEvidence.getLast();
				windowEvidence.set(windowEvidence.size() - 1, new WindowEvidence(
						frozenEvidence.windowId(), frozenEvidence.reportedTotalResults(),
						frozenEvidence.derivedPageUpperBound(), frozenEvidence.effectivePageUpperBound(),
						windowPagesFetched, frozenEvidence.newStagingRows()
				));

				List<CrossrefPage.Work> sourceItems = List.copyOf(message.items());
				boolean shortPage = sourceItems.size() < Tuning.CROSSREF_ROWS;
				if (!shortPage && (message.nextCursor() == null || message.nextCursor().isBlank())) {
					throw safety("Crossref full page has no next cursor", request, pagesFetched,
							reportedTotalResults, windowEvidence);
				}
				if (!shortPage && sourceItems.equals(previousFullPage)) {
					if (++repeatedFullPages >= request.consecutiveNoProgressLimit()) {
						throw safety("Crossref page payload made no progress", request, pagesFetched,
								reportedTotalResults, windowEvidence);
					}
					continue;
				}
				repeatedFullPages = 0;
				previousFullPage = sourceItems;
				windowPagesAdvanced++;
				long remaining = request.maxItems() - sequenceBefore - windowCount;
				int accepted = (int) Math.min(sourceItems.size(), remaining);
				List<CrossrefPage.Work> items = sourceItems.subList(0, accepted);

				boolean maxReached = sequenceBefore + windowCount + accepted >= request.maxItems();
				Completion completion = maxReached || (shortPage && windowIndex == request.windows().size() - 1)
						? Completion.EXECUTION
						: shortPage ? Completion.WINDOW : Completion.PAGE;
				if (completion != Completion.PAGE && windowCount + accepted < replayFrontier) {
					throw safety("Crossref replay ended before durable replay frontier", request, pagesFetched,
							reportedTotalResults, windowEvidence);
				}
				PageCommit commit = store.persist(new PageWrite(
						request.executionId(), window.id(), cursor, message.nextCursor(),
						sequenceBefore + windowCount + 1, windowCount + accepted, replayFrontier,
						items, clock.instant()
				), completion);
				windowCount += accepted;
				frozenEvidence = windowEvidence.getLast();
				windowEvidence.set(windowEvidence.size() - 1, new WindowEvidence(
						frozenEvidence.windowId(), frozenEvidence.reportedTotalResults(),
						frozenEvidence.derivedPageUpperBound(), frozenEvidence.effectivePageUpperBound(),
						frozenEvidence.pagesFetched(), frozenEvidence.newStagingRows() + commit.insertedCount()
				));
				if (!items.isEmpty() && commit.insertedCount() == 0 && windowCount > replayFrontier) {
					if (++zeroNewPages >= request.consecutiveNoProgressLimit()) {
						throw safety("Crossref pages added no new staging rows", request, pagesFetched,
								reportedTotalResults, windowEvidence);
					}
					if (message.nextCursor() != null && !message.nextCursor().isBlank()) {
						cursor = message.nextCursor();
					}
					continue;
				}
				if (commit.insertedCount() > 0) {
					zeroNewPages = 0;
				}

				if (maxReached) {
					stopReason = StopReason.MAX_ITEMS;
					return new Result(commit.frozen().expectedCount(), commit.frozen().stagingUpperBound(), reportedTotalResults,
							pagesFetched, stopReason, windowEvidence, request.pageSafetyCap());
				}
				if (shortPage) {
					if (completion == Completion.EXECUTION) {
						return new Result(commit.frozen().expectedCount(), commit.frozen().stagingUpperBound(), reportedTotalResults,
								pagesFetched, stopReason, windowEvidence, request.pageSafetyCap());
					}
					break;
				}
				cursor = message.nextCursor();
			}
		}

		throw new IllegalStateException("Collection ended without execution completion");
	}

	private CrossrefPage fetch(URI pageUri, String cursor) {
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
				if (delay.isNegative() || delay.compareTo(MAX_RETRY_AFTER) > 0) {
					throw new CrossrefRequestException(
							"Crossref Retry-After exceeds demo retry budget", false, delay, exception
					);
				}
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

	private static long derivePageUpperBound(long totalResults, long remainingMaxItems) {
		if (remainingMaxItems <= totalResults) {
			return (remainingMaxItems - 1) / Tuning.CROSSREF_ROWS + 1;
		}
		return totalResults / Tuning.CROSSREF_ROWS + 1;
	}

	private static CollectionSafetyException safety(
			String message,
			Request request,
			int pages,
			long totalResults,
			List<WindowEvidence> evidence
	) {
		return new CollectionSafetyException(
				message, pages, totalResults, request.pageSafetyCap(), List.copyOf(evidence)
		);
	}

	@FunctionalInterface
	public interface CrossrefClient {
		CrossrefPage fetch(URI pageUri, String cursor, int rows);
	}

	public interface Store {
		void validate(UUID executionId, long maxItems, List<UUID> windowIds);

		WindowProgress progress(UUID executionId, UUID windowId);

		PageCommit persist(PageWrite page, Completion completion);

		Frozen complete(UUID executionId);
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

	public record PageWrite(
			UUID executionId,
			UUID windowId,
			String cursor,
			String nextCursor,
			long startSequence,
			long windowCollectedCount,
			long replayFrontier,
			List<CrossrefPage.Work> items,
			Instant collectedAt
	) {
		public PageWrite {
			Objects.requireNonNull(executionId);
			Objects.requireNonNull(windowId);
			Objects.requireNonNull(cursor);
			items = List.copyOf(items);
			Objects.requireNonNull(collectedAt);
			if (startSequence <= 0 || windowCollectedCount < 0 || replayFrontier < 0) {
				throw new IllegalArgumentException("Invalid page progress");
			}
		}
	}

	public record Frozen(long expectedCount, long stagingUpperBound) {
	}

	public record WindowProgress(long sequenceBefore, long committedWindowCount) {
	}

	public record PageCommit(int insertedCount, Frozen frozen) {
	}

	public enum Completion {
		PAGE,
		WINDOW,
		EXECUTION
	}

	public record Result(
			long expectedCount,
			long stagingUpperBound,
			long reportedTotalResults,
			int pagesFetched,
			StopReason stopReason,
			List<WindowEvidence> windowEvidence,
			int configuredPageSafetyCap
	) {
		public Result {
			windowEvidence = List.copyOf(windowEvidence);
		}
	}

	public record WindowEvidence(
			UUID windowId,
			long reportedTotalResults,
			long derivedPageUpperBound,
			long effectivePageUpperBound,
			int pagesFetched,
			long newStagingRows
	) {
	}

	public enum StopReason {
		SHORT_PAGE,
		MAX_ITEMS
	}

	public static class CrossrefRequestException extends RuntimeException {
		private final boolean retryable;
		private final Duration retryAfter;
		private int pagesFetched;
		private long reportedTotalResults;
		private int configuredPageSafetyCap;
		private List<WindowEvidence> windowEvidence = List.of();

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

		private CrossrefRequestException withEvidence(
				int pagesFetched,
				long reportedTotalResults,
				int configuredPageSafetyCap,
				List<WindowEvidence> windowEvidence
		) {
			this.pagesFetched = pagesFetched;
			this.reportedTotalResults = reportedTotalResults;
			this.configuredPageSafetyCap = configuredPageSafetyCap;
			this.windowEvidence = List.copyOf(windowEvidence);
			return this;
		}

		public int pagesFetched() {
			return pagesFetched;
		}

		public long reportedTotalResults() {
			return reportedTotalResults;
		}

		public int configuredPageSafetyCap() {
			return configuredPageSafetyCap;
		}

		public List<WindowEvidence> windowEvidence() {
			return windowEvidence;
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
		private final int configuredPageSafetyCap;
		private final List<WindowEvidence> windowEvidence;

		private CollectionSafetyException(
				String message,
				int pagesFetched,
				long reportedTotalResults,
				int configuredPageSafetyCap,
				List<WindowEvidence> windowEvidence
		) {
			super(message);
			this.pagesFetched = pagesFetched;
			this.reportedTotalResults = reportedTotalResults;
			this.configuredPageSafetyCap = configuredPageSafetyCap;
			this.windowEvidence = windowEvidence;
		}

		public int pagesFetched() {
			return pagesFetched;
		}

		public long reportedTotalResults() {
			return reportedTotalResults;
		}

		public int configuredPageSafetyCap() {
			return configuredPageSafetyCap;
		}

		public List<WindowEvidence> windowEvidence() {
			return windowEvidence;
		}
	}
}
