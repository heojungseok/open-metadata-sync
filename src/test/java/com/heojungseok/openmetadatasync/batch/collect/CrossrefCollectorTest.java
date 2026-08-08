package com.heojungseok.openmetadatasync.batch.collect;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.heojungseok.openmetadatasync.crossref.CrossrefPage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossrefCollectorTest {

	private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID WINDOW_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final URI PAGE_URI = URI.create("https://api.crossref.org/works?filter=from-index-date:2026-08-01");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);

	@Test
	void keepsTwoCommittedPagesWhenTheFollowingHttpCallTimesOut() {
		FakeClient client = new FakeClient(
				response(1_000, "cursor-1", 3_000),
				response(1_000, "cursor-2", 3_000),
				retryable("timeout"), retryable("timeout"), retryable("timeout")
		);
		MemoryStore store = new MemoryStore();
		List<Duration> delays = new ArrayList<>();

		assertThatThrownBy(() -> collector(client, store, delays).collect(request(10_000)))
				.isInstanceOf(CrossrefCollector.CrossrefRequestException.class)
				.hasMessage("timeout");

		assertThat(client.cursors).containsExactly("*", "cursor-1", "cursor-2", "cursor-2", "cursor-2");
		assertThat(delays).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
		assertThat(store.rows).hasSize(2_000);
		assertThat(store.collectedCount).isEqualTo(2_000);
		assertThat(store.frozen).isFalse();
	}

	@Test
	void expiredCursorFailsImmediatelyWithoutRetrying() {
		FakeClient client = new FakeClient(new CrossrefCollector.CursorExpiredException("expired"));
		List<Duration> delays = new ArrayList<>();

		assertThatThrownBy(() -> collector(client, new MemoryStore(), delays).collect(request(10_000)))
				.isInstanceOf(CrossrefCollector.CursorExpiredException.class);

		assertThat(client.cursors).containsExactly("*");
		assertThat(delays).isEmpty();
	}

	@Test
	void retryAfterOverridesFallbackBeforeRetryExhaustion() {
		FakeClient client = new FakeClient(
				new CrossrefCollector.CrossrefRequestException("busy", true, Duration.ofSeconds(7)),
				retryable("busy"),
				retryable("busy")
		);
		List<Duration> delays = new ArrayList<>();

		assertThatThrownBy(() -> collector(client, new MemoryStore(), delays).collect(request(10_000)))
				.isInstanceOf(CrossrefCollector.CrossrefRequestException.class)
				.hasMessage("busy");

		assertThat(client.attempts).hasValue(3);
		assertThat(delays).containsExactly(Duration.ofSeconds(7), Duration.ofSeconds(2));
	}

	@Test
	void shortFinalPageFreezesExactly347ItemsEvenWithANextCursor() {
		FakeClient client = new FakeClient(response(347, "still-present", 347));
		MemoryStore store = new MemoryStore();

		CrossrefCollector.Result result = collector(client, store, new ArrayList<>()).collect(request(10_000));

		assertThat(result.expectedCount()).isEqualTo(347);
		assertThat(result.reportedTotalResults()).isEqualTo(347);
		assertThat(result.pagesFetched()).isEqualTo(1);
		assertThat(result.stopReason()).isEqualTo(CrossrefCollector.StopReason.SHORT_PAGE);
		assertThat(result.windowEvidence()).singleElement().satisfies(evidence -> {
			assertThat(evidence.reportedTotalResults()).isEqualTo(347);
			assertThat(evidence.derivedPageUpperBound()).isEqualTo(1);
			assertThat(evidence.effectivePageUpperBound()).isEqualTo(1);
			assertThat(evidence.pagesFetched()).isEqualTo(1);
		});
		assertThat(store.frozen).isTrue();
		assertThat(client.cursors).containsExactly("*");
	}

	@Test
	void emptyPageCompletesAnEmptyCollection() {
		FakeClient client = new FakeClient(response(0, "unused", 0));

		CrossrefCollector.Result result = collector(client, new MemoryStore(), new ArrayList<>()).collect(request(10_000));

		assertThat(result.expectedCount()).isZero();
		assertThat(result.stopReason()).isEqualTo(CrossrefCollector.StopReason.SHORT_PAGE);
	}

	@Test
	void maxItemsStopsInsideAFullPage() {
		FakeClient client = new FakeClient(response(1_000, "cursor-1", 20_000));

		CrossrefCollector.Result result = collector(client, new MemoryStore(), new ArrayList<>()).collect(request(347));

		assertThat(result.expectedCount()).isEqualTo(347);
		assertThat(result.stopReason()).isEqualTo(CrossrefCollector.StopReason.MAX_ITEMS);
	}

	@Test
	void exactMultipleTotalAllowsOneTerminalEmptyPageAndFreezesItsBound() {
		FakeClient client = new FakeClient(
				response(1_000, "cursor-1", 2_000),
				response(1_000, "cursor-2", 2_000),
				response(0, "unused", 2_000)
		);

		CrossrefCollector.Result result = collector(client, new MemoryStore(), new ArrayList<>()).collect(request(10_000));

		assertThat(result.pagesFetched()).isEqualTo(3);
		assertThat(result.windowEvidence()).singleElement().satisfies(evidence -> {
			assertThat(evidence.derivedPageUpperBound()).isEqualTo(3);
			assertThat(evidence.effectivePageUpperBound()).isEqualTo(3);
			assertThat(evidence.pagesFetched()).isEqualTo(3);
		});
	}

	@Test
	void restartReplaysFromStarAndDuplicateRefetchKeepsTheSameExecutionSequences() {
		MemoryStore store = new MemoryStore();
		FakeClient interrupted = new FakeClient(
				response(1_000, "cursor-1", 2_000),
				retryable("timeout"), retryable("timeout"), retryable("timeout")
		);
		assertThatThrownBy(() -> collector(interrupted, store, new ArrayList<>()).collect(request(10_000)))
				.isInstanceOf(CrossrefCollector.CrossrefRequestException.class);

		FakeClient restarted = new FakeClient(
				response(1_000, "cursor-1", 2_000),
				response(0, "unused", 2_000)
		);
		CrossrefCollector.Result result = collector(restarted, store, new ArrayList<>()).collect(request(10_000));

		assertThat(restarted.cursors).startsWith("*");
		assertThat(store.rows).hasSize(1_000);
		assertThat(result.expectedCount()).isEqualTo(1_000);
	}

	@Test
	void httpFetchRunsOutsideThePageTransaction() {
		FakeClient client = new FakeClient(response(1, "unused", 1)) {
			@Override
			public CrossrefPage fetch(URI pageUri, String cursor, int rows) {
				assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
				return super.fetch(pageUri, cursor, rows);
			}
		};

		collector(client, new MemoryStore(), new ArrayList<>()).collect(request(10_000));

		assertThat(client.attempts).hasValue(1);
	}

	@Test
	void repeatedCursorAndPageSafetyCapFailWithEvidence() {
		FakeClient noProgress = new FakeClient(
				response(1_000, "*", 9_000),
				response(1_000, "*", 9_000)
		);
		assertThatThrownBy(() -> collector(noProgress, new MemoryStore(), new ArrayList<>()).collect(request(10_000)))
				.isInstanceOfSatisfying(CrossrefCollector.CollectionSafetyException.class, exception -> {
					assertThat(exception.pagesFetched()).isEqualTo(2);
					assertThat(exception.reportedTotalResults()).isEqualTo(9_000);
				});

		FakeClient capped = new FakeClient(response(1_000, "cursor-1", 9_000));
		CrossrefCollector.Request cappedRequest = new CrossrefCollector.Request(
				EXECUTION_ID, List.of(new CrossrefCollector.Window(WINDOW_ID, PAGE_URI)), 10_000, 1, 2
		);
		assertThatThrownBy(() -> collector(capped, new MemoryStore(), new ArrayList<>()).collect(cappedRequest))
				.isInstanceOfSatisfying(CrossrefCollector.CollectionSafetyException.class, exception -> {
					assertThat(exception).hasMessageContaining("page safety cap");
					assertThat(exception.configuredPageSafetyCap()).isEqualTo(1);
					assertThat(exception.windowEvidence()).singleElement().satisfies(evidence -> {
						assertThat(evidence.reportedTotalResults()).isEqualTo(9_000);
						assertThat(evidence.derivedPageUpperBound()).isEqualTo(10);
						assertThat(evidence.effectivePageUpperBound()).isEqualTo(1);
						assertThat(evidence.pagesFetched()).isEqualTo(1);
					});
				});
	}

	@Test
	void fullPageWithoutANextCursorFailsBeforeAnyDatabaseWrite() {
		FakeClient client = new FakeClient(response(1_000, " ", 9_000));
		MemoryStore store = new MemoryStore();

		assertThatThrownBy(() -> collector(client, store, new ArrayList<>()).collect(request(10_000)))
				.isInstanceOf(CrossrefCollector.CollectionSafetyException.class)
				.hasMessageContaining("next cursor");

		assertThat(store.rows).isEmpty();
		assertThat(store.collectedCount).isZero();
		assertThat(store.frozen).isFalse();
	}

	private static CrossrefCollector collector(FakeClient client, MemoryStore store, List<Duration> delays) {
		return new CrossrefCollector(client, store, delays::add, CLOCK);
	}

	private static CrossrefCollector.Request request(long maxItems) {
		return new CrossrefCollector.Request(
				EXECUTION_ID, List.of(new CrossrefCollector.Window(WINDOW_ID, PAGE_URI)), maxItems, 10, 2
		);
	}

	private static CrossrefPage response(int itemCount, String nextCursor, long totalResults) {
		List<CrossrefPage.Work> items = IntStream.range(0, itemCount)
				.mapToObj(index -> work("10.1000/" + index))
				.toList();
		return new CrossrefPage(
				"ok", "work-list", "1.0.0",
				new CrossrefPage.Message(nextCursor, totalResults, itemCount, items)
		);
	}

	private static CrossrefPage.Work work(String doi) {
		return new CrossrefPage.Work(
				doi, List.of("Title"), "Publisher", "journal-article",
				new CrossrefPage.DateParts(List.of(List.of(2026, 8, 8))), "https://doi.org/" + doi,
				List.of(), new CrossrefPage.Timestamp("2026-08-08T00:00:00Z"),
				new CrossrefPage.Timestamp("2026-08-07T00:00:00Z")
		);
	}

	private static CrossrefCollector.CrossrefRequestException retryable(String message) {
		return new CrossrefCollector.CrossrefRequestException(message, true, null);
	}

	private static class FakeClient implements CrossrefCollector.CrossrefClient {
		private final Queue<Object> outcomes = new ArrayDeque<>();
		private final AtomicInteger attempts = new AtomicInteger();
		private final List<String> cursors = new ArrayList<>();

		FakeClient(Object... outcomes) {
			this.outcomes.addAll(List.of(outcomes));
		}

		@Override
		public CrossrefPage fetch(URI pageUri, String cursor, int rows) {
			attempts.incrementAndGet();
			cursors.add(cursor);
			Object outcome = outcomes.remove();
			if (outcome instanceof RuntimeException exception) {
				throw exception;
			}
			return (CrossrefPage) outcome;
		}
	}

	private static final class MemoryStore implements CrossrefCollector.Store {
		private final Map<Long, CrossrefPage.Work> rows = new LinkedHashMap<>();
		private long collectedCount;
		private boolean frozen;

		@Override
		public void validate(UUID executionId, long maxItems) {
		}

		@Override
		public long sequenceBefore(UUID executionId, UUID windowId) {
			return 0;
		}

		@Override
		public CrossrefCollector.Frozen persist(
				CrossrefCollector.PageWrite page,
				CrossrefCollector.Completion completion
		) {
			for (int index = 0; index < page.items().size(); index++) {
				rows.putIfAbsent(page.startSequence() + index, page.items().get(index));
			}
			collectedCount = page.windowCollectedCount();
			if (completion == CrossrefCollector.Completion.EXECUTION) {
				return complete(page.executionId());
			}
			return null;
		}

		@Override
		public CrossrefCollector.Frozen complete(UUID executionId) {
			frozen = true;
			return new CrossrefCollector.Frozen(rows.size(), rows.isEmpty() ? 0 : rows.size());
		}
	}
}
