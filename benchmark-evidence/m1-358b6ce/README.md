# Milestone 1 benchmark evidence

Task 9 data-plane benchmark evidence for the Milestone 1 release candidate. The final reviewed code is `develop@358b6cec5c7f003c717e85237f0e8d418c784409`; Java 21, MySQL 8.4.10, generator `v1`, seed `20260809`, chunk size `1000`, and contract hash `660432b99f6ec7e837df1930fb0d8e7999c65d1f0e6fee8947b72d6389b60dc8` were used.

| Rows | Scenario | Outcome | Target DML | Queries / prepared / batches | Heap plateau | Restart | Preload / sync / verify |
|---:|---|---|---|---|---|---|---|
| 100,000 | initial | 100,000 inserted | 100,000 / 0 | 302 / 502 / 200 | PASS, 99 samples | PASS | 51,132 / 22,912 / 2,187 ms |
| 100,000 | no-op | 100,000 no-op | 0 / 0 | 302 / 402 / 100 | PASS, 99 samples | PASS | 24,342 / 4,283 / 2,041 ms |
| 1,000,000 | initial | 1,000,000 inserted | 1,000,000 / 0 | 3,001 / 5,001 / 2,000 | PASS, 1,000 samples | not injected | 241,934 / 235,889 / 54,397 ms |
| 1,000,000 | no-op | 1,000,000 no-op | 0 / 0 | 3,001 / 4,001 / 1,000 | PASS, 1,000 samples | not injected | 256,038 / 69,438 / 52,884 ms |

All four runs completed with equal staging/target/distinct-DOI counts and matching checksums. The 100,000-row initial and no-op runs were executed on `358b6ce` with request IDs `m1-100k-initial-358b6ce` and `m1-100k-noop-358b6ce`. The 1,000,000-row no-op run was executed on `358b6ce` with request ID `m1-1m-noop-358b6ce`.

The 1,000,000-row Markdown files display `Preflight gate | FAIL` because that renderer calls the deliberately 100,000-row-only `requirePreflight()` check. For the 1,000,000-row runs this field is **not applicable**, not an execution failure: the mandatory matching 100,000-row initial/no-op gate passed before either 1,000,000-row launch, and both 1,000,000-row jobs completed their own integrity checks.

The 1,000,000-row initial run was executed on `1e7d6d9236ab5ba75d06f142c2266b2192e7873b` with request ID `m1-1m-initial-1e7d6d9`. The only later production change through `358b6ce` was evidence filename isolation and atomic replacement. A subsequent clean build removed the original file under `build/benchmark-evidence`; the captured result was therefore written again through the reviewed production evidence writer. The regenerated JSON and Markdown are byte-for-byte identical to the hashes captured immediately after the original run.

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
