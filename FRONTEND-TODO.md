# nospoon Android frontend — deferred follow-ups

Items intentionally not done in the 2026-05-25 frontend overhaul. Pick up
when there's appetite.

## Quick wins

- **Export config to file** — mirror of "Import config from file" (SAF
  `ActionCreateDocument` writes `nospoon.conf`). Useful for backing up a
  configuration before swapping phones or for sharing a profile with
  another user. Plumbing: a button in the editor (or long-press card →
  Export) → `contentResolver.openOutputStream(uri)` writes
  `VpnConfig.toJson().toString(2)`. Mirror of the Import code path; the
  CIDR/seed/server fields already round-trip.

- **Long-press card → action menu** — context menu on each row in the
  config list with Duplicate / Export / Delete. Today long-press opens
  the editor (same as the explicit edit icon, redundant). Replace with a
  `PopupMenu` anchored to the card. Pairs naturally with Export.

## Lower priority

- **DiffUtil** — swap `VpnConfigAdapter` (`RecyclerView.Adapter`
  + `notifyDataSetChanged()`) for `ListAdapter<VpnConfig, …>` with a
  `DiffUtil.ItemCallback`. Today the full list rebinds on every
  `ConnectionStateRepository` tick (cheap because few items), but it
  costs free animations and retained focus. ~30 lines.

- **Connection log screen** — new Activity showing recent retry attempts,
  DHT bootstrap timing, last error. Power-user diag. Requires plumbing
  a ring buffer in the service + a new layout. Defer until users ask.

## Not deferred — out of scope at audit time

- "Bigger accessibility colors" (`status_error` red below 4.5:1 on dark
  `surface`) — left for design pass. Easy change if it bites:
  `#F44336` → `#FF6B6B`. See `res/values/colors.xml`.

- "Connection state surfacing" (virtual IP / uptime / bytes /
  retry count visible in UI) — needs the service to track and expose
  these fields. Service is otherwise out of scope per the original
  audit. Hook point exists: extend `ConnectionState.Connected` /
  `Reconnecting` with carried fields, update at the existing `setStatus`
  callsites in `NospoonVpnService.kt`. ~40 lines, low risk.

## Reference

- Full original audit (still untracked): `android/FRONTEND-AUDIT.md`
- Implementation history: `nospoon-cpp` branch git log, search
  `feat(android)` / `fix(android)` / `chore(android)` from 2026-05-25.
