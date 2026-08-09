package com.heojungseok.openmetadatasync.batch.collect;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import com.heojungseok.openmetadatasync.crossref.CrossrefPage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpCrossrefClientTest {

	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void appendsCursorAndRowsAndDecodesTheExistingCrossrefPageType() throws IOException {
		AtomicReference<String> query = new AtomicReference<>();
		server = server(exchange -> {
			query.set(exchange.getRequestURI().getRawQuery());
			respond(exchange, 200, """
					{"status":"ok","message-type":"work-list","message-version":"1.0.0","message":{
					  "next-cursor":"next value","total-results":1,"items-per-page":1,
					  "items":[{"DOI":"10.1000/one","title":["Title"],"indexed":{"date-time":"2026-08-08T00:00:00Z"}}]
					}}
					""");
		});
		HttpCrossrefClient client = client();

		CrossrefPage response = client.fetch(
				uri("/works?filter=from-index-date%3A2026-08-01"), "cursor value/+", 1_000
		);

		assertThat(query.get()).isEqualTo(
				"filter=from-index-date%3A2026-08-01&cursor=cursor+value%2F%2B&rows=1000"
		);
		assertThat(response.message().items().getFirst().doi()).isEqualTo("10.1000/one");
	}

	@Test
	void exposesRetryAfterAndExpiredCursorWithoutHidingStatusEvidence() throws IOException {
		server = server(exchange -> {
			exchange.getResponseHeaders().add("Retry-After", "7");
			respond(exchange, 429, "busy");
		});
		HttpCrossrefClient client = client();

		assertThatThrownBy(() -> client.fetch(uri("/works"), "*", 1_000))
				.isInstanceOfSatisfying(CrossrefCollector.CrossrefRequestException.class, exception -> {
					assertThat(exception.retryable()).isTrue();
					assertThat(exception.retryAfter()).isEqualTo(Duration.ofSeconds(7));
					assertThat(exception.getMessage()).contains("429");
				});

		server.stop(0);
		server = server(exchange -> respond(exchange, 410, "cursor expired"));
		assertThatThrownBy(() -> client.fetch(uri("/works"), "expired", 1_000))
				.isInstanceOf(CrossrefCollector.CursorExpiredException.class)
				.hasMessageContaining("410");
	}

	@Test
	void pacesTheNextSuccessfulRequestFromRateHeadersWithAMonotonicClock() throws IOException {
		AtomicInteger requests = new AtomicInteger();
		server = server(exchange -> {
			requests.incrementAndGet();
			exchange.getResponseHeaders().add("X-Rate-Limit-Limit", "2");
			exchange.getResponseHeaders().add("X-Rate-Limit-Interval", "1s");
			respond(exchange, 200, emptyPage());
		});
		AtomicLong nanos = new AtomicLong();
		List<Duration> sleeps = new ArrayList<>();
		HttpCrossrefClient client = new HttpCrossrefClient(
				HttpClient.newHttpClient(), Duration.ofSeconds(2), "open-metadata-sync-test",
				Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC),
				nanos::get, delay -> {
					sleeps.add(delay);
					nanos.addAndGet(delay.toNanos());
				}
		);

		client.fetch(uri("/works"), "*", 1_000);
		client.fetch(uri("/works"), "next", 1_000);

		assertThat(requests).hasValue(2);
		assertThat(sleeps).containsExactly(Duration.ofMillis(500));
	}

	@Test
	void missingOrMalformedRateHeadersFallBackToFourHundredMilliseconds() throws IOException {
		AtomicInteger requests = new AtomicInteger();
		server = server(exchange -> {
			if (requests.incrementAndGet() == 2) {
				exchange.getResponseHeaders().add("X-Rate-Limit-Limit", "invalid");
				exchange.getResponseHeaders().add("X-Rate-Limit-Interval", "never");
			}
			respond(exchange, 200, emptyPage());
		});
		AtomicLong nanos = new AtomicLong();
		List<Duration> sleeps = new ArrayList<>();
		HttpCrossrefClient client = new HttpCrossrefClient(
				HttpClient.newHttpClient(), Duration.ofSeconds(2), "open-metadata-sync-test",
				Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC),
				nanos::get, delay -> {
					sleeps.add(delay);
					nanos.addAndGet(delay.toNanos());
				}
		);

		client.fetch(uri("/works"), "*", 1_000);
		client.fetch(uri("/works"), "next", 1_000);
		client.fetch(uri("/works"), "last", 1_000);

		assertThat(sleeps).containsExactly(Duration.ofMillis(400), Duration.ofMillis(400));
	}

	private HttpCrossrefClient client() {
		return new HttpCrossrefClient(
				HttpClient.newHttpClient(), Duration.ofSeconds(2), "open-metadata-sync-test",
				Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC)
		);
	}

	private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
		HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		created.createContext("/works", handler);
		created.start();
		return created;
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
	}

	private static String emptyPage() {
		return """
				{"status":"ok","message-type":"work-list","message-version":"1.0.0","message":{
				  "next-cursor":"next","total-results":0,"items-per-page":0,"items":[]
				}}
				""";
	}

	private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
			throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
