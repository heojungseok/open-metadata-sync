package com.heojungseok.openmetadatasync.canonical;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({
		"canonicalVersion", "doi", "title", "publisher", "type",
		"issuedDate", "issuedDatePrecision", "url", "authors"
})
public record CanonicalWork(
		int canonicalVersion,
		String doi,
		String title,
		String publisher,
		String type,
		String issuedDate,
		Integer issuedDatePrecision,
		String url,
		List<Author> authors
) {

	@JsonPropertyOrder({"given", "family", "orcid"})
	public record Author(String given, String family, String orcid) {
	}
}
