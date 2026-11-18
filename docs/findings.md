# POC findings

Fill this in during the POC. Keep it factual: numbers, file names, decisions.

## Setup

- **Machine / OS:**
- **Java version:**
- **Maven version:**
- **MSBuild version:**
- **Visual Studio / toolset:**
- **Windows SDK:**
- **llvm-platform version** (LLVM version inside it):
- **Target solution** (name, number of projects, number of `.cpp` files):
- **Configuration / platform scanned:**

## Results against the success criteria

| Criterion | Result | Pass / Fail / Pending review | Notes |
| --- | --- | --- | --- |
| libclang via JavaCPP runs | | | |
| ≥ 90% of `.cpp` files parse without fatal errors | x of y (z%) | | |
| ≥ 18 of 20 sampled calls correct | | | |
| 10 of 10 sampled classes correct | | | |
| Output matches the tinypay structure | | | |
| SQLite / Winsock hits all real | | | |
| Total scan time / time per file | | (no target) | |
| LLM draws a correct sequence diagram from one flow file | | | |
| Facts libclang could not provide (list below) | | | |

## Counts

- **Modules:**
- **Components** (classes and structs):
- **Methods:**
- **Calls**, broken down by EXACT, VIRTUAL and UNRESOLVED:
- **Entry points:**
- **SQLite hits:**
- **Winsock hits:**

## Facts libclang could not provide

For each one, give an example (file and line), what was missing, and whether it matters for the JSON or the diagrams. This list decides whether a LibTooling extractor is ever needed.

## Fallbacks used

For each one: what failed, what was done instead, and the effect on accuracy.

## Files that failed to parse

The top causes, each with an example file and its first error.

## Surprises and lessons

## Recommendation

One of: Go / Go with changes / Stop or rethink. Give the reason in two or three sentences.

## Open questions for the full plan
