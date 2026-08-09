# Milestone 1 벤치마크 증빙

Milestone 1 릴리스 후보의 Task 9 데이터 처리 계층 벤치마크 결과입니다. 최종 리뷰를 마친 코드는 `develop@358b6cec5c7f003c717e85237f0e8d418c784409`입니다. Java 21과 MySQL 8.4.10에서 generator `v1`, seed `20260809`, chunk size `1000`, contract hash `660432b99f6ec7e837df1930fb0d8e7999c65d1f0e6fee8947b72d6389b60dc8` 조건으로 실행했습니다.

| 건수 | 시나리오 | 처리 결과 | 대상 DML | 쿼리 / prepared / batch | Heap plateau | 재시작 | preload / sync / verify |
|---:|---|---|---|---|---|---|---|
| 100,000 | initial | 100,000건 INSERT | 100,000 / 0 | 302 / 502 / 200 | PASS, 99 samples | PASS | 51,132 / 22,912 / 2,187 ms |
| 100,000 | no-op | 100,000건 no-op | 0 / 0 | 302 / 402 / 100 | PASS, 99 samples | PASS | 24,342 / 4,283 / 2,041 ms |
| 1,000,000 | initial | 1,000,000건 INSERT | 1,000,000 / 0 | 3,001 / 5,001 / 2,000 | PASS, 1,000 samples | 주입하지 않음 | 241,934 / 235,889 / 54,397 ms |
| 1,000,000 | no-op | 1,000,000건 no-op | 0 / 0 | 3,001 / 4,001 / 1,000 | PASS, 1,000 samples | 주입하지 않음 | 256,038 / 69,438 / 52,884 ms |

네 실행 모두 스테이징, 대상, 고유 DOI 건수가 같고 체크섬도 일치한 상태로 완료됐습니다. 10만 건 initial과 no-op은 `358b6ce`에서 request ID `m1-100k-initial-358b6ce`, `m1-100k-noop-358b6ce`로 실행했습니다. 100만 건 no-op도 같은 SHA에서 request ID `m1-1m-noop-358b6ce`로 실행했습니다.

## 100만 건 파일의 `Preflight gate | FAIL` 표기

100만 건 Markdown에는 `Preflight gate | FAIL`이 찍혀 있습니다. 이 항목을 만드는 검사가 10만 건만 대상으로 하도록 의도적으로 제한돼 있기 때문입니다. 100만 건 실행에서 이 값은 **해당 없음**이지 실행 실패가 아닙니다.

두 번의 100만 건 실행 모두 그 전에 필수 선행 조건인 10만 건 initial·no-op gate를 통과했습니다. 각자의 무결성 검사도 자체적으로 마쳤습니다.

## 100만 건 initial의 파일 이력

100만 건 initial은 `1e7d6d9236ab5ba75d06f142c2266b2192e7873b`에서 request ID `m1-1m-initial-1e7d6d9`로 실행했습니다. 이후 `358b6ce`까지 들어간 제품 코드 변경은 증빙 파일 이름 분리와 원자적 교체뿐입니다.

그 뒤 clean build 과정에서 `build/benchmark-evidence` 아래에 있던 원본 파일이 지워졌습니다. 그래서 확보해둔 실행 결과를 리뷰를 마친 제품 코드의 증빙 writer로 다시 기록했습니다. 이렇게 재생성한 JSON과 Markdown은 최초 실행 직후 확보한 해시와 바이트 단위로 같습니다.

## 파일 형식에 관한 참고

이 디렉터리의 `benchmark-*.json`과 `benchmark-*.md`는 애플리케이션이 생성한 산출물이며 Jenkins Pipeline과 테스트가 문자열을 그대로 대조합니다. 아래 SHA-256도 그 파일들을 대상으로 기록한 값이므로 내용을 손대지 않습니다.

## SHA-256

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
