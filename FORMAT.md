# FORMAT — tim 8, Checkpoint 1

Tačan format bajtova na disku (Checkpoint 1 §31.2). **Svi celi brojevi su big-endian, bez znaka**, osim ako
nije drugačije rečeno. Sve kontrolne sume su **CRC32C** (Castagnoli, `java.util.zip.CRC32C`).
Ključevi su ASCII `[A-Za-z0-9._~-]`, dužine 1..128; vrednosti su 0..262144 bajta.

## Raspored direktorijuma

```
<DATA_DIR>/
├── LOCK                       isključivo vlasništvo procesa (FileChannel.tryLock)
├── node.json                  {"formatVersion":1,"nodeId":"standalone"}
└── lsm/
    ├── MANIFEST.json          jedini merodavni spisak tabela
    ├── wal/wal-<20 cifara>.log
    ├── sst/sst-<20 cifara>.sst
    └── tmp/                   privremeni fajlovi (*.tmp); uvek se brišu pri pokretanju
```

## 1. WAL segment — Katarina (usvojen raspored iz §16.3.1)

### Zaglavlje segmenta (24 B)

| Offset | Dužina | Polje |
|---|---|---|
| 0 | 8 | ASCII `LSMWAL01` |
| 8 | 2 | verzija formata fajla = 1 |
| 10 | 2 | rezervisano = 0 |
| 12 | 8 | ID segmenta (mora odgovarati imenu fajla) |
| 20 | 4 | CRC32C bajtova 0..19 |

### Okvir zapisa (30 + k + v B)

| Offset | Dužina | Polje |
|---|---|---|
| 0 | 4 | `totalFrameLength` = 30 + k + v |
| 4 | 4 | `totalFrameLength XOR 0xFFFFFFFF` (zaštita dužine) |
| 8 | 2 | verzija zapisa = 1 |
| 10 | 1 | vrsta: 1 = PUT, 2 = DELETE |
| 11 | 1 | rezervisano = 0 |
| 12 | 8 | redni broj mutacije (> 0) |
| 20 | 2 | dužina ključa k (1..128) |
| 22 | 4 | dužina vrednosti v (0..262144; 0 za DELETE) |
| 26 | k | ključ |
| 26+k | v | vrednost |
| 26+k+v | 4 | CRC32C bajtova **8 .. 25+k+v** (sve posle zaštićene dužine, bez same sume) |

**Klasifikacija pri čitanju:** ako dužina i njen komplement ne odgovaraju, ili je dužina van
`[31, 30+128+262144]` → oštećenje. Ako okvir prelazi kraj fajla, a reč je o **poslednjem** okviru
**poslednjeg** segmenta → nepotpun završetak (odseca se). Svaki kompletan okvir sa pogrešnim CRC-om → oštećenje.

### Primer: zaglavlje segmenta 1 i zapis `PUT k1 = "ab"`, redni broj 1

```
zaglavlje
0000: 4c 53 4d 57 41 4c 30 31  "LSMWAL01"
0008: 00 01                    verzija fajla 1
000a: 00 00                    rezervisano
000c: 00 00 00 00 00 00 00 01  ID segmenta 1
0014: e8 56 78 a9              CRC32C(0..19)

okvir (34 B)
0000: 00 00 00 22              totalFrameLength = 34
0004: ff ff ff dd              34 XOR 0xFFFFFFFF
0008: 00 01                    verzija zapisa 1
000a: 01                       PUT
000b: 00                       rezervisano
000c: 00 00 00 00 00 00 00 01  redni broj 1
0014: 00 02                    k = 2
0016: 00 00 00 02              v = 2
001a: 6b 31                    "k1"
001c: 61 62                    "ab"
001e: cd 7a e5 3a              CRC32C(bajtovi 8..29)
```

## 2. SSTable — Vojislav

Fajl je nepromenljiv posle finalizacije. Oblasti redom:

```
[ZAGLAVLJE 24 B][BLOK 0]...[BLOK n-1][INDEKS][BLOOM][FOOTER 100 B]
```

| Oblast | Raspored |
|---|---|
| Zaglavlje (24 B) | `LSMSST01` (8) · verzija u16 = 1 · rezervisano u16 = 0 · tableId u64 · CRC32C(0..19) u32 |
| Blok | dužina sadržaja u32 · CRC32C(sadržaj) u32 · sadržaj (cilj ~4096 B; zapis se ne deli između blokova) |
| Zapis u bloku | dužina ključa u16 · ključ · redni broj u64 · vrsta u8 (1 PUT, 2 DELETE) · dužina vrednosti u32 · vrednost |
| Indeks (retki) | broj stavki u32 · za svaki blok: dužina ključa u16 · prvi ključ bloka · offset bloka u64 · ukupna dužina bloka u32 · zatim dužina minKey u16 · minKey · dužina maxKey u16 · maxKey |
| Bloom | algoritam u8 = 1 · broj proba u8 (k = 7) · broj bajtova u32 · bitovi |
| Footer (100 B) | `LSMSSTFT` (8) · verzija u16 · rezervisano u16 · tableId u64 · dataOffset u64 · dataLength u64 · indexOffset u64 · indexLength u32 · indexCrc u32 · bloomOffset u64 · bloomLength u32 · bloomCrc u32 · entryCount u64 · minSequence u64 · maxSequence u64 · blockCount u32 · CRC32C(0..95) u32 |

- Zapisi su strogo rastući po ključu, po jedan zapis po ključu. Tombstone ima dužinu vrednosti 0.
- **Bloom filter:** `m = max(64, 10·n)` bitova (zaokruženo na bajt), `k = 7`. Ulaz za heš je ASCII ključ;
  SHA-256, prvih 8 bajtova = `h1`, sledećih 8 = `h2` sa najnižim bitom postavljenim na 1 (big-endian), proba
  `i` = `(h1 + i·h2) mod m` (64-bitno sabiranje sa prelivanjem, neoznačen ostatak).
  Bit `j` je bit `j mod 8` (LSB prvi) bajta `j / 8`. Tombstone-i se dodaju u filter.
- **Validacija pri otvaranju:** footer (magic, CRC, verzija), zaglavlje, CRC indeksa i Bloom-a, granice
  oblasti; CRC bloka se proverava pri svakom čitanju bloka. Pri oporavku se čita i svaki blok (strimovano).

### Primer: tabela 7 sa `a = "x"` (redni broj 1) i tombstone-om `b` (redni broj 2) — 204 B

```
0000: 4c 53 4d 53 53 54 30 31 00 01 00 00 00 00 00 00   zaglavlje: "LSMSST01", verzija 1, tableId =
0010: 00 00 00 07 15 9f 48 53                           7, CRC32C(0..19)
0018: 00 00 00 21 68 88 69 a3                           blok 0: sadržaj 33 B, CRC32C(sadržaj)
0020: 00 01 61 00 00 00 00 00 00 00 01 01 00 00 00 01   zapis: k=1 "a", seq 1, PUT, v=1
0030: 78                                                "x"
0031: 00 01 62 00 00 00 00 00 00 00 02 02 00 00 00 00   zapis: k=1 "b", seq 2, DELETE, v=0
0041: 00 00 00 01 00 01 61 00 00 00 00 00 00 00 18      indeks: 1 stavka, prvi ključ "a", offset 0x18,
0050: 00 00 00 29 00 01 61 00 01 62                     dužina 41, minKey "a", maxKey "b"
005a: 01 07 00 00 00 08 00 0d 88 41 10 02 12 c0         Bloom: alg 1, k=7, 8 B, bitovi
0068: 4c 53 4d 53 53 54 46 54 00 01 00 00               footer: "LSMSSTFT", verzija 1, rez.
0074: 00 00 00 00 00 00 00 07                           tableId 7
007c: 00 00 00 00 00 00 00 18 00 00 00 00 00 00 00 29   dataOffset 24, dataLength 41
008c: 00 00 00 00 00 00 00 41 00 00 00 19 8f f3 40 1e   indexOffset 65, indexLength 25, indexCrc
009c: 00 00 00 00 00 00 00 5a 00 00 00 0e 85 a7 83 01   bloomOffset 90, bloomLength 14, bloomCrc
00ac: 00 00 00 00 00 00 00 02                           entryCount 2
00b4: 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00 02   minSequence 1, maxSequence 2
00c4: 00 00 00 01 e1 99 93 ba                           blockCount 1, CRC32C(footer 0..95)
```

## 3. Manifest i node.json — Ivona

`MANIFEST.json` (UTF-8 JSON, jedan objekat):

| Polje | Tip | Značenje |
|---|---|---|
| `formatVersion` | broj | 1 |
| `generation` | broj | raste tačno za 1 pri svakom objavljivanju; početni prazan manifest ima 0 |
| `nextTableId` | broj | sledeći slobodan ID tabele (≥ svaki referencirani ID + 1) |
| `flushedThroughSequence` | broj | svaki redni broj ≤ ove vrednosti je ugrađen u referencirane SSTable |
| `sstables` | niz | po tabeli: `tableId`, `file`, `minKey`, `maxKey`, `minSequence`, `maxSequence`, `entryCount`, `sizeBytes` |

Primer: `{"formatVersion":1,"generation":1,"nextTableId":2,"flushedThroughSequence":84,"sstables":[{"tableId":1,"file":"sst-00000000000000000001.sst","minKey":"p000","maxKey":"pnikad","minSequence":4,"maxSequence":84,"entryCount":81,"sizeBytes":3547}]}`

Objavljivanje: `lsm/tmp/MANIFEST-<gen>.tmp` → force → `ATOMIC_MOVE` preko `lsm/MANIFEST.json` → force `lsm/`.
Pri učitavanju se proverava: ispravan JSON, sva polja i tipovi, `formatVersion`, jedinstveni ID-jevi i imena,
`nextTableId` veći od svakog ID-ja, `minKey ≤ maxKey`, `minSequence ≤ maxSequence ≤ flushedThroughSequence`,
pa za svaku tabelu postojanje fajla i slaganje footer-a (tableId, entryCount, redni brojevi, veličina).

`node.json`: `{"formatVersion":1,"nodeId":"<NODE_ID>"}`, upisan atomski pri prvoj inicijalizaciji.

## 4. Ponašanje verzija formata

Svaka struktura nosi verziju (WAL fajl, WAL zapis, SSTable zaglavlje i footer, manifest, node.json).
Ova implementacija čita i piše **samo verziju 1**; svaka druga vrednost je oštećenje / nepodržan format i
vodi u `FAILED` (nikad tiho ignorisanje). Rezervisana polja moraju biti 0.
