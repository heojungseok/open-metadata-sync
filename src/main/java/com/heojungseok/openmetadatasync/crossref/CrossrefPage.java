package com.heojungseok.openmetadatasync.crossref;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrossrefPage(
		String status,
		@JsonProperty("message-type") String messageType,
		@JsonProperty("message-version") String messageVersion,
		Message message
) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Message(
			@JsonProperty("next-cursor") String nextCursor,
			@JsonProperty("total-results") long totalResults,
			@JsonProperty("items-per-page") int itemsPerPage,
			List<Work> items
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Work(
			@JsonProperty("DOI") String doi,
			@JsonProperty("title") List<String> titles,
			String publisher,
			String type,
			DateParts issued,
			@JsonProperty("URL") String url,
			@JsonProperty("author") List<Author> authors,
			Timestamp indexed,
			Timestamp created
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Author(
			String given,
			String family,
			@JsonProperty("ORCID") String orcid
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record DateParts(@JsonProperty("date-parts") List<List<Integer>> dateParts) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Timestamp(@JsonProperty("date-time") String dateTime) {
	}
}
