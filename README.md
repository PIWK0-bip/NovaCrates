# NovaCrates

**Premium crate system for Paper/Purpur** — physical & virtual keys, CS-style hologram roulette, full in-game editor, pity, battle pass, shop, and high-concurrency safety for busy servers.

> Version **2.6.4** · Paper **1.20.6+** · Java **17**

---

## Features

### Crate system
- Unlimited crate types with weighted rewards
- Physical keys, virtual keys, or **both**
- Place crates in the world (`/nv setblock`) with per-block holograms
- Preview GUI before opening
- Multi-open (`/nv open <crate> <amount>`)
- Per-crate cooldowns, daily limits, permission gates
- Guaranteed sequences (e.g. every 15th open)
- Pity system with bossbar / actionbar
- Milestone rewards & auto-unlock crates
- Offline open queue (process on join)

### Animations
| Style | Description |
|--------|-------------|
| **HOLOGRAM / ROULETTE** | Full-screen face-camera strip (CS-like), intro shake + explode, glowing center item |
| **CHEST** | Classic GUI chest animation |
| **CSGO / WHEEL / SLOTS** | Extra GUI styles |
| **INSTANT / NONE** | Skip animation, grant immediately |

**Load-aware scaling (≤100 concurrent openers):**
- Soft load → lighter strip, no intro
- Hard load → minimal visuals
- Over capacity → **instant reward** (no entity spam, TPS-safe)

### Holograms
- **FancyHolograms** / DecentHolograms / ArmorStand fallback
- `/fholo` edits are **respected** (plugin does not overwrite your styling)
- Optional sync of world hologram text → `crates.yml`
- PlaceholderAPI on hologram lines

### In-game editor
- Full reward editor GUI (weight, chance, amount, rarity, broadcast, commands)
- Anvil / chat input for numbers (reliable save → reopen GUI)
- Command editor per reward
- `/nv changename` — GUI + key display name only (spaces & colors supported; holograms untouched)

### Economy & shop
- Vault / PlayerPoints / CoinsEngine soft depend
- Built-in key shop
- Gift keys to other players

### Progression
- Battle pass tracks
- Progress / unlock GUI
- Leaderboards (`/nv top`)
- Drop history & audit tools

### Admin toolkit
- `/nv doctor` — diagnostics
- `/nv exportcsv` / `/nv exportall` — data & config backup
- `/nv audit` — player drop history
- HMAC-signed keys (anti-dupe option)
- Blocked worlds / WorldGuard regions
- Configurable plugin prefix & messages (PL/EN)

### Performance
- Async dirty-flush player data (SQLite)
- Material cache, throttled hologram refresh
- Configurable concurrent animation cap
- Global + per-player open rate limits

---

## Commands

**Aliases:** `/crates`, `/crate`, `/nv`, `/novacrates`

### Players
| Command | Description |
|---------|-------------|
| `/nv help` | Command list |
| `/nv list` | Available crates |
| `/nv open <id> [x]` | Open crate (multi) |
| `/nv preview <id>` | Preview rewards |
| `/nv keys` | Your keys |
| `/nv shop` | Key shop |
| `/nv progress` | Pity / unlocks |
| `/nv pass` | Battle pass |
| `/nv gift <player> <key> [amount]` | Gift a key |
| `/nv top [crate]` | Open leaderboard |

### Admin
| Command | Description |
|---------|-------------|
| `/nv editor <id>` | Reward editor |
| `/nv givekey <player> <key> [amount]` | Give keys |
| `/nv setblock <crate>` | Bind looking block as crate |
| `/nv changename <crate> <name...>` | GUI + key display name |
| `/nv reload [full]` | Reload configs |
| `/nv audit [player]` | Drop history |
| `/nv doctor` | Diagnostics |
| `/nv exportcsv` / `/nv exportall` | Export data |
| `/nv cleanup-lore` | Clean key lore |

---

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `novacrates.open` | true | Open crates |
| `novacrates.shop` | true | Use shop |
| `novacrates.admin` | op | Full admin |
| `novacrates.admin.editor` | op | Editor |
| `novacrates.admin.givekey` | op | Give keys |
| `novacrates.admin.reload` | op | Reload |
| `novacrates.admin.doctor` | op | Doctor |
| `novacrates.admin.settings` | op | Settings |

---

## PlaceholderAPI
%novacrates_keys_<keyId>%
%novacrates_pity_<crateId>%
%novacrates_opens%
%novacrates_opens_<crateId>%
%novacrates_opens_player_<crateId>%
%novacrates_pass_<track>%
%novacrates_unlocked_<crateId>%

---

## Dependencies

**Required**
- Paper / Purpur **1.20.6+** (API 1.20)

**Optional (soft)**
- PlaceholderAPI
- Vault / PlayerPoints / CoinsEngine
- WorldGuard
- **FancyHolograms** (recommended) or DecentHolograms
- FancyDialogs

---

## Installation

1. Drop `NovaCrates-*.jar` into `plugins/`
2. Restart the server
3. Edit `plugins/NovaCrates/config.yml` & `crates.yml`
4. `/nv reload` or restart
5. `/nv setblock <crateId>` while looking at a block
6. Optional: style holograms with `/fholo` — NovaCrates will **not** overwrite them

---

## Configuration highlights

```yaml
settings:
  prefix: "&7[&bNovaCrates&7]&r "
  default-animation: HOLOGRAM
  win-screen: false
  max-concurrent-animations: 32
  scale-soft-cap: 12
  scale-hard-visual-cap: 24
  hologram-respect-external: true   # keep /fholo edits
  hologram-sync-from-world: true    # save Fancy text → crates.yml
  keys-mode: both                   # physical | virtual | both
  input-mode: chat                  # chat | anvil

Support & notes

Built for high traffic — animations shed load automatically; rewards always grant.
Hologram styling belongs to FancyHolograms; NovaCrates only creates missing holograms and can sync text into config.
/nv changename changes GUI + key name only, never holograms.


Author: Skritped
License: ARR

```
CS-style crate system with hologram roulette, full editor, pity, battle pass & TPS-safe scaling for 100+ openers.
