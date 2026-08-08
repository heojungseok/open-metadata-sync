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

import com.heojungseok.openmetadatasync.crossref.CrossrefPage;
import tools.jackson.databind.ObjectMapper;

public final class HttpCrossrefClient implements CrossrefCollector.CrossrefClient {

	private final HttpClient httpClient;
	private final Duration timeout;
	private final String userAgent;
	private final Clock clock;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public HttpCrossrefClient(HttpClient httpClient, Duration timeout, String userAgent, Clock clock) {
		this.httpClient = httpClient;
		this.timeout = timeout;
		this.userAgent = userAgent;
		this.clock = clock;
	}

	@Override
	public CrossrefCollector.Response fetch(URI pageUri, String cursor, int rows) {
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
				return new CrossrefCollector.Response(objectMapper.readValue(response.body(), CrossrefPage.class));
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
