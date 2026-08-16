# 실제 Crossref 1만 건 재시작 기록

2026-08-16에 수행한 실제 외부 API 실행 중 강제 종료와 재시작 검증의 기록입니다.

## 실행 기준

| 항목 | 값 |
|---|---|
| 최종 구현 | `develop@46e658594926228d929cc4fdce43a9f5aac7652e` |
| Jenkins | crossref `#9`(강제 종료) · `#10`(재개) · `#11`(거부), request `live-restart-001` |
| 데이터베이스 | `open_metadata` — 실제 10만 건 `#6`을 수행한 것과 같은 스키마 |
| 소요 시간 | `#9` 62초 · `#10` 13초 · `#11` 12초 |
| 수집 범위 | 생성일 2026-08-01~2026-08-08, `maxItems=10000` |
| 로컬 검증 | `./gradlew test --rerun-tasks` — 25 suites·161 tests, 실패·오류·스킵 0건 |

`#8`은 `gradle.lockfile` 결함으로 애플리케이션이 기동하지 못하고 종료했습니다. 재시작 증빙이 아닙니다.

## 무엇을 검증했는가

세 가지를 분리해서 확인했습니다.

1. sync 진행 중 프로세스를 강제 종료해도 커밋된 청크 경계부터 재개하는가
2. 재개할 때 외부 API를 다시 호출하지 않는가
3. 동결한 업무 파라미터를 바꾼 재시작은 거부되는가

## 강제 종료 조건

`#9`는 `chunkSize=10`, `hibernateBatchSize=1`로 실행했습니다. 청크를 1,000개로 늘려 sync 구간을 관측 가능한 길이로 만들기 위해서입니다. 두 값은 비식별 파라미터라 재시작 계약에 영향을 주지 않습니다.

종료 시점은 시간이 아니라 `sync_chunk_result`의 커밋된 행 수를 기준으로 정했습니다. 커밋된 청크가 실재하는 상태, 즉 **재개 지점이 의미를 갖는 상태**에서 종료해야 하기 때문입니다.

| 항목 | 값 |
|---|---|
| 종료 시각 | 2026-08-16 16:31:10 KST |
| 대상 | 애플리케이션 `java` 프로세스, `SIGKILL` |
| 종료 직전 커밋된 청크 | 278 |
| 최종 확정 청크 | 280 |

종료 직전 관측값 278과 최종 확정값 280이 다른 것은 조회와 종료 사이에 청크 2개가 더 커밋됐기 때문입니다. 대사에 쓰는 값은 280이며, 278은 종료 시점이 sync 한복판이었다는 근거입니다.

## 재개 지점

| step | job | 청크 | 첫 키 | 마지막 키 | 키 구간 길이 | 결과 합계 |
|---|---|---:|---:|---:|---:|---:|
| 29 | 6 (`#9`) | 280 | 200013 | 202812 | 2,800 | 2,800 |
| 30 | 7 (`#10`) | 8 | 202813 | 210012 | 7,200 | 7,200 |

두 구간이 겹치지 않고 빈 키도 없습니다.

`#10`은 `chunkSize`를 10에서 1000으로 **바꿔서** 실행했습니다. 재개 위치가 처리 순번이 아니라 커밋된 키에 묶여 있으므로 청크 크기가 달라져도 재개 지점은 이동하지 않습니다.

## 외부 API 재호출

`#10`에서 실행된 단계는 `sync`, `beginVerify`, `verify` 세 개입니다. `prepareCrossrefExecution`, `collect`, `beginSync`는 `#9`에서 `COMPLETED`로 확정돼 재실행되지 않았습니다. `collection_pages_fetched`는 10으로 변하지 않았습니다.

수집 결과인 `expected_count`와 `staging_upper_bound`는 `sync_execution`에 영속돼 있어 재시작이 이 값을 DB에서 복원합니다.

## 처리 결과

수집 10,000건, 청크 288개, 미해결 오류 0건으로 verify까지 완료했습니다.

| 대사 항목 | 건수 |
|---|---:|
| 예상 건수 = 스테이징 = 고유 DOI = 처리 결과 | 10,000 |
| `NO_OP` | 9,242 |
| `INDEX_ADVANCED` | 514 |
| `UPDATED` | 237 |
| `INSERTED` | 7 |
| `SUPERSEDED` | 0 |
| `CONFLICT` | 0 |
| 검증 오류 | 0 |
| 미해결 `sync_error` | 0 |

`NO_OP`이 지배적인 것은 같은 DOI가 `#6`의 10만 건 실행에 이미 반영돼 있기 때문입니다. 재수집한 실제 데이터에서 동일성 판정이 작동한 결과입니다.

## 두 경로의 대조

`sync_chunk_result`는 애플리케이션의 `ChunkAwareJpaWorkWriter`가 대상 데이터·오류와 같은 트랜잭션에서 기록합니다. `BATCH_STEP_EXECUTION`은 Spring Batch가 기록합니다. 두 기록이 일치해야 청크 트랜잭션과 체크포인트가 같은 커밋 경계에 있다고 말할 수 있습니다.

| step | 애플리케이션 청크 / 결과 합계 | Spring Batch `COMMIT_COUNT` / `WRITE_COUNT` |
|---|---|---|
| 29 | 280 / 2,800 | 280 / 2,800 |
| 30 | 8 / 7,200 | 8 / 7,200 |

## 강제 종료 후 재시작 준비

`SIGKILL`은 애플리케이션에 정리 기회를 주지 않으므로 Spring Batch 메타데이터가 `STARTED`로 남습니다. 이 상태에서 같은 식별 파라미터로 재실행하면 실행 중인 작업으로 판단해 거부합니다. 재시작 전에 죽은 실행을 `FAILED`로 확정해야 합니다.

종료 직후 실측 상태입니다.

```
job_execution 6  STATUS=STARTED  EXIT_CODE=UNKNOWN
  prepareCrossrefExecution  COMPLETED
  collect                   COMPLETED
  beginSync                 COMPLETED
  sync                      STARTED   COMMIT_COUNT=280
```

Jenkins 빌드는 `FAILURE`인데 메타데이터는 `STARTED`입니다. 이 어긋남은 운영에서 반드시 다뤄야 하는 상태이며, `JOB_EXECUTION_ID=6`으로 범위를 좁혀 `BATCH_STEP_EXECUTION`과 `BATCH_JOB_EXECUTION`을 `FAILED`로 확정한 뒤 `STARTED` 잔여 0건을 확인하고 `#10`을 실행했습니다.

이때 사용한 `NOW(6)`이 MySQL 서버 시간대인 UTC로 기록돼 애플리케이션이 KST로 남긴 `START_TIME`과 기준이 다릅니다. 두 값의 차로 단계 소요를 계산하면 음수가 나오며, 이는 수동 확정 절차의 흔적입니다. 실제 종료 시각은 2026-08-16 16:31:10 KST입니다.

## 재시작 거부

`#11`은 `maxItems`만 9999로 바꾸고 나머지를 동일하게 두고 실행했습니다.

```
java.lang.IllegalStateException: Frozen request contract changed
BATCH_STEP_FAILURE  jobExecutionId=8  step=prepareCrossrefExecution
읽음=0  저장=0  커밋=0  롤백=1
```

`maxItems`는 식별 파라미터이므로 값이 바뀌면 Spring Batch는 재시작이 아니라 새 JobInstance로 판단해 실행을 시작합니다. 거부한 것은 프레임워크가 아니라 `prepareCrossrefExecution`이 같은 `requestId`의 기존 실행을 조회해 동결 파라미터를 대조하는 애플리케이션 계약입니다.

거부 후 상태는 변하지 않았습니다.

| 확인 | 값 |
|---|---|
| `business_status` / `expected_count` / `collection_pages_fetched` | `COMPLETED` / 10,000 / 10 |
| 청크 총계 | 288 |
| `sync_execution` 행 수 | 6 (기존 5 + 이번 실행 1) |
| `job_execution 8`의 단계 | `prepareCrossrefExecution` 하나 |

`collect` 단계가 생성되지 않았으므로 이 시도는 외부 API를 호출하지 않았습니다.

## 검증 범위 밖

- 로컬 Jenkins와 로컬 데이터베이스에서 수행했습니다. 외부에서 이 빌드 기록을 직접 열람할 수는 없습니다.
- `collect` 단계 진행 중의 강제 종료는 확인하지 않았습니다. 이 기록은 sync 단계의 재시작만 다룹니다.
- 재시작 횟수는 1회입니다. 반복 종료·재개는 확인하지 않았습니다.

## 저장소 밖 보존 증빙

용량 때문에 Git 저장소 밖에 보존한 원본입니다. 경로는 `/Volumes/sd-128/open-metadata-sync/2026-08-16-restart` 기준입니다.

- `database-backups/open_metadata_after_restart.sql.gz`
- `jenkins-evidence/jenkins-crossref-builds-9-11.tar.gz`
- `jenkins-evidence/final-tests-f6621aa.tar.gz`
- `jenkins-evidence/kill-record.txt`
- `jenkins-evidence/restart-watcher.sh`
- `checksums/SHA256SUMS-restart.txt`

SHA-256, gzip, tar 검사를 모두 통과했습니다. 테스트 아카이브에는 25 suites·161 tests의 XML·HTML 리포트가 들어 있으며 실패·오류·스킵은 0건입니다.

DB 덤프는 임시 스키마 `open_metadata_restore_check`에 실제로 복원해 Flyway migration 6개, `live-restart-001`의 스테이징 10,000건, 청크 288개, 미해결 오류 0건, 두 단계의 키 구간 `200013~202812`와 `202813~210012`를 다시 확인했습니다.

종료 기준을 재현할 수 있도록 `restart-watcher.sh`를 함께 보존합니다. 종료 시점이 임의의 시간이 아니라 커밋된 청크 수를 조건으로 정해졌다는 근거입니다.

Jenkins 빌드 기록 원본은 로컬 `~/.jenkins/jobs/open-metadata-sync/jobs/crossref/builds/9`, `10`, `11`에 있습니다.

## 증빙 라벨 구분

| 실행 | SHA | 성격 |
|---|---|---|
| Crossref `#9` / `#10` / `#11` | `46e6585` | **실제 API 재시작·거부 증빙** |
| Crossref `#8` | `8b0f74b` | 빌드 실패로 미기동. 증빙 아님 |
| Crossref `#6` | `512dc73` | 최종 SHA 실제 API 대사 증빙 |
| PREFLIGHT `#19` / `#21` | `7350aa1` | 재시작 운영 증빙 (**합성 데이터**) |

`#9`~`#11`은 실제 외부 API 데이터를 대상으로 한 재시작 증빙이고, PREFLIGHT는 합성 데이터를 대상으로 한 재시작 증빙입니다. 두 기록은 검증 대상이 다르므로 서로를 대체하지 않습니다.
