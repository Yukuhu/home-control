# ADR: Workflow chains — schema version 2, and response values in the editor

**Date:** 2026-09-29
**Status:** Accepted
**Context:** Workflow chains, build step 1.
**Spec:** `docs/superpowers/specs/2026-09-29-workflow-chains-design.md`.

## Decision

- A workflow definition is **schema version 2**: an ordered list of up to eight GET calls, each shared or per entry,
  with an entry source naming one shared call. Version 1 definitions are read and converted in memory, their one
  fetch becoming the shared call `main`, and written as version 2 on the next Save. Version 2 gains optional fields
  (date values, filters) in later steps without a new version.
- A call may use only values of calls above it. When a call runs (refresh, Play, both) is derived from what uses its
  values, not stored.
- In a header value, `{name}` is a placeholder and `{{`/`}}` are literal braces. Migration doubles the braces of
  version 1 header values.
- The editor's Try (build step 3) shows a logged-in household member real response values, which relaxes the rule
  that raw responses never reach the browser. Test, the Dashboard, logs and errors keep masking.

## Consequences

- Rolling back to a release before version 2 cannot read workflows saved since; they show as unreadable in Setup
  until removed or the newer release is restored.
- A variable's value can fill a call URL's path and query values but never its host, so no response can redirect a
  credential to another server.
- Migration makes a stored definition longer: braces are doubled, and calls, scope and field objects are added. A
  version 1 workflow close to the 16,384-character limit still reads, but it may fail to save again until it is
  shortened.
