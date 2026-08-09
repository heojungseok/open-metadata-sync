# 실제 Crossref 10만 건 최종 대사 기록

2026-08-09에 수행한 실제 외부 API 전체 흐름 검증의 최종 기록입니다.

## 실행 기준

| 항목 | 값 |
|---|---|
| 최종 구현 | `develop@512dc7311e68dbe083fa8746d73571f83eb9a5e6` |
| Jenkins | crossref `#6`, request `jenkins-crossref-100k-20260809-02` |
| 소요 시간 | 7분 50초 |
| 로컬 검증 | `./gradlew clean test --rerun-tasks` — 128 tests, 실패·오류·스킵 0건 |
| 독립 리뷰 | 데이터·코드 리뷰 2건 모두 `PASS` (빈 cursor 정렬과 파생 page 상한 수정 반영 후) |

## 처리 결과

수집 100,000건, sync 100 커밋, 롤백 0건으로 verify까지 완료했습니다.

| 대사 항목 | 건수 |
|---|---:|
| 예상 건수 = 스테이징 = 고유 DOI = 처리 결과 | 100,000 |
| `INSERTED` | 2,385 |
| `NO_OP` | 97,572 |
| `INDEX_ADVANCED` | 39 |
| `UPDATED` | 4 |
| `CONFLICT` | 0 |
| 검증 오류 | 0 |
| 미해결 `sync_error` | 0 |

100개 청크 결과의 합계가 100,000건과 정확히 일치합니다.

## 저장소 밖 보존 증빙

용량 때문에 Git 저장소 밖에 보존한 원본입니다. 경로는 `/Volumes/sd-128/open-metadata-sync/2026-08-09-final` 기준입니다.

- `database-backups/open_metadata_after_final_crossref_v2.sql.gz`
- `jenkins-evidence/jenkins-crossref-build-6.tar.gz`
- `jenkins-evidence/final-tests-512dc73.tar.gz`
- `checksums/SHA256SUMS-after-final-v2.txt`

SHA-256, gzip, tar 검사를 모두 통과했습니다. 테스트 아카이브에는 128개 테스트의 XML·HTML 리포트가 들어 있습니다. DB 덤프는 임시 스키마에 실제로 복원해 Flyway migration 5개, 완료 실행과 스테이징 각 100,000건, 100개 청크와 처리 결과 합계 100,000건, 오류 0건을 다시 확인했습니다.

Jenkins 빌드 기록 원본은 로컬 `~/.jenkins/jobs/open-metadata-sync/jobs/crossref/builds/6`에 있습니다.

## 증빙 라벨 구분

같은 시기의 다른 실행과 섞이지 않도록 범위를 구분합니다.

| 실행 | SHA | 성격 |
|---|---|---|
| Crossref `#6` | `512dc73` | **최종 SHA 실제 API 증빙** |
| Crossref `#5` | `180e050` | `#6`이 대체한 잠정 이력 |
| Benchmark `#14` / `#15` | `8d6dd852` | Milestone 2 100만 건 성능 증빙 |
| PREFLIGHT `#19` / `#21` | `7350aa1` | 재시작 운영 증빙 |

Crossref `#6` 외의 실행은 최종 SHA 실제 API 증빙이 아닙니다.

## 보존 상태

benchmark·actual 스키마, Docker volume, 과거 Jenkins 빌드, branch와 worktree는 모두 그대로 두었습니다. 정리는 별도 승인 대상입니다.
