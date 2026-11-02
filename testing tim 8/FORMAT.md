# FORMAT — tim 8, Checkpoint 1

Tačan format bajtova na disku (Checkpoint 1 §31.2). Svi celi brojevi su big-endian, kontrolne sume CRC32C,
osim ako sekcija ne kaže drugačije.

## WAL segment i okvir zapisa — Katarina
_TODO: zaglavlje, okvir, zaštita dužine, pokrivenost CRC-a, hex primer jednog zapisa_

## SSTable — Vojislav
_TODO: oblasti, zapis, blok, retki indeks, Bloom filter, footer, hex primer male tabele_

## Manifest i node.json — Ivona
_TODO: polja MANIFEST.json i node.json, ponašanje verzija formata_
