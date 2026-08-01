# NovaCrates — funkcje i komendy

## Gracz
| Komenda | Opis |
|---------|------|
| `/crates help` | Lista komend |
| `/crates list` | Skrzynki |
| `/crates open <id> [x]` | Otwórz (x = multi) |
| `/crates preview <id>` | Podgląd chest |
| `/crates keys` | Klucze |
| `/crates shop` | Sklep |
| `/crates progress` | Postęp / unlock / pity |
| `/crates pass` | Battle pass GUI |
| `/crates gift <gracz> <key> [ile]` | Gift klucza |
| `/crates top [crate]` | Ranking otwarć |

## Admin
| Komenda | Opis |
|---------|------|
| `/crates editor <id>` | Edytor nagród |
| `/crates givekey` | Daj klucz |
| `/crates reload [full]` | Przeładuj |
| `/crates audit [gracz]` | Historia dropów |
| `/crates cleanup-lore` | Czyść lore |
| `/crates exportcsv` | CSV dropów |
| `/crates exportall` | ZIP backup config |
| `/crates doctor` | Diagnostyka |
| `/crates queueopen` | Offline queue |

## Animacje
`CHEST` (domyślna), `CSGO`, `WHEEL`, `SLOTS`, `INSTANT`

## Config kluczowe
```yaml
settings:
  default-animation: CHEST
  win-screen: true
  win-screen-ticks: 40
  input-mode: chat
  preview-before-open: true
  pity-bossbar: true
  pity-actionbar: true

guaranteed-sequence:
  vote:
    every: 15
    reward-id: rare_1
```

## PAPI
- `%novacrates_keys_<id>%`
- `%novacrates_pity_<crate>%`
- `%novacrates_opens%` / `%novacrates_opens_<crate>%`
- `%novacrates_opens_player_<crate>%`
- `%novacrates_pass_<track>%`
- `%novacrates_unlocked_<crate>%`


## 2.5.2
- guarantee-once-per-multi
- PlayerPoints cost type: POINTS
- DecentHolograms backend (optional)
- /crates history
- Preview rarity legend glass


## Animacja HOLOGRAM / ROULETTE
Itemy nad skrzynką (ItemDisplay): scroll z prawej do lewej, środek największy, boki coraz mniejsze.
```yaml
animation: HOLOGRAM   # lub ROULETTE / DISPLAY
settings:
  roulette-visible: 7
  roulette-spacing: 0.55
```

## 2.6.1 Improvements
- Fixed AnimationController CHEST branch (tasks → active) + resolveCrateLocation
- Folia-safe timers via SchedulerUtil for GUI/hologram animations
- Strict reward commands: block op/ban/kick/stop/reload/execute; allowed-command-prefixes; max length
- CrateMultiOpenEvent + OfflineQueueGrantEvent; delayed offline queue on join
- /crates doctor diagnostics; validation on crate reload
- /crates top LeaderboardGUI; /crates history HistoryGUI with rarity filters
- Win screen: rarity, chance %, pity
- Hologram roulette ease-out cubic + max concurrent animations
- CSGO animation end bounce
- Discord webhook embeds
- Player opens + pass points cache
- Hologram PAPI refresh skips empty ranges
- Multi-open confirm total cost + key balance
- Public API: giveKeys, getHistory, registerRewardFilter

## 2.6.2
- MySQL-compatible upserts for all player data flushes
- isAnimating includes hologram roulette; hologram entities cleaned on quit
- Real openCrateAsync + CrateMultiOpenEvent with reward list
- CoinsEngine (COINS) cost type
- pity-once-per-multi; PlayerDataRepository inject for win-screen pity
- /crates help; /crates doctor fix (orphan blocks)
- Reward command deny-list + logging; RewardCodec; EditorMeta tests
- PAPI %novacrates_top_<rank>% / %novacrates_top_<rank>_<crate>%
- History batch insert; WorldGuard flag-aware region block

## 2.6.3
- Async open timeout (async-open-timeout-ticks) + cancel on releaseOpening
- Multi-open history via addHistoryBatch
- Offline gift queue (gift_queue) + process on join
- Physical key HMAC signature; keys-mode virtual-only/physical-only
- reward-commands-console-only + allowed-reward-commands exact list
- Per-player open rate limit; cost-confirm-threshold
- Granular admin permissions; full progressive tab-complete
- Vote plugin soft-hook; PAPI top_name_ / top_opens_
- Flush metrics; export schema_version; editor backup before save
- Config-driven chest animation sounds

## 2.6.4
- Package/author: com.skritped / Skritped (was kodari)
- FancyHolograms integration (settings.hologram-backend: auto|fancy|decent|armorstand)
- Soft-depend FancyHolograms

## Display name & holograms
- `/nv changename <crateId> <Display Name...>` — GUI + key + hologram title (spaces/caps ok)
  Example: `/nv changename super_rare Super Rare`
- FancyHolograms/DecentHolograms edits are detected on hologram refresh and saved to `crates.yml`
  under `hologram.lines` for that crate id — all blocks of the same crate update after restart too

## HOLOGRAM animation polish
- Intro: chest rises from below → spins ~2s → explodes → roulette
- Center slot **glowing**
- Camera lag (`roulette-camera-lag`, default 0.12) — soft follow behind head turns
- Slower scroll (`animation-duration-ticks` default 110)

## High concurrency (≤100 openers)
- `max-concurrent-animations: 32` — beyond that, opens still grant rewards **instantly** (no entity spam)
- Soft load (≥12): skip intro, 5-item strip, less particles/discord spam
- Hard visual (≥24): 3-item strip, shorter duration, 2-tick frames
- Player data remains dirty-flag + async flush (no per-open disk write)
