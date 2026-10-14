# The C++ rewrite lives in another repository

Assessment, design, milestones, verification strategy and the decision log for the rewrite of OpenPnP in C++20
are **not** in this checkout. They are in the sibling repository:

    ../vpnp            docs/design/rewrite/     the assessment and design
                       DECISIONS.md             ADR log + decision-pack calendar
                       AGENTS.md, STATUS.md     how that project is worked

They moved on 2026-10-13, because they describe a different application. Only a pointer belongs here.

## What stays here, and why it matters to that project

| path | role for vpnp |
|---|---|
| `tests_2026-10-01/` | `pn52`'s real 4,975-line `machine.xml`, the bottom-vision corpus, and the run logs. The reference evidence |
| `docs/design/parallel-bottom-vision/CAPTURE-COST.md` | the measurement showing a rewrite buys ~0 ms per job, and that the grabber is the only native work that pays |
| `docs/design/parallel-bottom-vision/MACHINE-TEST.md` | how to run something on `pn52` |
| `src/test/java/.../vision/FrameReplayTest.java` | the corpus-replay pattern and the 1e-6 px tolerance it generalises |
| this repository as a whole | **the oracle.** vpnp builds this app from a tag and diffs its behaviour against it |

## Frozen revision

`oracle-2026-10-13` = `7fda12d0ad1e035987ff8e652182c637aa0341ad` on `parallel-bottom-vision`.
**Build the reference app from that tag, never from a working tree.** Ordinary development on
`parallel-bottom-vision` is unconstrained - vpnp must not care how far it moves.
