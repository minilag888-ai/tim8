# Veliki podaci — tim 8: distribuirana LSM baza

Checkpoint 1: lokalna LSM baza ključ–vrednost na jednom čvoru (Java 21, Maven, Docker profil `standalone`).
Arhitektura: `DESIGN.md`. Format na disku: `FORMAT.md`. Upotreba AI alata: `AI_USAGE.md`.

## Tim

| Član | GitHub | Paketi `rs.raf.dlsm.*` |
|---|---|---|
| Ilija Živadinović | IlijaZiv | `record`, `memtable`, `engine`, `api`, `json`, `Main` (+ osnova projekta) |
| Katarina Rakić | katarinaRakic | `wal`, `recovery` |
| Vojislav Mitrović | vojafull | `sstable`, `bloom`, `merge` |
| Ivona Stevanović | IvonaStevanovic | `node`, `manifest`, `maintenance` |
| Emilija Mićović | emicovic12024si | `failpoint`, `io`, `log`, `cli`, `testkit`, Docker, `dbctl` |

## Šta treba instalirati

- **Docker Desktop** (Windows/macOS) ili Docker Engine + Compose v2 (Linux) — dovoljno za sve `dbctl` komande.
- **Git Bash** na Windows-u (za `./dbctl`).
- Opciono, za rad bez Docker-a: **JDK 21** (Maven nije potreban, koristi se `./mvnw`).

## Standardne komande (Checkpoint 1 §32)

```bash
./dbctl build                                   # Docker slika (Maven radi unutar Docker-a)
./dbctl up standalone                           # pokreće čvor i čeka spremnost
./dbctl status                                  # status čvora (JSON)
./dbctl logs -f                                 # JSON logovi
./dbctl test checkpoint-1 --list                # spisak 33 scenarija
./dbctl test checkpoint-1 --seed 7              # svih 33 → reports/checkpoint-1-7.json
./dbctl test checkpoint-1 --case CP1-WAL-002 --seed 7
./dbctl inspect manifest                        # offline pregled volumena
./dbctl inspect wal
./dbctl inspect sstable 3
./dbctl down                                    # zaustavlja (podaci ostaju)
./dbctl reset                                   # briše volumen i izveštaje
```

Bez Docker-a (lokalni JDK 21): `DBCTL_LOCAL=1 ./dbctl test checkpoint-1 --seed 7`.

Portovi na računaru: javni API `http://127.0.0.1:18080`, admin API `http://127.0.0.1:18082`.

```bash
curl -X PUT  http://127.0.0.1:18080/v1/kv/alpha -H 'Content-Type: application/json' -d '{"value":"QQ=="}'
curl         http://127.0.0.1:18080/v1/kv/alpha
curl        "http://127.0.0.1:18080/v1/scan?start=a&end=z&limit=10"
curl -X POST http://127.0.0.1:18082/admin/v1/storage/flush -d '{}'
curl -X POST http://127.0.0.1:18082/admin/v1/storage/compact -d '{}'
```

## Prevođenje i JUnit testovi

```bash
./mvnw test          # prevodi i pokreće JUnit testove
./mvnw package       # pravi target/dlsm.jar
```

Celo testiranje scenarija ide kroz `./dbctl test checkpoint-1 --seed N`. Svaki scenario pokreće pravi
produkcioni proces baze nad svojim direktorijumom; izveštaj je u `reports/`, a logovi i artefakti u
`reports/artifacts/<ID>/`.

## Konfiguracija (promenljive okruženja)

| Promenljiva | Podrazumevano | Značenje |
|---|---|---|
| `NODE_ID` | `standalone` | identitet čvora (upisuje se u `node.json`) |
| `DATA_DIR` | `/var/lib/dlsm` | direktorijum baze |
| `PUBLIC_PORT` / `ADMIN_PORT` | 8080 / 8082 | portovi u kontejneru |
| `MEMTABLE_MAX_BYTES` | 1048576 | prag rotacije MemTable |
| `TEST_MODE` | `true` u Compose-u | omogućava failpoint API |
