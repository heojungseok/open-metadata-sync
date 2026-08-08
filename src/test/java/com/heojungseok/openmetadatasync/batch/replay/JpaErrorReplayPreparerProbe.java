package com.heojungseok.openmetadatasync.batch.replay;

import jakarta.persistence.EntityManager;

public final class JpaErrorReplayPreparerProbe {

	private JpaErrorReplayPreparerProbe() {
	}

	public static JpaErrorReplayPreparer afterSnapshot(EntityManager entityManager, Runnable callback) {
		return new JpaErrorReplayPreparer(entityManager, callback);
	}
}
