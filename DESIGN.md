# DESIGN — tim 8, Checkpoint 1

Opis arhitekture lokalne LSM baze ključ–vrednost (Checkpoint 1 §31.1). Sekcije su podeljene po vlasnicima
modula, ali ceo tim mora umeti da objasni svaku od njih na odbrani.

## 1. Granice modula — Ilija

Svi paketi su pod `rs.raf.dlsm`. Zavisnosti idu samo „naniže“; niži sloj nikad ne zna za viši.

| Paket | Vlasnik | Odgovornost |
|---|---|---|
| `record` | Ilija | `InternalRecord` (ključ, redni broj, vrsta PUT/DELETE, vrednost) i granice veličina |
| `memtable` | Ilija | uređena MemTable u memoriji (`TreeMap`), logička veličina `32 + k + v` po zapisu |
| `engine` | Ilija | `StorageEngine`: put mutacije, GET, SCAN, rotacija, backpressure, režimi rada |
| `api` | Ilija | JDK `HttpServer`: javni KV API i administrativni API, validacija, status |
| `json` | Ilija | sopstveni mali JSON parser/pisac (bez spoljnih biblioteka) |
| `wal` | Katarina | segmenti WAL-a: pisanje sa force, čitanje, klasifikacija završetka, registar segmenata |
| `recovery` | Katarina | algoritam pokretanja (§21.1) i kategorije oštećenja |
| `sstable`, `bloom`, `merge` | Vojislav | format SSTable, Bloom filter, čitač sa retkim indeksom, spajanje izvora |
| `node`, `manifest`, `maintenance` | Ivona | direktorijum čvora i LOCK, `MANIFEST.json`, flush, compaction, čišćenje |
| `failpoint`, `io`, `log`, `cli`, `testkit` | Emilija | failpoint-i, `FileOps`, JSON logovi, `InspectCli`, 33 scenarija |

Sav pristup disku ide kroz `io.FileOps` (`LocalFileOps` u produkciji). Zato svaki failpoint sa akcijom
`IO_ERROR` prolazi kroz isti produkcioni put greške kao prava greška diska.

## 2. Linearizacija mutacije i redosled WAL force-a — Ilija

PUT i DELETE (`StorageEngine.put/delete`) rade sledeće, sve pod **write** bravom `engineLock`:

1. provera da je mehanizam `READY` (inače `503`), čekanje na backpressure ako je potrebno (odeljak 4);
2. dodela sledećeg rednog broja (`nextSequence++`);
3. kodiranje celog WAL okvira u memoriji i `WalWriter.append` = upis okvira + `FileChannel.force`;
4. **tačka linearizacije**: uspešan povratak iz force-a. Od tog trenutka mutacija je trajna;
5. upis u aktivnu MemTable; tek tada se oslobađa brava i vraća `200`.

Pošto čitanja uzimaju read bravu, nijedno čitanje ne može da vidi stanje između koraka 4 i 5. Ako append ili
force baci grešku, mutacija ne dobija uspeh, mehanizam prelazi u `FAILED` (put upisa je zatvoren do restarta),
a oporavak kasnije odlučuje da li je okvir kompletan (WAL-005/006).

## 3. Brave i redosled preuzimanja — Ilija

| Brava | Ko je drži | Šta štiti |
|---|---|---|
| `engineLock` (pravedni `ReentrantReadWriteLock`) | write: PUT/DELETE, rotacija, objavljivanje u memoriji; read: GET, SCAN | aktivna/nepromenljiva MemTable, lista čitača SSTable, redni brojevi |
| `maintenanceLock` (`ReentrantLock`) | ceo flush ili compaction posao | manifest, dodela ID-jeva tabela, brisanje fajlova |
| `FileManifestStore` (sinhronizovan) | objavljivanje manifesta | trenutni `Manifest` u memoriji |

Redosled je uvek **`maintenanceLock` → `engineLock`**. Klijentska mutacija nikad ne uzima `maintenanceLock`,
pa ciklus nije moguć. Skupa izgradnja SSTable fajla radi **bez** `engineLock`-a; write brava se uzima samo
na kratko, za rotaciju i za zamenu liste čitača (`completeFlush`, `replaceTables`).

Održavanje je serijalizovano na jednoj niti (`MaintenanceService`, single-thread executor). Ručni flush,
ručni compaction, automatski flush posle rotacije i automatski compaction idu kroz isti red.

## 4. MemTable rotacija i backpressure — Ilija

- Aktivna MemTable rotira kad bi upis prešao `MEMTABLE_MAX_BYTES`. Rotacija pravi novi WAL segment (zaglavlje +
  force), pa tek onda menja aktivnu generaciju; stara postaje **jedina** nepromenljiva MemTable i ide u red za flush.
- Najviše jedna nepromenljiva MemTable. Ako je mesto zauzeto, a aktivna je puna, pisac čeka na
  `Condition spaceAvailable` (vezan za write bravu). `await` oslobađa bravu, pa flush može da završi
  `completeFlush`, koji signalizira sve pisce. Rok čekanja je rok zahteva (`X-Request-Timeout-Ms`).
- Status prikazuje `writeBackpressure` i `backpressuredWriters` (FLS-007).
- Čitanja tokom backpressure-a rade normalno, jer koriste postojeće izvore pod read bravom.

## 5. Algoritmi čitanja i spajanja skeniranja — Ilija i Vojislav

**GET**: aktivna MemTable → nepromenljiva MemTable → SSTable od najnovije ka najstarijoj. Prvi pogodak pobeđuje
(najveći redni broj); tombstone daje `404 NOT_FOUND`. Za svaku SSTable: provera opsega `[minKey, maxKey]`,
pa Bloom filter (negativan odgovor = bez čitanja bloka), pa binarna pretraga retkog indeksa i čitanje
tačno jednog bloka (u retkim slučajevima dva).

**SCAN `[start, end)` sa `limit`**: pod read bravom se uzima snapshot svih izvora; `MergingIterator` (min-heap
po ključu, pa po opadajućem rednom broju) daje za svaki ključ samo najnoviju verziju, preskače tombstone-e i
staje posle `limit` živih ključeva. SSTable iterator kreće od bloka koji pokriva `start` (retki indeks), pa
čita samo potrebne blokove (`rangeScanBlocksRead`, SST-004). Rezultat je strogo rastući i bez duplikata.

## 6. Objavljivanje pri flush-u i compaction-u, čišćenje — Ivona

**Flush** (§19.1, 13 koraka): rotacija → dodela ID-ja tabele → izgradnja u `lsm/tmp/` → force fajla →
`ATOMIC_MOVE` u `lsm/sst/` → force direktorijuma → novi manifest (generacija + 1, tabela dodata,
`flushedThroughSequence` = najveći redni broj generacije) objavljen kao `MANIFEST-<gen>.tmp` → force →
`ATOMIC_MOVE` preko `MANIFEST.json` → force direktorijuma → zamena čitača u memoriji → brisanje pokrivenih
WAL segmenata.

- SSTable postaje merodavna tek posle force-a direktorijuma posle preimenovanja manifesta.
- Greška **pre** preimenovanja manifesta: stari manifest ostaje merodavan, finalizovan izlaz se briše, mehanizam
  ostaje `READY`, a nepromenljiva MemTable ostaje zaštićena WAL-om. Generacija čiji je flush pao ne pokušava se
  automatski ponovo u istoj seriji; preuzima je sledeći ručni flush ili oporavak (FLS-005).
- Greška **posle** preimenovanja (force direktorijuma): trajnost je neizvesna, mehanizam ide u `FAILED`, ništa
  se ne briše, a održavanje se zaustavlja do restarta (FLS-006).

**Compaction**: ručni (najmanje 2 tabele) i automatski (prag od 4 tabele). Ulazi se biraju pod
`maintenanceLock`; spajanje zadržava najveći redni broj po ključu i **zadržava tombstone-e** (nema nivoa ispod).
Pre objavljivanja se ponovo čita najnoviji manifest: izabrani ulazi moraju i dalje postojati, a svaka
nepovezana tabela (npr. iz konkurentnog flush-a) se čuva (CMP-005). Posle objavljivanja i zamene čitača,
ulazi se zatvaraju i brišu. `flushedThroughSequence` se ne menja.

**Čišćenje pri pokretanju** (`OrphanCleaner`): briše sve iz `lsm/tmp/` i svaku `.sst` koju manifest ne
referencira. ID-jevi se dodeljuju iznad svih pronađenih pre brisanja, pa se ID orphan-a nikad ne koristi ponovo.

**Redosled posle oporavka**: oporavljena nepromenljiva MemTable ide na flush odmah **posle** `READY`.

## 7. Oporavak i politika oštećenja — Katarina

`Recovery.run` (§21.1): LOCK (`FileChannel.tryLock`, drugi proces dobija `DIRECTORY_LOCKED`) → provera
`node.json` (`NODE_IDENTITY`) → učitavanje manifesta (neprazan volumen bez manifesta = `MANIFEST_MISSING`,
nikad nova prazna baza) → strimovana validacija svake referencirane SSTable i poređenje sa manifestom →
dodela ID-jeva → čišćenje tmp/orphan → čitanje WAL segmenata rastuće → replay zapisa iznad
`flushedThroughSequence` u jednu oporavljenu nepromenljivu MemTable (bez dopisivanja u WAL) →
`nextSequence = max(high-water, najveći WAL redni broj) + 1` → nov aktivni WAL segment → brisanje pokrivenih.

- **Nepotpun završetak** (samo poslednji okvir poslednjeg segmenta je presečen): odseca se tačno do početka
  tog okvira + force. To je jedini slučaj popravke (WAL-002).
- **Oštećenje** (pogrešan CRC kompletnog okvira, zaglavlje segmenta, neispravan manifest, nedostajuća ili
  oštećena referencirana SSTable, neslaganje sa manifestom): čvor ostaje živ u `FAILED` sa stabilnim kodom
  (`WAL_CORRUPT`, `MANIFEST_INVALID`, `MANIFEST_MISSING`, `REFERENCED_FILE_MISSING`,
  `MANIFEST_TABLE_MISMATCH`, kategorije SSTable oštećenja…), ne opslužuje KV saobraćaj i ništa ne prepravlja.
- Svaki korak je bezbedan za ponavljanje posle novog pada (recovery hook-ovi u MDL-002).

## 8. Failpoint okvir i testiranje — Emilija

- 21 obavezni failpoint (`FailpointCatalog`), akcije `PAUSE`, `CRASH` (`Runtime.halt(137)`), `IO_ERROR`
  (kroz `FileOps`), `RETURN_ERROR`; filteri, `afterHits`, `maxTriggers`; HTTP admin API i bootstrap pravila
  kroz `DLSM_TEST_BOOTSTRAP_FAILPOINTS_B64` za recovery hook-ove. Radi samo uz `TEST_MODE=true`.
- `testkit` pokreće **pravi produkcioni `Main`** kao proces dete nad izolovanim direktorijumom; restart je
  nov proces nad istim volumenom, a pad je SIGKILL ili failpoint `CRASH`.
- Referentni model (`RefModel`) je obična `TreeMap` i ne deli kod sa WAL-om, MemTable-om, SSTable-om ni spajanjem.
- `./dbctl test checkpoint-1 --seed N` pokreće svih 33 scenarija i piše `reports/checkpoint-1-<seed>.json`.

## 9. Namerna odstupanja i tumačenja

- Čitanja se odbijaju (`503`) kad je mehanizam u `FAILED`, iako bi neka mogla da se opsluže: jednostavnija
  i konzervativnija politika.
- Oporavljena nepromenljiva MemTable se flush-uje posle `READY`, a ne pre (brže pokretanje).
- Posle neuspelog flush-a pre preimenovanja manifesta nema automatskog ponovnog pokušaja iste generacije
  (izbegava petlju greške); ponavlja ga ručni flush ili restart.
- JSON se obrađuje sopstvenim kodom (`json.Json`), bez spoljnih zavisnosti.
- `forceDirectory` se na Windows-u preskače (JDK ne dozvoljava otvaranje direktorijuma); Docker slika je Linux.
