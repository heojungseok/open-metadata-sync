package com.heojungseok.openmetadatasync.batch.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionStatusTest {

	@Test
	void permitsTheHappyPathAndBusinessErrorCompletion() {
		assertThatCode(() -> ExecutionStatus.PREPARING.requireTransitionTo(ExecutionStatus.COLLECTING))
				.doesNotThrowAnyException();
		assertThatCode(() -> ExecutionStatus.COLLECTING.requireTransitionTo(ExecutionStatus.COLLECTED))
				.doesNotThrowAnyException();
		assertThatCode(() -> ExecutionStatus.COLLECTED.requireTransitionTo(ExecutionStatus.SYNCING))
				.doesNotThrowAnyException();
		assertThatCode(() -> ExecutionStatus.SYNCING.requireTransitionTo(ExecutionStatus.VERIFYING))
				.doesNotThrowAnyException();
		assertThatCode(() -> ExecutionStatus.VERIFYING.requireTransitionTo(ExecutionStatus.COMPLETED_WITH_ERRORS))
				.doesNotThrowAnyException();
	}

	@Test
	void anyActiveStateMayFailButModeIsASeparateType() {
		assertThatCode(() -> ExecutionStatus.COLLECTING.requireTransitionTo(ExecutionStatus.FAILED))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> ExecutionStatus.COMPLETED.requireTransitionTo(ExecutionStatus.SYNCING))
				.isInstanceOf(IllegalStateException.class);
		assertThatCode(() -> RunMode.valueOf("INCREMENTAL")).doesNotThrowAnyException();
	}
}
