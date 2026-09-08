# Milestone 1 벤치마크 증빙

데이터 처리 계층의 합성 데이터 10만·100만 건 실행 기록입니다. 네 실행 모두 스테이징·대상·고유 DOI 건수와 체크섬이 일치했습니다. 아래 표의 설명은 한국어로 정리하고 자동 생성된 Markdown·JSON 원본은 그대로 보존합니다.

> **원본의 `Preflight gate | FAIL` 읽는 법:** 100만 건 원본에서 이 값은 실행 실패가 아니라 **해당 없음**을 뜻합니다. 당시 사전 자격 검사가 10만 건만 대상으로 설계됐기 때문입니다. 두 100만 건 실행은 앞서 필수 10만 건 `initial`·`no-op` 검사를 통과했고 자체 데이터 대조도 완료했습니다. 100만 건에는 재시작 실패를 주입하지 않았습니다.

## 실행 조건

- 기준: Milestone 1 릴리스 후보의 Task 9, 리뷰 완료 소스 `develop@358b6cec5c7f003c717e85237f0e8d418c784409`
- 환경: Java 21, MySQL 8.4.10
- 데이터 생성기: `v1`, 난수 시드 `20260809`, 청크 크기 `1000`
- 결과 파일 형식: `schemaVersion: v1` — 현재 생성기의 v2 결과와 구분하는 과거 실행 기록
- 실행 계약 해시: `660432b99f6ec7e837df1930fb0d8e7999c65d1f0e6fee8947b72d6389b60dc8`

## 처리 결과와 단계별 시간

실행 이름은 당시 생성된 Markdown 원본으로 연결됩니다. 사전 적재 시간은 동기화 시간에 포함하지 않습니다.

| 실행 | 처리 결과 | 사전 적재(ms) | 동기화(ms) | 검증(ms) |
|---|---|---:|---:|---:|
| [10만 initial](benchmark-100000-initial.md) | 100,000건 INSERT | 51,132 | 22,912 | 2,187 |
| [10만 no-op](benchmark-100000-no-op.md) | 100,000건 변경 없음 (`no-op`) | 24,342 | 4,283 | 2,041 |
| [100만 initial](benchmark-1000000-initial.md) | 1,000,000건 INSERT | 241,934 | 235,889 | 54,397 |
| [100만 no-op](benchmark-1000000-no-op.md) | 1,000,000건 변경 없음 (`no-op`) | 256,038 | 69,438 | 52,884 |

## DB 작업·메모리·재시작

쿼리·준비·배치는 원본의 `Queries`·`Prepared statements`·`JDBC batches` 횟수입니다. 메모리 안정화는 당시 `Heap plateau` 판정과 표본 수이며 전체 메모리 안전성을 보장하는 값은 아닙니다.

| 실행 | INSERT / UPDATE | 쿼리 / 준비 / 배치 | 메모리 안정화 | 재시작 | JSON 원본 |
|---|---:|---|---|---|---|
| 10만 initial | 100,000 / 0 | 302 / 502 / 200 | PASS, 99개 표본 | PASS | [JSON](benchmark-100000-initial.json) |
| 10만 no-op | 0 / 0 | 302 / 402 / 100 | PASS, 99개 표본 | PASS | [JSON](benchmark-100000-no-op.json) |
| 100만 initial | 1,000,000 / 0 | 3,001 / 5,001 / 2,000 | PASS, 1,000개 표본 | 주입하지 않음 | [JSON](benchmark-1000000-initial.json) |
| 100만 no-op | 0 / 0 | 3,001 / 4,001 / 1,000 | PASS, 1,000개 표본 | 주입하지 않음 | [JSON](benchmark-1000000-no-op.json) |

10만 건 `initial`·`no-op`과 100만 건 `no-op`은 `358b6ce`에서 실행했습니다. 요청 ID는 각각 `m1-100k-initial-358b6ce`, `m1-100k-noop-358b6ce`, `m1-1m-noop-358b6ce`입니다. 100만 건 `initial`의 별도 실행 이력은 아래에 남깁니다.

## 100만 건 initial의 파일 이력

100만 건 initial은 `1e7d6d9236ab5ba75d06f142c2266b2192e7873b`에서 request ID `m1-1m-initial-1e7d6d9`로 실행했습니다. 이후 `358b6ce`까지 들어간 제품 코드 변경은 증빙 파일 이름 분리와 원자적 교체뿐입니다.

그 뒤 clean build 과정에서 `build/benchmark-evidence` 아래에 있던 원본 파일이 지워졌습니다. 그래서 확보해둔 실행 결과를 리뷰를 마친 제품 코드의 증빙 writer로 다시 기록했습니다. 이렇게 재생성한 JSON과 Markdown은 최초 실행 직후 확보한 해시와 바이트 단위로 같습니다.

## 파일 형식에 관한 참고

이 디렉터리의 `benchmark-*.json`과 `benchmark-*.md`는 애플리케이션이 생성한 산출물이며 Jenkins Pipeline과 테스트가 문자열을 그대로 대조합니다. 아래 SHA-256도 그 파일들을 대상으로 기록한 값이므로 내용을 손대지 않습니다.

## SHA-256

파일 내용의 동일성을 확인하는 해시입니다. 원본을 내려받아 보존할 때 대조합니다.

<details>
<summary>원본 8개 파일의 SHA-256</summary>

```text
6e3ee1efeee313e6e8f067b341b564d39cc062569f703049dfde33f8cbf30e1f  benchmark-100000-initial.json
4e637cd68957c865113d6cb1f571d7100f41024c0dc76eec889ce09ce61e9112  benchmark-100000-initial.md
1d93dfee0884447377e982c71c5ba4d8cd6b746a7581de6923c8e9d6e73e4180  benchmark-100000-no-op.json
cc6165f155091194becff5d34346d7818b7f3826cfe972a8022a25b1018eb8ea  benchmark-100000-no-op.md
b0cab8322bd98510eaeae93fac353906c1856db041e54c9e8d4a220d2782a59a  benchmark-1000000-initial.json
696e39cb86163b8edc156e8ea5f1b75908f8e27c154c2fe149ccb51be58b6750  benchmark-1000000-initial.md
a64a27f8a287fb874de10595653dc81c8f7b3d4d9dda89e197e252e9e3d273a1  benchmark-1000000-no-op.json
50c6ff89bcade13c478f305a7110420461fa45c923462dfcd9ad0ff424a7d59b  benchmark-1000000-no-op.md
```

</details>

[프로젝트 README](../../README.md#2-주요-검증-결과) · [증빙 안내](../../docs/evidence/README.md)
