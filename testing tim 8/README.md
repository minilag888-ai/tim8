# Veliki podaci — tim 8: distribuirana LSM baza

Checkpoint 1: lokalna LSM baza ključ–vrednost na jednom čvoru (Java 21, Maven, Docker profil `standalone`).
Ko radi šta: `PODELA_POSLA.md` (deljen u grupi). Specifikacija: `Checkpoint 1.md` i `Project Info.md`.

## Tim

| Član | GitHub | Paketi `rs.raf.dlsm.*` |
|---|---|---|
| Ilija Živadinović | IlijaZiv | `record`, `memtable`, `engine`, `api` (+ osnova projekta) |
| Katarina Rakić | katarinaRakic | `wal`, `recovery` |
| Vojislav Mitrović | vojafull | `sstable`, `bloom`, `merge` |
| Ivona Stevanović | IvonaStevanovic | `node`, `manifest`, `maintenance` |
| Emilija Mićović | emicovic12024si | `failpoint`, `io`, `cli`, `testkit`, Docker, `dbctl` |

## Prevođenje i testovi

```bash
./mvnw test          # prevodi i pokreće JUnit testove
./mvnw package       # pravi target/dlsm.jar
java -jar target/dlsm.jar
```

Docker (`./dbctl build`, `./dbctl up standalone`) dodaje Emilija.

## Pravila rada

- Svako radi samo u svojim paketima; tuđi moduli se koriste preko interfejsa iz osnove.
- Interfejsi (`InternalRecord`, `WalWriter`, `WalReader`, `SSTableWriter`, `SSTableReader`, `MergingIterator`,
  `ManifestStore`, `FileOps`, `Failpoints`) menjaju se samo uz dogovor celog tima.
- Sav pristup disku ide kroz `FileOps`, nikad direktno `FileChannel`/`Files`.
- Git: svoja grana `ime/tema` → push na nju → Pull Request ka `main` → drugi član pregleda i spaja.
  Pre PR-a: `git fetch origin && git merge origin/main && ./mvnw test`.
