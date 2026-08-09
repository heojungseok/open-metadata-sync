package com.heojungseok.openmetadatasync.batch.collect;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.LongSupplier;

import com.heojungseok.openmetadatasync.crossref.CrossrefPage;
import tools.jackson.databind.ObjectMapper;

public final class HttpCrossrefClient implements CrossrefCollector.CrossrefClient {

	private final HttpClient httpClient;
	private final Duration timeout;
	private final String userAgent;
	private final Clock clock;
	private final LongSupplier nanoTime;
	private final CrossrefCollector.Sleeper sleeper;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private long nextRequestNanos;

	private static final Duration MINIMUM_INTERVAL = Duration.ofMillis(400);

	public HttpCrossrefClient(HttpClient httpClient, Duration timeout, String userAgent, Clock clock) {
		this(httpClient, timeout, userAgent, clock, System::nanoTime, Thread::sleep);
	}

	HttpCrossrefClient(
			HttpClient httpClient,
			Duration timeout,
			String userAgent,
			Clock clock,
			LongSupplier nanoTime,
			CrossrefCollector.Sleeper sleeper
	) {
		this.httpClient = httpClient;
		this.timeout = timeout;
		this.userAgent = userAgent;
		this.clock = clock;
		this.nanoTime = nanoTime;
		this.sleeper = sleeper;
	}

	@Override
	public synchronized CrossrefPage fetch(URI pageUri, String cursor, int rows) {
		pace();
		URI uri = URI.create(pageUri + (pageUri.getRawQuery() == null ? "?" : "&")
				+ "cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8) + "&rows=" + rows);
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(timeout)
				.header("Accept", "application/json")
				.header("User-Agent", userAgent)
				.GET()
				.build();

		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			if (status >= 200 && status < 300) {
			nextRequestNanos = nanoTime.getAsLong() + successInterval(response).toNanos();
				return objectMapper.readValue(response.body(), CrossrefPage.class);
			}
			if (status == 404 || status == 410) {
				throw new CrossrefCollector.CursorExpiredException("Crossref cursor expired: HTTP " + status);
			}
			boolean retryable = status == 408 || status == 429 || status >= 500;
			throw new CrossrefCollector.CrossrefRequestException(
					"Crossref request failed: HTTP " + status,
					retryable,
					retryAfter(response)
			);
		} catch (IOException exception) {
			throw new CrossrefCollector.CrossrefRequestException("Crossref I/O failure", true, null, exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new CrossrefCollector.CrossrefRequestException("Crossref request interrupted", false, null, exception);
		}
	}

	private void pace() {
		long remaining = nextRequestNanos - nanoTime.getAsLong();
		if (remaining <= 0) {
			return;
		}
		try {
			sleeper.sleep(Duration.ofNanos(remaining));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new CrossrefCollector.CrossrefRequestException(
					"Crossref pacing interrupted", false, null, exception
			);
		}
	}

	private static Duration successInterval(HttpResponse<?> response) {
		try {
			long limit = Long.parseLong(response.headers().firstValue("X-Rate-Limit-Limit").orElse(""));
			Duration interval = parseInterval(
					response.headers().firstValue("X-Rate-Limit-Interval").orElse("")
			);
			if (limit <= 0 || interval == null || interval.isNegative() || interval.isZero()) {
				return MINIMUM_INTERVAL;
			}
			long nanos = Math.max(1, interval.toNanos() / limit);
			return Duration.ofNanos(Math.max(MINIMUM_INTERVAL.toNanos(), nanos));
		} catch (ArithmeticException | NumberFormatException ignored) {
			return MINIMUM_INTERVAL;
		}
	}

	private static Duration parseInterval(String value) {
		if (value.endsWith("ms")) {
			return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
		}
		if (value.endsWith("s")) {
			return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
		}
		return null;
	}

	private Duration retryAfter(HttpResponse<?> response) {
		return response.headers().firstValue("Retry-After").map(value -> {
			try {
				return Duration.ofSeconds(Long.parseLong(value));
			} catch (NumberFormatException ignored) {
				try {
					Duration duration = Duration.between(
							clock.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
					);
					return duration.isNegative() ? Duration.ZERO : duration;
				} catch (DateTimeParseException invalidHeader) {
					return null;
				}
			}
		}).orElse(null);
	}
}
