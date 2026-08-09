# 증빙 책임 범위

실행 결과를 뒷받침하는 증빙은 세 주체로 나뉩니다. 무엇을 근거로 완료를 판단하는지 헷갈리지 않도록 각자의 역할을 분리했습니다.

| 증빙 | 역할 | 한계 |
|---|---|---|
| Spring Batch 메타데이터, `sync_chunk_result` | 실행과 재시작의 영속 기준 | — |
| DB 대사 결과 | 업무 완료와 데이터 정합성 판단 | — |
| 구조화 로그 | 진행과 오류를 관찰하는 일시적 신호 | 어떤 로그도 체크포인트가 아님 |
| 결과 파일, Jenkins 상태·산출물 | 실행 결과의 기계 판독 가능한 요약 | Batch 메타데이터와 DB 대사를 대신하지 못함 |

`commitCount`, `readCount`, `writeCount`는 진행 상황을 보는 데 도움이 되지만 재시작 위치를 정하는 근거로는 쓰지 않습니다.

## 결과 판정

종료 코드와 Jenkins 상태의 대응은 [루트 README](../../README.md#프로세스-결과)를 따릅니다.

벤치마크 증빙에서 `Processing result PASS`는 처리 결과가 완결되고 내부적으로 대사가 맞는다는 뜻입니다. 재시작, heap retention, persistence는 더 큰 규모의 합성 실행을 감당할 수 있는지 보는 **별개의 자격 항목**입니다. 자격을 충족하지 못하면 Jenkins는 `UNSTABLE`이 됩니다. 이미 성공한 처리 결과를 실패로 바꿔 쓰지는 않습니다.

## 산출물 취급

- 결과 파일에는 결과 코드와 outcome, job 이름, `requestId`, mode, 실행 ID만 담습니다.
- DB 인증 정보는 마스킹된 환경 변수로만 전달하며 Job Parameter나 산출물에 넣지 않습니다.
- Jenkins는 실행 직전에 현재 요청이 사용할 결과 파일 하나만 지웁니다. 새 파일은 코드·`requestId`·job·mode가 현재 빌드와 일치할 때만 받아들입니다.
- 벤치마크 증빙은 `benchmark-evidence` 아래에 고정합니다. 성공이나 불안정 실행은 현재 JSON/Markdown 쌍만, `MAIN`은 여기에 10만 `initial`·`no-op` 선행 쌍까지 함께 보존합니다. 이미 완료된 실행을 건너뛴 경우에는 해당 결과 파일만 남깁니다.

벤치마크 증빙 schema `v2`는 retained heap의 초반·후반 floor와 증가량, 허용치, 각 자격 판정을 따로 기록합니다. Jenkins 벤치마크 JVM은 `-Xms128m -Xmx256m`으로 고정해 반복 실행이 같은 메모리 조건을 공유하도록 했습니다.

heap retention은 GC 상태를 진단하는 지표가 아닙니다. 외부 API 수집 과정의 메모리 사용을 예측하지도 않습니다.

## 정리 정책

애플리케이션 실행과 두 Jenkins Pipeline 모두 DB, schema, volume, branch를 자동으로 정리하지 않습니다. 보존 기간과 정리 작업은 실행·검증과 분리해 별도 승인 대상으로 둡니다.
