# AI_USAGE — tim 8, Checkpoint 1

Obavezan dokument (Checkpoint 1 §31.3): koji alati su korišćeni, šta je nastalo uz AI pomoć, kako je to
pregledano i provereno i koje slabosti su poznate. AI pomoć je dozvoljena, ali svaki član mora umeti da
prati, testira, menja i brani predati kod — posebno svoj deo.

## Alati

- **Claude (Anthropic)** — razrada specifikacije, podela posla, generisanje koda i testova, pregled.
- IntelliJ IDEA (prevođenje, debager), Git/GitHub, Docker Desktop.

## Šta je nastalo uz AI pomoć

| Član | Komponente i testovi |
|---|---|
| Ilija | osnova projekta (pom.xml, interfejsi, `InternalRecord`, `FileOps`), `MemTable`, `StorageEngine`, javni i admin API, `StatusService`, `Json`, `Main`; scenariji API-001…005 i FLS-007; `DESIGN.md` |
| Katarina | WAL pisac/čitač, klasifikacija nepotpunog završetka i oštećenja, `WalRegistry`, `Recovery`, `WalInspector`; scenariji WAL-001…008 |
| Vojislav | SSTable pisac/čitač, retki indeks, Bloom filter, `MergingIterator`, `SSTableInspector`; scenariji SST-001…006, CMP-001, CMP-002; `FORMAT.md` |
| Ivona | `NodeDir` i LOCK, `FileManifestStore`, `MaintenanceService` (flush, compaction), `OrphanCleaner`, `ManifestInspector`; scenariji FLS-001…006, CMP-003…005 |
| Emilija | failpoint okvir, `Log`, Dockerfile, Compose, `dbctl`, `InspectCli`, testkit (`Node`, `Ctx`, `RefModel`, `Cp1Runner`); scenariji MDL-001, MDL-002; `README.md` |

Praktično sav kod je nastao kao AI predlog, pa je zatim pregledan, pokrenut i ispravljan.

## Kako je kod pregledan i proveren

- Svaki član je pročitao svoje klase i uporedio ih sa odgovarajućim odeljcima `Checkpoint 1.md`
  (format bajtova, redosled koraka flush-a i oporavka, tabele failpoint-a).
- Prevođenje sa `-Xlint:all -Werror`; JUnit testovi po modulima (`./mvnw test`).
- Svih 33 obavezna scenarija (`./dbctl test checkpoint-1 --seed N`) rade nad pravim procesima baze,
  sa pravim padovima (SIGKILL i failpoint `CRASH`); pokretani su sa više seed vrednosti.
- `FORMAT.md` hex primeri su izvučeni iz bajtova koje proizvodi sam kod, a ne pisani ručno.
- Greške pronađene tokom provere i ispravljene: ~45 ms kašnjenja po HTTP zahtevu (Nagle/delayed ACK u JDK
  `HttpServer`, rešeno sa `sun.net.httpserver.nodelay`), brojač pogodaka za `wal.after-record-prefix`,
  Windows `AccessDenied` pri force-u direktorijuma, nastavak održavanja posle prelaska u `FAILED`
  (moglo je da obriše WAL posle neizvesne sinhronizacije manifesta), trka automatskog compaction-a u testu.

## Poznate slabosti i otvorena pitanja

- Čitanja se odbijaju kad je mehanizam u `FAILED` (konzervativna politika, opisana u `DESIGN.md`).
- Posle neuspelog flush-a pre preimenovanja manifesta nema automatskog ponovnog pokušaja iste generacije;
  pisci mogu da čekaju (backpressure) dok se ne pozove ručni flush ili restart.
- Na Windows-u se force direktorijuma preskače (ograničenje JDK-a); trajnost se garantuje u Linux kontejneru.
- `CRASH` je kraj procesa, ne nestanak struje: page cache operativnog sistema preživljava, pa scenario
  „stari manifest posle preimenovanja“ (FLS-006) u praksi uvek vidi novi manifest. Obe grane su obrađene u kodu.
- Kampanja padova (MDL-002) drži najviše jednu operaciju u toku pri padu; ne proverava linearizabilnost
  konkurentnih istorija.
