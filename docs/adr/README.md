# Architecture decisions

One file per decision, numbered in order. A decision is recorded when it is hard to reverse or would surprise someone
reading the code: which library or protocol implementation to use, what a module may depend on, what a data format
promises. Superseded decisions stay, marked as such.

| # | Decision | Status | Date |
| --- | --- | --- | --- |
| [0001](0001-cast-sender.md) | Google Cast: an in-house minimal CASTV2 sender instead of a library | Accepted | 2026-09-16 |
| [0002](0002-versioned-data-files-and-device-secrets.md) | Versioned data files, and device secrets that need no login | Accepted | 2026-09-29 |
| [0003](0003-workflow-chains.md) | Workflow chains: schema version 2, and response values in the editor | Accepted | 2026-09-29 |
| [0004](0004-device-control-from-sources.md) | Device control from content sources: service APIs stay in the source, device steps go through `DeviceCommands` | Accepted | 2026-09-29 |

New decisions copy the header of 0001 (date, status, context) and take the next number.
