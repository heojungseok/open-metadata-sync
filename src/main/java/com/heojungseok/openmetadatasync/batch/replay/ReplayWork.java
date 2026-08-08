package com.heojungseok.openmetadatasync.batch.replay;

import java.util.Objects;

import com.heojungseok.openmetadatasync.batch.sync.SyncWorkDto;

public record ReplayWork(long errorKey, SyncWorkDto work) {

	public ReplayWork {
		Objects.requireNonNull(work);
	}
}
