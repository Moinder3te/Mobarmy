# Mobarmy 🏹⚔️

> Team-basiertes Multiplayer-Minigame für Minecraft Fabric Server (1.21.11) mit Block-Randomizer, Mob-Farming, taktischer Wellen-Anordnung und Round-Robin-Arena-Kämpfen.

**Server-only Mod** – Spieler verbinden sich mit ganz normalem Vanilla-Minecraft-Client. Keine Client-Installation nötig.

---

## 📖 Inhaltsverzeichnis

1. [Spielprinzip](#-spielprinzip)
2. [Phasen im Detail](#-phasen-im-detail)
3. [Block-Randomizer](#-block-randomizer)
4. [Round-Robin Battle-System](#-round-robin-battle-system)
5. [Backpack](#-backpack)
6. [Tod & Respawn in der Arena](#-tod--respawn-in-der-arena)
7. [Punkte & Sieg](#-punkte--sieg)
8. [Installation](#-installation)
9. [Setup-Guide für Admins](#-setup-guide-für-admins)
10. [Befehlsübersicht](#-befehlsübersicht)
11. [Konfiguration](#-konfiguration)
12. [Tipps & Strategie](#-tipps--strategie)

---

## 🎯 Spielprinzip

Mehrere Teams kämpfen gegeneinander – aber **nicht direkt**. Stattdessen:

1. Jedes Team **farmt Mobs** in einer Welt mit verrückten Block-Drops.
2. Jedes Team **ordnet die getöteten Mobs** in 3 Wellen an.
3. Jedes Team muss dann **die Wellen eines anderen Teams überleben** in einer Arena.
4. Wer am meisten Punkte sammelt, gewinnt.

Je mehr und gefährlichere Mobs du farmst, desto härtere Wellen kannst du gegen deine Gegner schicken.

---

## 🌀 Phasen im Detail

Das Spiel läuft in **5 Phasen** ab:

### 1. 🧑‍🤝‍🧑 LOBBY
- Spieler erstellen / joinen Teams (`/mteam create`, `/mteam join`).
- Admin startet das Spiel mit `/mobarmy start`.

### 2. 🌾 FARM (Standard: 10 Min)
- Block-Randomizer ist **aktiv**: Alle Blöcke droppen ein zufällig gemapptes Item.
- Spieler erkunden die Welt, bauen ab, craften, kämpfen.
- **Jeder getötete Mob wird gezählt** für das Team des Killers.
- Backpack (`/bp`) ist verfügbar – team-shared Storage mit 54 Slots.
- Phase endet nach Timer (oder per `/mobarmy skip`).

### 3. 🧩 ARRANGE (Standard: 2 Min)
- Für jedes Team öffnet sich automatisch ein **6×9-Inventar-GUI**:
  ```
  ┌─────────────────────────────────────┐
  │ Pool: 9 Slots mit Spawn-Eggs       │  ← gekillte Mobs
  │ ───── Glas-Trenner ─────            │
  │ Welle 1 (9 Slots)                   │  ← hier reinziehen
  │ Welle 2 (9 Slots)                   │
  │ Welle 3 (9 Slots)                   │
  │ ───── Glas-Trenner ─────            │
  └─────────────────────────────────────┘
  ```
- **Alle Teammitglieder sehen Live-Updates** – wenn einer ein Egg verschiebt, sehen es alle anderen sofort. Ihr koordiniert eure Strategie gemeinsam.
- Jeder Stack-Count = Anzahl der Mobs dieses Typs in der Welle.
- Phase endet nach Timer → automatischer Übergang in Battle.

### 4. ⚔️ BATTLE
- Round-Robin: Team A kämpft gegen die Wellen von Team B, B gegen C, C gegen A, …
- Beim Match-Start bekommt jeder Spieler ein **Standard-Loadout** (Iron-Set, Sword, Bow, Pfeile, Food, Golden Apples).
- Das attackierende Team wird in die Arena teleportiert (`spawnA`), alle anderen Teams nach `spectatorPos`.
- Es spawnen 3 Wellen nacheinander aus den vom Defender-Team angeordneten Mobs.
- Welle gilt als geschafft, wenn alle Mobs tot sind → Pause → nächste Welle.
- Wenn das ganze Attacker-Team stirbt: Match endet sofort.
- Nach allen Matches: Endphase.

### 5. 🏆 END
- Ranking aller Teams wird angezeigt.
- Der Sieger bekommt ein riesiges Title-Display.
- Alle Spieler werden zur Lobby zurück teleportiert.

---

## 🎲 Block-Randomizer

> "Alles ist Verrat. Diamant könnte Sand sein. Gravel könnte Diamant sein."

- Beim Spielstart wird ein **Seed** erzeugt (oder fix in der Config gesetzt).
- Eine **deterministische 1:1 Permutation** wird über alle "natürlichen" Blöcke berechnet:
  - z.B. `Stone → Iron Ore`, `Dirt → Gravel`, `Oak Log → Coal Block`, ...
- Beim Abbau eines Blocks wird der Vanilla-Drop unterdrückt und stattdessen das gemappte Item gedroppt.
- **Jedes Item-Mapping kommt genau einmal vor** → keine Verzerrung, alles gleich wahrscheinlich.
- Block-Entities (Truhen, Öfen, Shulker-Boxen) sowie Bedrock/Barrier/Command-Blöcke sind **ausgeschlossen** und behalten ihr Vanilla-Verhalten.

> 💡 Der Seed bleibt für die ganze Runde stabil – wenn du also entdeckst dass `Cobblestone → Diamond` gemappt ist, bleibt das so.

---

## 🔁 Round-Robin Battle-System

Bei N Teams werden **N-1 Runden** gespielt. In Runde *k* kämpft jedes Team gegen die Mobs des Teams `(eigenerIndex + k + 1) % N`:

| Beispiel mit 3 Teams (A, B, C) |
|---|
| **Runde 1**: A vs B's Mobs · B vs C's Mobs · C vs A's Mobs |
| **Runde 2**: A vs C's Mobs · B vs A's Mobs · C vs B's Mobs |

So kämpft am Ende jedes Team gegen die Mobs jedes anderen Teams.

**Stuff-Reset**: Vor jedem Match wird das Inventar des Attacker-Teams geleert und das Standard-Loadout erneut vergeben → faire Bedingungen für jeden Kampf.

---

## 🎒 Backpack

- Befehl: `/bp` oder `/backpack`
- 54 Slots, **team-shared**: Was Alice reinpackt, kann Bob entnehmen.
- Funktioniert wie eine vanilla Doppel-Truhe.
- Bleibt zwischen den Phasen erhalten (resettet beim nächsten Spielstart).

> 💡 Nutze ihn für gemeinsame Vorräte: gefährliche Materialien, Heiltränke, gemappte "Diamanten"…

---

## 💀 Tod & Respawn in der Arena

In der Battle-Phase ist Sterben **nicht final**:

1. Spieler bekommt 0 HP → **Death wird gecancelt** (KeepInventory automatisch, da kein echter Tod stattfindet).
2. Spieler wird sofort geheilt und in den **Spectator-Modus** versetzt.
3. Teleport zur **Spectator-Plattform** über/neben der Arena → gute Sicht aufs Geschehen, aber **kein Einfluss** mehr auf den Kampf.
4. Nach `respawnDelayTicks` (Standard 30 Sekunden) wird der Spieler **automatisch zurück in die Arena** teleportiert mit frischem Loadout.

> 💡 Der Spectator-Modus erlaubt durch Wände zu sehen, aber nicht zu bauen oder anzugreifen – perfekt für die "Käfig"-Position.

---

## 🏆 Punkte & Sieg

| Aktion | Punkte |
|---|---|
| Mob in Farm-Phase getötet | +1 pro Mob |
| Welle in Battle besiegt | +10 pro Welle |

Am Ende werden alle Teams nach Punkten sortiert. Der höchste Score gewinnt.

---

## 📦 Installation

### Auf dem Server
1. Lade `mob-1.0.jar` aus `build/libs/` herunter.
2. Lade die passende **Fabric API** für 1.21.11 herunter (`fabric-api-0.141.2+1.21.11.jar`).
3. Stelle sicher, dass dein Server auf **Fabric Loader 0.19.2+** und **Java 21** läuft.
4. Lege beide JARs in den `mods/`-Ordner deines Servers.
5. Starte den Server.

### Auf dem Client
**Nichts.** Spieler joinen mit normalem Vanilla-Minecraft 1.21.11.

---

## 🛠 Setup-Guide für Admins

Vor dem ersten Spiel solltest du die Welt vorbereiten:

```
1. Stell dich in deine Lobby
   → /mobarmy setlobby

2. Geh zu einer Ecke deines Arena-Bereichs
   → /mobarmy setarena 1
3. Geh zur gegenüberliegenden Ecke (auf gewünschter Höhe)
   → /mobarmy setarena 2

4. Setze die Spieler-Spawnpunkte in der Arena
   → /mobarmy setspawn a    (eine Seite)
   → /mobarmy setspawn b    (andere Seite)

5. Setze den Mob-Spawn-Bereich (Mitte der Arena)
   → /mobarmy setmobspawn

6. Setze die Spectator-Plattform (über/neben Arena)
   → /mobarmy setspectator

7. Baue die Arena automatisch (Boden + Glaswände + Plattform)
   → /mobarmy buildarena

8. Speichere die Konfiguration
   → /mobarmy save
```

Danach kannst du jederzeit ein Spiel starten.

---

## 📋 Befehlsübersicht

### Spieler-Befehle
| Befehl | Beschreibung |
|---|---|
| `/mteam create <name> [farbe]` | Team erstellen (Farben: red, blue, green, yellow, …) |
| `/mteam join <name>` | Team beitreten |
| `/mteam leave` | Team verlassen |
| `/mteam list` | Alle Teams + Mitglieder anzeigen |
| `/bp` oder `/backpack` | Team-Backpack öffnen |

### Admin-Befehle
| Befehl | Beschreibung |
|---|---|
| `/mobarmy start` | Spiel starten |
| `/mobarmy stop` | Spiel abbrechen, alles auf Lobby zurücksetzen |
| `/mobarmy skip` | Aktuelle Phase überspringen (zum Testen) |
| `/mobarmy status` | Aktuelle Phase + verbleibende Zeit anzeigen |
| `/mobarmy setlobby` | Lobby-Position auf eigene Position setzen |
| `/mobarmy setarena <1\|2>` | Arena-Eckpunkt 1 oder 2 setzen |
| `/mobarmy setspawn a\|b` | Arena-Spawnpunkt setzen |
| `/mobarmy setmobspawn` | Mob-Spawn-Zentrum setzen |
| `/mobarmy setspectator` | Spectator-Position setzen |
| `/mobarmy buildarena` | Arena prozedural bauen |
| `/mobarmy save` | Config speichern |
| `/mteam remove <name>` | Team löschen |

---

## ⚙️ Konfiguration

Die Config liegt in `<world-folder>/mobarmy.json` und wird beim ersten Serverstart erzeugt.

```json
{
  "farmDurationSeconds": 600,
  "arrangeDurationSeconds": 120,
  "respawnDelayTicks": 600,
  "waveDelayTicks": 100,
  "waveCount": 3,
  "lobbyPos": { "x": 0, "y": 100, "z": 0 },
  "arenaPos1": { "x": -25, "y": 64, "z": -25 },
  "arenaPos2": { "x": 25, "y": 80, "z": 25 },
  "spectatorPos": { "x": 0, "y": 90, "z": 0 },
  "arenaSpawnA": { "x": -15, "y": 65, "z": 0 },
  "arenaSpawnB": { "x": 15, "y": 65, "z": 0 },
  "mobSpawnCenter": { "x": 0, "y": 65, "z": 0 },
  "randomizerSeed": 0,
  "buildArenaOnStart": true
}
```

| Feld | Bedeutung |
|---|---|
| `farmDurationSeconds` | Dauer der Farm-Phase in Sekunden |
| `arrangeDurationSeconds` | Dauer der Arrange-Phase |
| `respawnDelayTicks` | Wartezeit nach Tod (20 Ticks = 1 Sekunde) |
| `waveDelayTicks` | Pause zwischen den Wellen |
| `waveCount` | Anzahl Wellen pro Match (Standard 3) |
| `randomizerSeed` | `0` = zufälliger Seed pro Spiel; `>0` = fester Seed |
| `buildArenaOnStart` | Arena automatisch bei Battle-Start neu bauen |

---

## 🎯 Tipps & Strategie

### Für die Farm-Phase
- **Achte auf den Randomizer** – manchmal ist Dirt der Weg zu Diamanten.
- **Killt schwere Mobs** wie Endermen, Witches, Creepers für stärkere Wellen.
- **Vorsicht**: Jeder gekillte Mob ist ein Mob, den ein Gegner gegen dich einsetzen könnte – mehr ist nicht immer besser.

### Für die Arrange-Phase
- **Welle 1 = Aufwärmen**, Welle 3 = Endboss.
- Pack die schwersten Mobs (Witch, Wither Skeleton, Ravager) in Welle 3.
- **Mische Mob-Typen** für Synergien: Skeleton + Creeper = chaotisch.
- Plane gemeinsam – jeder Spieler im Team sieht das gleiche GUI.

### Für die Battle-Phase
- **Bleibt zusammen** – das Loadout enthält Schilde + Bögen, nutzt sie.
- **Esst Goldene Äpfel** vor schweren Wellen.
- Wer stirbt, kann seine Teammates aus der Spectator-Plattform anfeuern (oder per Chat coachen).

---

## 🧱 Architektur (für Entwickler)

```
mobarmy.lb.mobarmy
├── MobarmyMod              ← Entrypoint, registriert Events
├── config/MobarmyConfig    ← JSON Config in <world>/mobarmy.json
├── game/
│   ├── GamePhase           ← LOBBY / FARM / ARRANGE / BATTLE / END
│   └── GameManager         ← State-Machine, Phasen-Tick
├── team/                   ← Team-CRUD, Member-Lookup, killedMobs Map
├── backpack/               ← Team-shared Inventory + GUI-Factory
├── randomizer/             ← Seed-basierte Block→Item Permutation
├── ui/                     ← ArrangeInventory + Multi-Viewer ScreenHandler
├── arena/                  ← Bounds, prozeduraler Builder
├── battle/                 ← Round-Robin, WaveSpawner, Loadout
├── commands/               ← Brigadier-Commands
└── util/                   ← TaskScheduler, PlayerUtils, SpawnEggMap
```

### Verwendete Fabric-APIs
- `ModInitializer`
- `CommandRegistrationCallback`
- `ServerLifecycleEvents` (STARTED / STOPPING)
- `ServerTickEvents.END_SERVER_TICK`
- `PlayerBlockBreakEvents.BEFORE`
- `ServerLivingEntityEvents.AFTER_DEATH` + `ALLOW_DEATH`

### Kein Client-Mod, weil…
- GUIs nutzen `GenericContainerScreenHandler.createGeneric9x6` (vanilla).
- Mehrere Spieler öffnen **dieselbe Inventory-Instanz** → vanilla syncs Slot-Änderungen automatisch an alle Viewer.
- Block-Drops werden serverseitig manuell via `Block.dropStack` gespawnt.
- Death-Cancel passiert serverseitig – Client bekommt nur den Teleport.

---

## 📜 Lizenz

BSD-3-Clause

---

**Viel Spaß beim Spielen! ⚔️🎮**

