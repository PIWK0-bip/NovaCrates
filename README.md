# NovaCrates 2.2.0

## New in 2.2.0
- **Crate tiers / unlock** — `unlock.requires-crate` + `requires-opens` / permission
- **Battle pass lite** — points per open, tier claims (`/crates pass`)
- **Offline open queue** — `/crates queueopen <player> <crate> [rewardId]`
- **Hologram PAPI refresh** — lines re-parsed with PlaceholderAPI every N ticks

## Commands (admin)
- `/crates queueopen <player> <crate> [rewardId]`
- `/crates unlock <player> <crate>`
- `/crates pass [track|claim [track]]`

## Build
```bash
mvn clean package
```
